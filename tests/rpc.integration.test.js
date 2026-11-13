import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { unstable_dev } from 'wrangler';

// 前提: `npm run v6:build` で dist/index.js が生成済みであること（無ければスキップ）。
// 生成される RPC スタブ（docs/durable-object-stubs.md）が省略可能として宣言する引数を実際に省略し、
// DO 本体の既定値が実 workerd 上の RPC でも適用されることを確かめる。
const built = existsSync(new URL('../dist/index.js', import.meta.url));

test('Wrangler: DO RPC の省略引数に本体の既定値が適用される', { timeout: 120000, skip: built ? false : 'dist/index.js がありません（npm run v6:build を先に実行）' }, async t => {
  const worker = await unstable_dev(fileURLToPath(new URL('./support/rpc-worker.mjs', import.meta.url)), { config: fileURLToPath(new URL('./support/wrangler.rpc.jsonc', import.meta.url)), local: true, persist: false, port: 0, logLevel: 'error', experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false } });
  t.after(() => worker.stop());
  const origin = `http://${worker.address}:${worker.port}`;
  const rpc = async (binding, name, method, ...args) => {
    const response = await fetch(origin, { method: 'POST', body: JSON.stringify({ binding, name, method, args }) });
    const body = await response.json();
    assert.equal(response.status, 200, `${binding}.${method}: ${body.error}`);
    return body.result;
  };
  const thread = (method, ...args) => rpc('THREAD_DO', 'rpc-board/1800000001', method, ...args);
  const board = (method, ...args) => rpc('BOARD_DO', 'rpc-board', method, ...args);

  // getDat(bbs = "", key = "0"): 省略時は旧ログ取り込み（bbs 指定時のみ）に入らず、空の dat を返す。
  assert.equal(await thread('getDat'), '');
  assert.equal(await thread('getDat', 'rbbs'), '');
  await thread('setDat', '1<>name<><>message<>title\n');
  assert.equal(await thread('getDat'), '1<>name<><>message<>title\n');
  assert.equal(await thread('getDat', 'rbbs', '1800000001'), '1<>name<><>message<>title\n');

  // addRes(datLine, logLine, posting = undefined): posting 省略は板更新なしのレス追加になる。連投規制も判定しない。
  assert.deepEqual(await thread('addRes', '2<>name<><>second<>', '0<>log'), { count: 2, swallowed: false, observed: [] });
  assert.match(await thread('getDat'), /\n2<>name<><>second<>\n$/);
  assert.equal(await thread('getLog'), '2<>log\n');

  // inspect(bbs = undefined, key = undefined): 省略時は R2 の旧ログを見に行かずに現在の状態を返す。
  const inspected = await thread('inspect');
  assert.equal(inspected.dat, await thread('getDat'));
  assert.equal(inspected.legacyLog, undefined);

  // updateThread(data, sage = undefined, maxThreads = undefined): sage 省略は age 扱いで先頭へ移動し、
  // maxThreads 省略は既定の上限（切り捨てなし）、-1 指定は末尾 1 件を落とす。
  await board('updateThread', { key: '1800000001', title: 'first', resNum: 1 });
  await board('updateThread', { key: '1800000002', title: 'second', resNum: 1 });
  assert.equal(await board('getSubject'), '1800000002.dat<>second (1)\n1800000001.dat<>first (1)\n');
  await board('updateThread', { key: '1800000001', resNum: 2 }, true);
  assert.equal(await board('getSubject'), '1800000002.dat<>second (1)\n1800000001.dat<>first (2)\n');
  await board('updateThread', { key: '1800000001', resNum: 3 });
  assert.equal(await board('getSubject'), '1800000001.dat<>first (3)\n1800000002.dat<>second (1)\n');
  await board('updateThread', { key: '1800000003', title: 'third', resNum: 1 }, false, -1);
  assert.equal(await board('getSubject'), '1800000003.dat<>third (1)\n1800000001.dat<>first (3)\n');
  assert.deepEqual(await board('getRemoved'), []);
});
