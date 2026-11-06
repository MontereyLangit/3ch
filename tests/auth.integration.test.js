import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac, pbkdf2Sync } from 'node:crypto';
import { existsSync } from 'node:fs';
import { unstable_dev } from 'wrangler';

const built = existsSync(new URL('../dist/index.js', import.meta.url));
const secret = 'local-auth-integration-secret-0123456789';
const cookieName = '__Host-ch3s_sid';
const sign = payload => `${payload}.${createHmac('sha256', secret).update(payload).digest().subarray(0, 16).toString('base64url')}`;
const signedCookie = fields => `${cookieName}=${sign(fields.join('.'))}`;

test('Wrangler: 認証の HTTP と Account・AccountIndex・AuthRate の永続化', {
  timeout: 120000,
  skip: built ? false : 'dist/index.js がありません（npm run v6:build を先に実行）',
}, async t => {
  const worker = await unstable_dev(new URL('./support/auth-worker.mjs', import.meta.url).pathname, {
    config: new URL('./support/wrangler.auth.jsonc', import.meta.url).pathname,
    local: true, persist: false, port: 0, logLevel: 'error',
    experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false },
  });
  t.after(() => worker.stop());
  const origin = `http://localhost:${worker.port}`;
  const rpc = async (binding, name, method, ...args) => {
    const response = await fetch(origin + '/__test/rpc', { method: 'POST', body: JSON.stringify({ binding, name, method, args }) });
    const body = await response.json();
    assert.equal(response.status, 200, `${binding}.${method}: ${body.error}`);
    return body.result;
  };
  const account = (aid, method, ...args) => rpc('ACCOUNT_DO', aid, method, ...args);
  const index = (kind, method, ...args) => rpc('ACCOUNT_INDEX_DO', kind, method, ...args);
  const rate = (key, kind, now) => rpc('AUTH_RATE_DO', key, 'hit', kind, now);
  const post = (path, data = {}, headers = {}) => fetch(origin + path, {
    method: 'POST', headers: { origin, ...headers }, body: new URLSearchParams(data), redirect: 'manual',
  });
  const session = cookie => fetch(origin + '/auth/session/', { headers: cookie ? { cookie } : {} }).then(response => response.json());
  const now = Math.floor(Date.now() / 1000);
  const profile = (aid, uid, extra = {}) => ({ aid, uid, kind: 'client', createdAt: now, agreedAt: now, agreementVersion: 'v1', status: 'active', generation: 0, ...extra });

  await t.test('workerd の PBKDF2 は100000回と100001回で独立した派生値に一致する', async () => {
    const response = await fetch(origin + '/__test/pbkdf2');
    assert.equal(response.status, 200);
    const result = await response.json();
    const expected = pbkdf2Sync(Buffer.from('Café🔒password', 'utf8'), Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex'), 100000, 32, 'sha256').toString('hex');
    assert.equal(result.iterations, 100000);
    assert.equal(result.bits, 256);
    assert.equal(result.saltBytes, 16);
    assert.equal(result.hex, expected);
    const expected100001 = pbkdf2Sync(Buffer.from('Café🔒password', 'utf8'), Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex'), 100001, 32, 'sha256').toString('hex');
    assert.equal(result.hex100001, expected100001);
    assert.equal((await fetch(origin + '/__test/pbkdf2', { method: 'POST' })).status, 405);
  });

  await t.test('新しい DO クラスを実際の migration と RPC で作成し世代・期限を照合する', async () => {
    const aid = 'aaaaaaaaaaaaaaaa';
    assert.deepEqual(await account(aid, 'describeForPosting', aid, 0, now), { code: 'auth:account:missing' });
    const created = await account(aid, 'create', profile(aid, 'client01', { expiresAt: now + 1 }), now);
    assert.equal(created.code, '');
    assert.equal(created.expiresAt, now + 365 * 86400);
    assert.equal(created.lv, 1);
    assert.equal((await account(aid, 'create', profile(aid, 'client02'), now)).code, 'auth:account:exists');
    assert.equal((await account(aid, 'describeForPosting', aid, 1, now)).code, 'auth:session:invalid');
    assert.equal((await account(aid, 'describeForPosting', aid, 0, now + 365 * 86400)).code, 'auth:session:expired');
    assert.equal(await account(aid, 'bumpGeneration', now), 1);
    assert.equal((await account(aid, 'describeForPosting', aid, 0, now)).code, 'auth:session:invalid');
    assert.equal((await account(aid, 'describeForPosting', aid, 1, now)).code, '');
    const invalidAid = 'bbbbbbbbbbbbbbbb';
    assert.equal((await account(invalidAid, 'create', profile(invalidAid, 'client02', { status: 'frozen' }), now)).code, 'auth:invalid');
    assert.equal((await account(invalidAid, 'create', profile(aid, 'client02'), now)).code, 'auth:invalid');
    const competing = await Promise.all(Array.from({ length: 4 }, () => account(invalidAid, 'create', profile(invalidAid, 'client02'), now)));
    assert.equal(competing.filter(result => result.code === '').length, 1);
    assert.equal(competing.filter(result => result.code === 'auth:account:exists').length, 3);
  });

  await t.test('専ブラキーの初回 UA 紐付けと失効が保存されハッシュは本人画面へ返らない', async () => {
    const aid = 'aaaaaaaaaaaaaaaa';
    const hash = 'a'.repeat(64);
    const uaHash = 'b'.repeat(64);
    assert.equal((await account(aid, 'issueToken', 'token01', hash, now)).code, '');
    assert.equal((await account(aid, 'useToken', 'token01', uaHash, 'LocalBrowser/1.0', now)).code, '');
    assert.equal((await account(aid, 'describeForPosting', aid, 1, now, 'token01', uaHash)).code, '');
    assert.equal((await account(aid, 'describeForPosting', aid, 1, now, 'token01', 'c'.repeat(64))).code, 'auth:token:ua-mismatch');
    const panel = await account(aid, 'inspect');
    assert.equal(panel.tokens[0].uaLabel, 'LocalBrowser/1.0');
    assert.equal(Object.hasOwn(panel.tokens[0], 'hash'), false);
    assert.equal(Object.hasOwn(panel.tokens[0], 'uaHash'), false);
    assert.equal(await account(aid, 'revokeToken', 'token01', now), '');
    assert.equal((await account(aid, 'describeForPosting', aid, 1, now, 'token01', uaHash)).code, 'auth:token:revoked');
  });

  await t.test('索引の並行予約・未確定非公開・所有権・uid 再利用禁止を保存する', async () => {
    const hash = '1'.repeat(64);
    const aids = Array.from({ length: 8 }, (_, number) => `owner${number}`.padEnd(16, '0'));
    const results = await Promise.all(aids.map(aid => index('uid', 'register', hash, aid, now)));
    assert.equal(results.filter(result => result === '').length, 1);
    assert.equal(results.filter(result => result === 'auth:index:conflict').length, 7);
    const owner = aids[results.indexOf('')];
    const other = aids.find(aid => aid !== owner);
    assert.deepEqual(await index('uid', 'lookup', hash), { code: 'auth:index:missing' });
    assert.equal(await index('uid', 'confirm', hash, other, now), 'auth:index:ownership');
    assert.equal(await index('uid', 'remove', hash, other), 'auth:index:ownership');
    assert.equal(await index('uid', 'confirm', hash, owner, now), '');
    assert.deepEqual(await index('uid', 'lookup', hash), { code: '', aid: owner });
    assert.equal(await index('uid', 'remove', hash, owner), 'auth:index:permanent');
    assert.equal(await index('uid', 'register', hash, other, now + 86400), 'auth:index:conflict');

    const pending = '2'.repeat(64);
    assert.equal(await index('login', 'register', pending, owner, now), '');
    assert.equal(await index('login', 'confirm', pending, owner, now + 600), 'auth:index:ownership');
    assert.equal(await index('login', 'register', pending, other, now + 600), '');
    assert.equal(await index('login', 'confirm', pending, owner, now + 600), 'auth:index:ownership');
    assert.equal(await index('login', 'confirm', pending, other, now + 600), '');
    assert.equal(await index('login', 'remove', pending, other), '');
    assert.deepEqual(await index('login', 'lookup', pending), { code: 'auth:index:missing' });

    const token = '3'.repeat(64);
    assert.equal(await index('token', 'register', token, owner, now), 'auth:invalid');
    assert.equal(await index('token', 'register', token, owner, now, 'token02'), '');
    assert.equal(await index('token', 'confirm', token, owner, now), '');
    assert.deepEqual(await index('token', 'lookup', token), { code: '', aid: owner, tokenId: 'token02' });
  });

  await t.test('AuthRate は並行計数を失わず時間窓と相異なる端末を数える', async () => {
    const counts = await Promise.all(Array.from({ length: 12 }, () => rate('parallel-counts', 'verify', now)));
    assert.deepEqual(counts.map(result => result.hour).sort((a, b) => a - b), Array.from({ length: 12 }, (_, number) => number + 1));
    assert.deepEqual(await rate('parallel-counts', 'verify', now + 3600), { code: '', hour: 1, day: 13, distinctDevices24h: 0 });
    assert.deepEqual(await rate('parallel-counts', 'verify', now + 86400), { code: '', hour: 1, day: 2, distinctDevices24h: 0 });
    const first = 'deviceHash:p0123456789abcdef01';
    const second = 'deviceHash:g0123456789abcdef02';
    assert.equal((await rate('device-counts', first, now)).distinctDevices24h, 1);
    assert.equal((await rate('device-counts', first, now + 1)).distinctDevices24h, 1);
    assert.equal((await rate('device-counts', second, now + 2)).distinctDevices24h, 2);
    assert.deepEqual(await rate('device-counts', 'verify', now + 3), { code: '', hour: 1, day: 1, distinctDevices24h: 2 });
    assert.equal((await rate('device-counts', 'deviceHash:invalid', now + 4)).code, 'auth:invalid');
  });

  await t.test('HTTP の Origin・設定不足・外部検証失敗を拒否し Accept-CH を全応答へ付ける', async () => {
    const anonymous = await fetch(origin + '/auth/session/');
    assert.equal(anonymous.status, 200);
    assert.equal(await anonymous.json(), null);
    assert.match(anonymous.headers.get('accept-ch'), /Sec-CH-UA-Model/);
    assert.equal(anonymous.headers.get('critical-ch'), null);
    assert.equal(anonymous.headers.get('cache-control'), 'private, no-store');
    assert.equal(anonymous.headers.get('vary'), 'Cookie');
    for (const suppliedOrigin of ['', 'https://other.invalid']) {
      const response = await post('/auth/verify/', { agreement: 'v1' }, { origin: suppliedOrigin });
      assert.equal(response.status, 403);
      assert.equal((await response.json()).code, 'auth:origin');
      assert.ok(response.headers.get('accept-ch'));
    }
    const wrongType = await fetch(origin + '/auth/verify/', { method: 'POST', headers: { origin, 'content-type': 'application/json' }, body: '{}' });
    assert.equal(wrongType.status, 400);
    for (const path of ['/__test/no-secret/auth/session/', '/__test/no-turnstile/auth/verify/', '/__test/no-secret/auth/', '/__test/no-secret/auth/account/']) {
      const response = path.includes('verify') ? await post(path, { agreement: 'v1' }) : await fetch(origin + path);
      assert.equal(response.status, 404, path);
      assert.match(response.headers.get('content-type'), /^text\/html/);
    }
    const signedOut = await fetch(origin + '/auth/account/', { redirect: 'manual' });
    assert.equal(signedOut.status, 302);
    assert.equal(signedOut.headers.get('location'), '/auth/?return=/auth/account/');
    const staleLogout = await post('/auth/logout/', {}, { cookie: `${cookieName}=1.stale` });
    assert.equal(staleLogout.status, 200);
    assert.match(staleLogout.headers.get('set-cookie'), /^__Host-ch3s_sid=; Max-Age=0;/);
    for (const token of ['rejected', 'wrong-host', 'unavailable', 'malformed']) {
      const response = await post('/auth/verify/', { agreement: 'v1', 'cf-turnstile-response': token });
      assert.equal(response.status, 400, token);
      assert.equal((await response.json()).code, 'auth:turnstile');
      assert.equal(response.headers.get('set-cookie'), null);
    }
    const noRate = await post('/__test/no-rate/auth/verify/', { agreement: 'v1', 'cf-turnstile-response': 'accepted' }, { 'CF-Connecting-IP': '192.0.2.34' });
    assert.equal(noRate.status, 503);
    assert.equal((await noRate.json()).code, 'auth:unavailable');
    assert.equal(noRate.headers.get('set-cookie'), null);
    const redirect = await fetch(origin + '/auth', { redirect: 'manual' });
    assert.equal(redirect.status, 301);
    assert.ok(redirect.headers.get('accept-ch'));
    const unchanged = await fetch(origin + '/auth-test/subject.txt', { headers: { 'if-modified-since': new Date(Date.now() + 86400000).toUTCString() } });
    assert.equal(unchanged.status, 304);
    assert.ok(unchanged.headers.get('accept-ch'));
  });

  await t.test('verify が実アカウントと署名 Cookie を作り再認証・キー発行・logout を通す', async () => {
    const ua = 'Mozilla/5.0 Linux Android Mobile';
    const fingerprint = { os: ua, cores: 8, ram: 8, screen: '393x852', dpr: 3, colorDepth: 24, webgl: 'Adreno', fonts: '001000000000000', touch: true, arch: 'Linux armv8l' };
    const headers = { 'user-agent': ua, 'CF-Connecting-IP': '192.0.2.35', 'sec-ch-ua-platform': '"Android"', 'sec-ch-ua-mobile': '?1', 'sec-ch-ua-model': '"Pixel 9"' };
    const form = { agreement: 'v1', 'cf-turnstile-response': 'accepted', device: JSON.stringify(fingerprint), return: '//other.invalid' };
    const response = await post('/auth/verify/', form, headers);
    const body = await response.json();
    assert.equal(response.status, 200, JSON.stringify(body));
    assert.match(body.uid, /^[a-z0-9]{8}$/);
    assert.equal(body.lv, 1);
    assert.equal(body.returnPath, '/');
    assert.deepEqual(Object.keys(body).sort(), ['lv', 'returnPath', 'uid']);
    const setCookie = response.headers.get('set-cookie');
    assert.ok(setCookie);
    assert.match(setCookie, /^__Host-ch3s_sid=1\./);
    for (const attribute of ['Path=/', 'Secure', 'HttpOnly', 'SameSite=Lax']) assert.ok(setCookie.includes(attribute));
    const cookie = setCookie.split(';')[0];
    const fields = cookie.slice(cookie.indexOf('=') + 1).split('.');
    assert.equal(fields.length, 9);
    assert.match(fields[1], /^[a-z0-9]{16}$/);
    assert.match(fields[6], /^p[0-9a-f]{18}$/);
    assert.equal(fields[8], sign(fields.slice(0, 8).join('.')).split('.').at(-1));
    assert.deepEqual(await session(cookie), { uid: body.uid, lv: 1 });
    const repeated = await post('/auth/verify/', { ...form, return: '/auth/account/' }, { ...headers, cookie });
    assert.equal(repeated.status, 200);
    assert.equal((await repeated.json()).uid, body.uid);
    assert.equal(repeated.headers.get('set-cookie').split(';')[0].split('.')[1], fields[1]);
    const panel = await account(fields[1], 'inspect');
    assert.equal(panel.account.uid, body.uid);
    assert.equal(panel.account.generation, 0);
    const accountPage = await fetch(origin + '/auth/account/', { headers: { cookie }, redirect: 'manual' });
    assert.equal(accountPage.status, 200);
    const accountHtml = await accountPage.text();
    for (const text of [`@${body.uid}`, 'かんたん登録（クライアントID）', '準個体（p）', '登録した端末1台', 'ログアウトするとこの端末から戻せません']) assert.ok(accountHtml.includes(text), text);
    assert.equal(accountPage.headers.get('cache-control'), 'private, no-store');

    for (const invalid of [
      `${cookieName}=${fields.map((value, index) => index === 2 ? 'forged01' : value).join('.')}`,
      signedCookie(fields.slice(0, 8).map((value, index) => index === 3 ? '1' : value)),
      signedCookie(fields.slice(0, 8).map((value, index) => index === 4 ? String(now - 1) : value)),
      `${cookie}; ${cookie}`,
      cookie.replace(/.$/, fields[8].endsWith('A') ? 'B' : 'A'),
    ]) assert.equal(await session(invalid), null);

    const issued = await post('/auth/token/issue/', {}, { cookie });
    assert.equal(issued.status, 200);
    const token = await issued.json();
    assert.match(token.token, /^!#[a-z2-7]{20}$/);
    const tokenHash = createHmac('sha256', secret).update('token:' + token.token.slice(2)).digest('hex');
    const entry = await index('token', 'lookup', tokenHash);
    assert.equal(entry.aid, fields[1]);
    assert.match(entry.tokenId, /^[a-z2-7]{16}$/);
    assert.equal((await post('/auth/token/revoke/', { id: entry.tokenId }, { cookie })).status, 200);
    assert.equal((await account(fields[1], 'useToken', entry.tokenId, 'a'.repeat(64), 'Local', now)).code, 'auth:token:revoked');
    assert.equal((await index('token', 'lookup', tokenHash)).code, 'auth:index:missing');

    const logout = await post('/auth/logout/', {}, { cookie });
    assert.equal(logout.status, 200);
    assert.match(logout.headers.get('set-cookie'), /Max-Age=0; Path=\/; Secure; HttpOnly; SameSite=Lax/);
    assert.equal((await account(fields[1], 'inspect')).account.generation, 1);
    assert.equal(await session(cookie), null);
    assert.equal((await account(fields[1], 'describeForPosting', fields[1], 0, now)).code, 'auth:session:invalid');
    const calls = await (await fetch(origin + '/__test/external-calls')).json();
    assert.equal(calls.filter(call => call.token === 'accepted').length, 3);
    assert.ok(calls.every(call => call.method === 'POST'));
  });
});
