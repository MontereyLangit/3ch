import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Board, Thread } from '../../bbs/target/scala-3.3.8/bbs-fastopt/server.js';

async function legacy(name) {
  const code = (await readFile(new URL(`../../src/durable_objects/${name.toLowerCase()}.js`, import.meta.url), 'utf8')).replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
  return (await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64')))[name];
}
const LegacyBoard = await legacy('Board'), LegacyThread = await legacy('Thread');
function storage() {
  const data = new Map(); let tail = Promise.resolve();
  return { data, storage: {
    get: async key => Array.isArray(key) ? new Map(key.filter(k => data.has(k)).map(k => [k, structuredClone(data.get(k))])) : structuredClone(data.get(key)),
    put: async (key, value) => { for (const [k, v] of typeof key === 'string' ? [[key, value]] : Object.entries(key)) data.set(k, structuredClone(v)); },
    delete: async key => data.delete(key),
  }, blockConcurrencyWhile(callback) { const result = tail.then(callback); tail = result.catch(() => {}); return result; } };
}
function world(B, T) {
  const r2 = new Map(), boards = new Map(), threads = new Map();
  const env = { ch3www: { get: async k => r2.has(k) ? { text: async () => r2.get(k) } : null } };
  for (const [name, Type, objects] of [['BOARD_DO', B, boards], ['THREAD_DO', T, threads]]) env[name] = {
    idFromName: k => k, get: k => { if (!objects.has(k)) objects.set(k, new Type(storage(), env)); return objects.get(k); },
  };
  return { env, r2, board: k => env.BOARD_DO.get(k), thread: k => env.THREAD_DO.get(k),
    snapshot: () => [boards, threads].map(m => [...m].map(([k, obj]) => [k, [...obj.state.data].sort()]).sort()),
  };
}
async function paired(run) {
  const oldNow = Date.now; Date.now = () => 1790046245123;
  try {
    const worlds = [world(LegacyBoard, LegacyThread), world(Board, Thread)];
    async function step(fn) {
      const results = [];
      for (const w of worlds) {
        try { results.push({ value: await fn(w), storage: w.snapshot() }); }
        catch (e) { results.push({ error: e.message, storage: w.snapshot() }); }
      }
      assert.deepEqual(results[1], results[0]);
      return results[0];
    }
    await run(step);
  } finally { Date.now = oldNow; }
}
test('Board: persisted ordering, nullish titles, negative/fractional/NaN limits, UTF-8 limit', () => paired(async step => {
  await step(w => w.board('a').updateThread({ key: '1', title: 'a', resNum: 1 }, false));
  await step(w => w.board('a').updateThread({ key: '2', title: 'b', resNum: 1 }, false));
  await step(w => w.board('a').updateThread({ key: '1', title: null, resNum: 3 }, true));
  await step(w => w.board('a').updateThread({ key: '2', resNum: 5 }));
  for (const max of [NaN, 1.8, -1, -Infinity, 0, null]) await step(w => w.board('a').updateThread({ key: '3', title: '', resNum: 1 }, 0, max));
  await step(w => w.board('a').updateThread({ key: '4', title: '漢'.repeat(175000), resNum: 1 }, false));
  await step(w => w.board('a').removeThread('4'));
  await step(w => w.board('a').removeThread('4'));
}));
test('Thread: legacy BOM/whitespace, gaps, settings, malformed logs and old DO import', () => paired(async step => {
  const cases = [
    '題名\n\uFEFFBBS_NONAME_NAME=\u3000名無し\u3000<>BBS_HEISA=\n\u30001<><>識別<>1700000000123<>ID:x<>a<br>b<br>c<>log\u3000\n3<>name<><>1700000001<>ID:y<>body<>log\n',
    'broken\nsettings', '題名\n\n1<>name<><>not-a-date<>ID:x<>body<>log\n',
  ];
  for (let i = 0; i < cases.length; i++) {
    const key = String(1700000000 + i);
    await step(w => { w.r2.set(`a/thread/1700/${key}.cgi`, cases[i]); return w.thread('a/' + key).getDat('a', key); });
    await step(w => w.thread('a/' + key).inspect('a', key));
  }
  await step(w => w.thread('1762000000').state.storage.put({ dat: 'name<><>date<>body<>title\n', log: 'private', settings: 'BBS_HEISA=checked' }));
  await step(w => w.thread('a/1762000000').getDat('a', '1762000000'));
}));
test('Thread: exclusive validation at 999, concurrent writes and stored edited flags', () => paired(async step => {
  await step(w => w.thread('t').setDat('n<><>date<>body<>title\n'.repeat(997)));
  await step(w => Promise.allSettled([w.thread('t').addRes('new', '1<>a'), w.thread('t').addRes('new2', '1<>b')]).then(a => a.map(r => r.status === 'fulfilled' ? r.value : r.reason.message)));
  await step(w => w.thread('t').addRes('reject', '1<>c'));
  await step(w => w.thread('t').moderate('editRes', { bbs: 'a', key: 't', number: '2', name: '名前', mail: 'sage', message: '&<"\r\nnew' }));
  await step(w => w.thread('t').getPublicData('a', 't'));
  await step(w => w.thread('t').deleteRes(1));
}));
