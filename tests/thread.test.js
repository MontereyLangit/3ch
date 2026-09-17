import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Only replace the runtime superclass; execute the production DO methods unchanged.
async function loadClass(file, name) {
  const code = (await readFile(new URL(file, import.meta.url), 'utf8')).replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
  return (await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64')))[name];
}
const Thread = await loadClass('../src/durable_objects/thread.js', 'Thread');
const Board = await loadClass('../src/durable_objects/board.js', 'Board');
function state() {
  const data = new Map(); let tail = Promise.resolve();
  return { data, storage: {
    get: async key => Array.isArray(key) ? new Map(key.filter(k => data.has(k)).map(k => [k, structuredClone(data.get(k))])) : structuredClone(data.get(key)),
    put: async (key, value) => { for (const [k, v] of typeof key === 'string' ? [[key, value]] : Object.entries(key)) data.set(k, structuredClone(v)); },
    delete: async key => data.delete(key),
  }, blockConcurrencyWhile(callback) { const result = tail.then(callback); tail = result.catch(() => {}); return result; } };
}
function setup() {
  const objects = new Map(), boards = new Map(), r2 = new Map();
  const env = { ch3www: { get: async key => r2.has(key) ? { text: async () => r2.get(key) } : null } };
  for (const [binding, Class, instances] of [['THREAD_DO', Thread, objects], ['BOARD_DO', Board, boards]]) {
    env[binding] = { idFromName: key => key, get: key => { if (!instances.has(key)) instances.set(key, new Class(state(), env)); return instances.get(key); } };
  }
  return { env, r2, thread: key => env.THREAD_DO.get(key), board: key => env.BOARD_DO.get(key) };
}
const dat = 'name<><>date<>message<>title\n';
const input = { bbs: 'source', key: '1800000000', targetBoard: 'target', targetKey: '1800000001' };
test('削除は本文・投稿者ログを保持し、旧R2データを復活させない', async () => {
  const { thread, r2, board } = setup();
  const key = '1700000000';
  r2.set(`source/thread/1700/${key}.cgi`, 'legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n');
  const source = thread(`source/${key}`);
  const before = await source.inspect('source', key);
  assert.match(before.legacyLog, /IP:192.0.2.1/);
  assert.match(before.settings, /BBS_HEISA=checked/);
  await source.moderate('delete', { bbs: 'source', key });
  assert.equal(await source.getDat('source', key), '');
  assert.equal((await source.inspect('source', key)).dat, before.dat);
  assert.equal((await source.inspect('source', key)).legacyLog, before.legacyLog);
  assert.deepEqual(await board('source').getRemoved(), [key]);
});
test('移動の途中失敗は同じ移動先への再実行で復旧し、ログ・停止状態を保持する', async () => {
  const { thread, board } = setup();
  const source = thread('source/1800000000'), destination = thread('target/1800000001');
  await source.state.storage.put({ dat, log: '1<>IP:192.0.2.1\n', legacyLog: 'old private log', settings: 'BBS_HEISA=checked', stopped: true });
  const originalUpdate = board('target').updateThread.bind(board('target'));
  board('target').updateThread = async () => { throw new Error('temporary failure'); };
  await assert.rejects(source.moderate('move', input), /temporary failure/);
  assert.equal(await source.getDat(), '');
  assert.equal(await destination.getDat(), '');
  await assert.rejects(source.addRes('new', '2<>log'), /移動/);
  board('target').updateThread = originalUpdate;
  await source.moderate('move', input);
  assert.equal(await destination.getDat(), dat);
  assert.equal(await destination.getLog(), '1<>IP:192.0.2.1\n');
  assert.equal((await destination.inspect()).legacyLog, 'old private log');
  await assert.rejects(destination.addRes('new', '2<>log'), /停止/);
  assert.match(await board('target').getSubject(), /1800000001.dat<>title \(1\)/);
  assert.equal((await source.inspect()).dat, dat);
});
test('移動先保存後に応答を失っても二重コピーせず復旧できる', async () => {
  const { thread } = setup();
  const source = thread('source/1800000000'), destination = thread('target/1800000001');
  await source.setDat(dat);
  const receive = destination.receiveMove.bind(destination);
  destination.receiveMove = async (...args) => { await receive(...args); throw new Error('lost response'); };
  await assert.rejects(source.moderate('move', input), /lost response/);
  await assert.rejects(source.addRes('new', '2<>log'), /移動/);
  destination.receiveMove = receive;
  await source.moderate('move', input);
  assert.equal(await destination.getDat(), dat);
});
test('同時投稿はレスとログの連番を保持し、停止後の投稿を拒否する', async () => {
  const { thread } = setup();
  const source = thread('source/1800000000');
  await source.state.storage.put({ dat, log: '1<>first\n' });
  const counts = await Promise.all([source.addRes('second<><><><>', '2<>second'), source.addRes('third<><><><>', '2<>third')]);
  assert.deepEqual(counts, [2, 3]);
  assert.equal((await source.getDat()).trimEnd().split('\n').length, 3);
  assert.equal(await source.getLog(), '1<>first\n2<>second\n3<>third\n');
  await source.moderate('stop', input);
  await assert.rejects(source.addRes('fourth', '4<>fourth'), /停止/);
});

test('既存スレ同士の相互移動はロック前に拒否し、元データを保持する', async () => {
  const { thread } = setup();
  const source = thread('source/1800000000'), destination = thread('target/1800000001');
  await source.setDat(dat); await destination.setDat(dat);
  const results = await Promise.allSettled([
    source.moderate('move', input),
    destination.moderate('move', { bbs: 'target', key: '1800000001', targetBoard: 'source', targetKey: '1800000000' }),
  ]);
  assert.ok(results.every(result => result.status === 'rejected' && /使用済み/.test(result.reason.message)));
  assert.equal(await source.getDat(), dat); assert.equal(await destination.getDat(), dat);
});

test('旧スレへの直接返信は移行し、旧設定の停止も強制する', async () => {
  const { thread, r2 } = setup();
  r2.set('source/thread/1700/1700000000.cgi', 'legacy title\n\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n');
  const source = thread('source/1700000000');
  assert.equal(await source.addRes('second<><><><>', '2<>log', { bbs: 'source', key: '1700000000', title: 'legacy title', create: false }), 2);
  r2.set('source/thread/1700/1700000001.cgi', 'legacy title\nBBS_HEISA=checked\n1<>name<><>1700000000<>ID:one<>text<>IP:192.0.2.1\n');
  await assert.rejects(thread('source/1700000001').addRes('second<><><><>', '2<>log', { bbs: 'source', key: '1700000001', create: false }), /停止/);
});
