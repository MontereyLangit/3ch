import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { unstable_dev } from 'wrangler';

// Both real workerd instances run with the same fixture clock and random source.
// DO storage remains real, separate and ephemeral; no production configuration is read.
const fixedTime = Date.UTC(2026, 8, 22, 3, 4, 5, 123);
const root = resolve('.test-state/http-differential');
const password = 'migration-test-only';
const auth = 'Basic ' + Buffer.from('admin:' + password).toString('base64');

test('v5 / Scala.js + HonoX: real HTTP status, headers and response bytes', { timeout: 180000 }, async t => {
  await mkdir(root, { recursive: true });
  const workers = [];
  t.after(async () => { await Promise.all(workers.map(w => w.stop())); });
  for (const [name, relativeEntry] of [['v5', '../../src/main.js'], ['v6', '../../dist/index.js']]) {
    const fixture = `import worker from ${JSON.stringify(relativeEntry)};
import { sjis } from ${JSON.stringify(name === 'v5' ? '../../src/common.js' : relativeEntry)};
export { Board, Thread } from ${JSON.stringify(relativeEntry)};
const RealDate = Date;
globalThis.Date = class extends RealDate {
  constructor(...args) { super(...(args.length ? args : [${fixedTime}])); }
  static now() { return ${fixedTime}; }
};
Math.random = () => 0.5;
export default { fetch: (request, env, ctx) => {
  if (new URL(request.url).pathname === '/__fixture/sjis-table') {
    const codec = new sjis();
    const decode = bytes => { try { return { value: codec.decode(bytes) }; } catch (e) { return { error: e.name, message: e.message }; } };
    return Response.json({
      d: codec.d, e: codec.e,
      encodeBmp: Array.from({ length: 65536 }, (_, n) => codec.encode(String.fromCharCode(n))),
      decodeSingle: Array.from({ length: 256 }, (_, n) => decode([n])),
      decodePairs: Array.from({ length: 65536 }, (_, n) => decode([n >> 8, n & 255])),
      supplementary: ['😀', '\\uD800A', 'A\\uDFFF', '\\uDBFF\\uDFFF'].map(s => codec.encode(s)),
    });
  }
  return worker.fetch(request, env, ctx);
} };
`;
    const file = resolve(root, name + '.mjs');
    const config = resolve(root, name + '.json');
    await writeFile(file, fixture);
    await writeFile(config, JSON.stringify({
      name: 'ch3s-diff-' + name, main: file, compatibility_date: '2025-09-19', compatibility_flags: ['nodejs_compat'],
      durable_objects: { bindings: [{ name: 'THREAD_DO', class_name: 'Thread' }, { name: 'BOARD_DO', class_name: 'Board' }] },
      migrations: [{ tag: 'v1', new_classes: ['Thread', 'Board'] }],
      r2_buckets: [{ binding: 'ch3www', bucket_name: 'diff-' + name }],
      kv_namespaces: [{ binding: 'CAP', id: '00000000000000000000000000000001' }],
      vars: { salt: 'public-migration-test-salt', ADMIN_PASSWORD: password },
    }));
    workers.push(await unstable_dev(file, { config, local: true, persist: false, port: 0, logLevel: 'error', experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false } }));
  }
  let comparisons = 0;
  const origins = workers.map(w => `http://${w.address}:${w.port}`);
  // Check the Node-derived golden table against real workerd's TextDecoder.
  const codecs = [];
  for (const origin of origins) codecs.push(await (await fetch(origin + '/__fixture/sjis-table')).json());
  assert.deepEqual(codecs[1], codecs[0], 'real workerd: full tables, BMP encode, all single/pair decode inputs');
  const table = codecs[0].d;
  const golden = JSON.parse(await readFile(new URL('./sjis-table.json', import.meta.url)));
  const tableDiffs = [];
  for (const x of golden.singleByte) if (table[x.byte] !== String.fromCodePoint(x.char)) tableDiffs.push({ bytes: [x.byte], node: x.char, workerd: table[x.byte]?.codePointAt(0) ?? null });
  for (const x of golden.doubleByte) if (table[x.lead]?.[x.second] !== String.fromCodePoint(x.char)) tableDiffs.push({ bytes: [x.lead, x.second], node: x.char, workerd: table[x.lead]?.[x.second]?.codePointAt(0) ?? null });
  await writeFile(resolve(root, 'sjis-runtime-differences.json'), JSON.stringify(tableDiffs, null, 2));
  const expectedDiffs = JSON.parse(await readFile(new URL('./sjis-runtime-differences.json', import.meta.url)));
  assert.deepEqual(tableDiffs, expectedDiffs, 'the enumerated Node/workerd differences must not drift');
  const key = String(Math.floor(fixedTime / 1000));
  async function pair(path, { form, headers = {}, admin = false, method = form ? 'POST' : 'GET', expected, body } = {}) {
    const snapshots = [];
    for (const origin of origins) {
      const response = await fetch(origin + path, {
        method, redirect: 'manual',
        headers: { ...(admin ? { authorization: auth, origin } : {}), ...headers },
        body: form ? new URLSearchParams(form) : body,
      });
      const bytes = Buffer.from(await response.arrayBuffer());
      const h = Object.fromEntries(response.headers);
      // Date is supplied by HTTP transport rather than the application clock.
      if (h.date) { assert.ok(Number.isFinite(Date.parse(h.date))); delete h.date; }
      if (h['content-length']) assert.equal(Number(h['content-length']), bytes.length, path);
      snapshots.push({ status: response.status, headers: h, bytes });
    }
    assert.deepEqual(snapshots[1], snapshots[0], `${method} ${path}`);
    if (expected !== undefined) assert.equal(snapshots[0].status, expected, path);
    comparisons++;
    return snapshots[0];
  }
  const save = (path, form, expected = 303) => pair(path, { form, admin: true, expected });
  const post = (bbs, thread = '', message = '本文≒①\nsecond\nthird', subject = 'タイトル') => pair('/test/bbs.cgi', {
    form: { bbs, key: thread, MESSAGE: message, FROM: '名前#日本語トリップ', mail: 'sage', subject: thread ? '' : subject, time: '1', submit: '書き込む' },
    headers: { 'user-agent': 'MigrationBrowser/1.0', referer: 'http://127.0.0.1:8787/', 'CF-Connecting-IP': '192.0.2.1' }, expected: 200,
  });
  for (const path of ['/missing', '/auth.js', '/test/sakubbs.cgi/25', '/test/bbs.cgi', '/bbsmenu.html', '/headline/subject.txt', '/alpha/subject.txt', '/alpha/head.txt', '/alpha/SETTING.TXT', '/alpha/', '/alpha/index.html', '/alpha/kako/subject.txt', '/alpha/kako/1789/subject.txt']) await pair(path);
  // Prefix matching and non-GET dispatch are intentional legacy behavior.
  await pair('/alpha/SETTING.TXT/extra', { method: 'PUT', expected: 200 });
  await pair('/alpha/subject.txt.trailing', { method: 'OPTIONS', expected: 200 });
  await pair('/admin/', { expected: 401 });
  await pair('/admin/', { admin: true, expected: 200 });
  await pair('/admin/menu', { admin: true, method: 'DELETE', expected: 405 });
  await pair('/admin/menu', { admin: true, form: { html: 'no' }, headers: { origin: 'https://bad.invalid' }, expected: 403 });
  await pair('/admin/board?bbs=../bad', { admin: true, expected: 400 });
  for (const bbs of ['alpha', 'beta']) {
    await save('/admin/board?bbs=' + bbs, { action: 'save', head: '<b>ルール≒①</b>', settings: `BBS_TITLE=${bbs}板\nBBS_NONAME_NAME=名無し\nBBS_MAX_MENU_THREAD=700` });
    await pair('/admin/board?bbs=' + bbs, { admin: true });
    await pair('/' + bbs + '/');
  }
  await save('/admin/access', { rules: '[<><><>deny' }, 400);
  await save('/admin/access', { rules: '^alpha$<><><>deny' });
  await post('alpha');
  await save('/admin/access', { rules: '' });
  await save('/admin/ngwords', { rules: '<><>禁止<>deny' });
  await post('alpha', '', '禁止');
  await save('/admin/ngwords', { rules: '' });
  const created = await post('alpha');
  assert.equal(created.headers['x-threadkey'], key);
  await post('alpha', key);
  await post('beta');
  await pair('/test/subbbs.cgi/extra', {
    method: 'POST',
    body: `bbs=alpha&key=${key}&FROM=name&mail=&MESSAGE=%82%A0%87%90&time=1&submit=%8F%91%82%AB%8D%9E%82%DE`,
    headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'Monazilla/1.00 Migration', referer: 'http://127.0.0.1:8787/', 'CF-Connecting-IP': '192.0.2.2' }, expected: 200,
  });
  for (const path of ['/alpha/subject.txt', `/alpha/dat/${key}.dat`, '/headline/subject.txt', '/bbsmenu.html', '/alpha/kako/subject.txt', `/alpha/kako/${key.slice(0, 4)}/subject.txt`, '/admin/threads?bbs=alpha', `/admin/thread?bbs=alpha&key=${key}`, '/admin/access', '/admin/ngwords', '/admin/menu']) await pair(path, { admin: path.startsWith('/admin/') });
  for (const range of ['bytes=0-5', 'bytes=-5', 'bytes=999999-', 'bad', 'bytes=3-2', 'bytes=0-', 'bytes=0-999999', 'bytes=9007199254740993-']) await pair(`/alpha/dat/${key}.dat`, { headers: { range } });
  for (const since of [new Date(fixedTime).toUTCString(), new Date(fixedTime + 1000).toUTCString(), 'invalid']) await pair(`/alpha/dat/${key}.dat`, { headers: { 'if-modified-since': since } });
  const adminThread = `/admin/thread?bbs=alpha&key=${key}`;
  await save(adminThread, { action: 'stop' });
  await post('alpha', key);
  await save(adminThread, { action: 'resume' });
  await post('alpha', key, '再開後');
  await save(adminThread, { action: 'editRes', number: '2', name: '<script>', mail: 'sage', message: '編集\n本文 & < > "' });
  await pair(adminThread, { admin: true });
  await pair(`/alpha/dat/${key}.dat`, { headers: { range: 'bytes=5-', 'if-modified-since': new Date(fixedTime + 1000).toUTCString() }, expected: 200 });
  await save(adminThread, { action: 'deleteRes', number: '999' }, 400);
  await save(adminThread, { action: 'deleteRes', number: '1' });
  await save(adminThread, { action: 'move', targetBoard: 'beta', targetKey: '1800000001' });
  for (const path of [`/alpha/dat/${key}.dat`, '/alpha/subject.txt', '/beta/subject.txt', '/beta/dat/1800000001.dat', `/alpha/kako/${key.slice(0, 4)}/subject.txt`, '/headline/subject.txt', adminThread, '/admin/thread?bbs=beta&key=1800000001']) await pair(path, { admin: path.startsWith('/admin/') });
  await save('/admin/thread?bbs=beta&key=1800000001', { action: 'delete' });
  await pair('/beta/dat/1800000001.dat', { expected: 404 });
  await save('/admin/menu', { html: '<html>独自メニュー≒①</html>' });
  await pair('/bbsmenu.html');
  await writeFile(resolve(root, 'result.json'), JSON.stringify({ comparisons, clock: fixedTime, byteExact: true }) + '\n');
  t.diagnostic(`${comparisons} paired HTTP requests matched byte-for-byte`);
});

test('checked-in v6 config: exported DO binding and RPC', { timeout: 60000 }, async t => {
  const worker = await unstable_dev('dist/index.js', {
    config: 'wrangler.v6.jsonc', local: true, persist: false, port: 0, logLevel: 'error',
    experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false },
  });
  t.after(() => worker.stop());
  const response = await fetch(`http://${worker.address}:${worker.port}/_v6/health`);
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'ch3s v6s ok (do:pong)');
});
