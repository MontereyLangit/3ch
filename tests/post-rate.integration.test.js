import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { unstable_dev } from 'wrangler';

// 前提: `npm run v6:build` で dist/index.js が生成済みであること（無ければスキップ）。
const built = existsSync(new URL('../dist/index.js', import.meta.url));

test('Wrangler: BBS_FR_LEVEL の無い板は規制モードで、Samba24 が返信を拒否する', {
  timeout: 120000,
  skip: built ? false : 'dist/index.js がありません（npm run v6:build を先に実行）'
}, async t => {
  const worker = await unstable_dev('dist/index.js', {
    config: 'wrangler.jsonc',
    local: true,
    persist: false,
    port: 0,
    vars: {ADMIN_PASSWORD: 'local-integration-password'},
    logLevel: 'error',
    experimental: {disableExperimentalWarning: true, disableDevRegistry: true, watch: false}
  });
  t.after(() => worker.stop());
  const origin = `http://${worker.address}:${worker.port}`;
  const authorization = 'Basic ' + Buffer.from('admin:local-integration-password').toString('base64');
  const post = (key, message, subject = '') => fetch(origin + '/test/bbs.cgi', {
    method: 'POST',
    headers: {'user-agent': 'IntegrationBrowser/1.0', referer: 'http://127.0.0.1:8787/', 'CF-Connecting-IP': '192.0.2.10'},
    body: new URLSearchParams({bbs: 'rentou', key, MESSAGE: message, FROM: '', mail: '', subject, time: '1', submit: '書き込む'})
  });
  const saved = await fetch(origin + '/admin/board?bbs=rentou', {
    method: 'POST',
    headers: {authorization, origin},
    body: new URLSearchParams({action: 'save', head: '', settings: 'BBS_TITLE=rentou\nBBS_SAMBA24=60'}),
    redirect: 'manual'
  });
  assert.equal(saved.status, 303);

  const created = await post('', 'first message', 'rate title');
  assert.match(await created.text(), /書きこみました/);
  const key = created.headers.get('x-threadkey');
  assert.match(key, /^\d{10}$/);

  const rejected = await post(key, 'second message');
  assert.equal(rejected.status, 200);
  assert.equal(rejected.headers.get('x-resnum'), null);
  const html = await rejected.text();
  assert.match(html, /<!-- 2ch_X:error -->/);
  assert.match(html, /Samba24 で規制中です。\(あと \d+ 秒\)/);
  const dat = new TextDecoder('shift_jis').decode(await (await fetch(origin + `/rentou/dat/${key}.dat`)).arrayBuffer());
  assert.equal(dat.split('\n').filter(Boolean).length, 1, '拒否した返信は dat に残らない');
});
