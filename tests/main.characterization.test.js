import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { sjis } from '../src/common.js';

/*
 * main.js の fetch ハンドラをそのまま Node 上で動かすためのブリッジ。
 * main.js 自体は "cloudflare:workers" を import していないが、末尾で
 * re-export している Board/Thread (durable_objects/*.js) がそれを import
 * しているため、まずその2行と re-export 行を取り除く（fetch のルーティング
 * ロジックはこれらのクラスを直接参照していないので、除去しても挙動は変わらない）。
 * また、相対 import (./admin.js 等) は data: URL からは解決できないため、
 * 実ファイルの絶対 file:// URL に置き換えたコピーを一時ファイルに書き出して
 * import する。src/ 配下のファイルは一切書き換えない。
 */
async function loadMain() {
  const srcDir = new URL('../src/', import.meta.url);
  let code = await readFile(new URL('../src/main.js', import.meta.url), 'utf8');
  code = code
    .replace(/import \{ Board \} from "\.\/durable_objects\/board\.js";\r?\n/, '')
    .replace(/import \{ Thread \} from "\.\/durable_objects\/thread\.js";\r?\n/, '')
    .replace(/\r?\nexport \{ Board \};\r?\nexport \{ Thread \};\r?\n?/, '\n')
    .replace('"./admin.js"', JSON.stringify(new URL('admin.js', srcDir).href))
    .replace('"./common.js"', JSON.stringify(new URL('common.js', srcDir).href))
    .replace('"./bbs.cgi.js"', JSON.stringify(new URL('bbs.cgi.js', srcDir).href))
    .replace('"./3chiko.js"', JSON.stringify(new URL('3chiko.js', srcDir).href));
  const dir = await mkdtemp(join(tmpdir(), 'ch3s-main-'));
  const file = join(dir, 'main.generated.mjs');
  await writeFile(file, code, 'utf8');
  return (await import(pathToFileURL(file).href)).default.fetch;
}

// Board/Thread DO はここでは thread.test.js と同じ手法（superclass だけ差し替え）で読む。
async function loadClass(file, name) {
  const code = (await readFile(new URL(file, import.meta.url), 'utf8')).replace('import { DurableObject } from "cloudflare:workers";', 'class DurableObject {}');
  return (await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64')))[name];
}
const Thread = await loadClass('../src/durable_objects/thread.js', 'Thread');
const Board = await loadClass('../src/durable_objects/board.js', 'Board');
const fetchHandler = await loadMain();

function state() {
  const data = new Map(); let tail = Promise.resolve();
  return { data, storage: {
    get: async key => Array.isArray(key) ? new Map(key.filter(k => data.has(k)).map(k => [k, structuredClone(data.get(k))])) : structuredClone(data.get(key)),
    put: async (key, value) => { for (const [k, v] of typeof key === 'string' ? [[key, value]] : Object.entries(key)) data.set(k, structuredClone(v)); },
    delete: async key => data.delete(key),
  }, blockConcurrencyWhile(callback) { const result = tail.then(callback); tail = result.catch(() => {}); return result; } };
}

// main.js は /bbsmenu.html と /{bbs}/kako/subject.txt で Workers の Cache API (`caches.default`) を
// 直接グローバル参照するので、テスト用にインメモリ実装をグローバルへ差し込む。
function makeCaches() {
  const store = new Map();
  return { default: {
    match: async req => store.get(typeof req === 'string' ? req : req.url),
    put: async (req, res) => { store.set(typeof req === 'string' ? req : req.url, res.clone()); },
  } };
}

// wrangler.jsonc の `assets` (binding: ASSETS) の簡易モック。実Wranglerと同じく
// public/配下のファイルをパス一致で返す(条件付きGET/Range等は本テストの対象外)。
function makeAssets() {
  return {
    fetch: async req => {
      const pathname = new URL(req.url).pathname;
      let body;
      try { body = await readFile(new URL('../public' + pathname, import.meta.url), 'utf8'); }
      catch { return new Response('Not Found', { status: 404 }); }
      return new Response(body, { headers: { 'content-type': 'text/html; charset=utf-8' } });
    },
  };
}

function setup() {
  const objects = new Map(), boards = new Map(), r2 = new Map();
  const env = { ch3www: { get: async key => r2.has(key) ? { text: async () => r2.get(key) } : null }, ASSETS: makeAssets() };
  for (const [binding, Class, instances] of [['THREAD_DO', Thread, objects], ['BOARD_DO', Board, boards]]) {
    env[binding] = { idFromName: key => key, get: key => { if (!instances.has(key)) instances.set(key, new Class(state(), env)); return instances.get(key); } };
  }
  globalThis.caches = makeCaches();
  const ctx = { waitUntil: p => p };
  return { env, ctx, r2, thread: key => env.THREAD_DO.get(key), board: key => env.BOARD_DO.get(key) };
}

const sjisBytes = text => Uint8Array.from(new sjis().encode(text));
const utf8Bytes = text => new TextEncoder().encode(text);
const bytesOf = async res => new Uint8Array(await res.arrayBuffer());

// 'name<><>2026/09/22(火) 12:00:00<>hello<>title\n' のShift_JISバイト列。
// sjisBytes()ヘルパー(=検証対象のsjis.encode自身)で期待値を作ると、エンコーダにバグが
// 入っても両辺が一緒にずれて常に一致してしまうため、ここでは実測して得た値をハードコードする。
const DAT_BOARD1_1800000000_SJIS_BYTES = new Uint8Array([
  110, 97, 109, 101, 60, 62, 60, 62, 50, 48, 50, 54, 47, 48, 57, 47, 50, 50,
  40, 137, 206, 41, 32, 49, 50, 58, 48, 48, 58, 48, 48, 60, 62, 104, 101, 108,
  108, 111, 60, 62, 116, 105, 116, 108, 101, 10,
]);

// subject.txtルートのlastModifiedは常に`new Date()`(現在時刻)なので、ミリ秒成分をテストから
// 制御するために一時的にグローバルDateを固定する。明示的な引数付きnew Date(x)は素通しする。
function withFixedTime(ms, fn) {
  const RealDate = Date;
  class FixedDate extends RealDate {
    constructor(...args) {
      if (args.length === 0) return new RealDate(ms);
      return new RealDate(...args);
    }
    static now() { return ms; }
  }
  globalThis.Date = FixedDate;
  return Promise.resolve().then(fn).finally(() => { globalThis.Date = RealDate; });
}

async function createThread(thread, bbs, key, { title = 'title', message = 'hello', date = '2026/09/22(火) 12:00:00' } = {}) {
  const t = thread(`${bbs}/${key}`);
  await t.addRes(`name<><>${date}<>${message}<>${title}`, '1<>log', { bbs, key, title, resNum: 1, sage: false, maxThreads: 700, create: true });
  return t;
}

// ---------- 1. /{bbs}/dat/{key}.dat ----------

test('dat: 通常取得は200・Shift_JISバイト列・cache-control/s-maxageヘッダ', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000000');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000000.dat'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.equal(res.headers.get('s-maxage'), '1');
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.deepEqual(await bytesOf(res), DAT_BOARD1_1800000000_SJIS_BYTES);
  assert.equal(res.headers.get('content-length'), String(DAT_BOARD1_1800000000_SJIS_BYTES.length));
});

test('dat: 削除済み(hidden)スレは404', async () => {
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000001');
  await t.moderate('delete', { bbs: 'board1', key: '1800000001' });
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000001.dat'), env, ctx);
  assert.equal(res.status, 404);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  assert.match(await res.text(), /\/board1\/dat\/1800000001\.dat/);
});

test('dat: 存在しないスレも404', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/dat/1999999999.dat'), env, ctx);
  assert.equal(res.status, 404);
});

test('dat: Range bytes=N- は206でContent-Range/Content-Length/Accept-Ranges付き、本文バイトも指定範囲通り', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000002');
  const total = DAT_BOARD1_1800000000_SJIS_BYTES.length;
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000002.dat', { headers: { range: 'bytes=5-' } }), env, ctx);
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), `bytes 5-${total - 1}/${total}`);
  assert.equal(res.headers.get('content-length'), String(total - 5));
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  // ヘッダだけでなく本文バイト自体も正しい範囲であることを検証する(ヘッダは合っていて
  // 本文の切り出しが間違っている移植ミスを検知するため)。
  assert.deepEqual(await bytesOf(res), DAT_BOARD1_1800000000_SJIS_BYTES.slice(5));
});

test('dat: Range bytes=N-M は範囲だけの206、本文バイトも指定範囲通り', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000003');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000003.dat', { headers: { range: 'bytes=2-5' } }), env, ctx);
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 2-5/46');
  assert.equal(res.headers.get('content-length'), '4');
  assert.deepEqual(await bytesOf(res), DAT_BOARD1_1800000000_SJIS_BYTES.slice(2, 6));
});

test('dat: 範囲外のRangeは416でContent-Range: bytes */total', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000004');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000004.dat', { headers: { range: 'bytes=99999-' } }), env, ctx);
  assert.equal(res.status, 416);
  assert.equal(res.headers.get('content-range'), 'bytes */46');
});

test('dat: 書式不正なRangeは400・本文"Bad Range Header"', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000006');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000006.dat', { headers: { range: 'notarange' } }), env, ctx);
  assert.equal(res.status, 400);
  assert.equal(await res.text(), 'Bad Range Header');
  // Response(text, {status}) の既定content-typeがそのまま出る（アプリ側では明示していない）。
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=UTF-8');
});

test('KB-23: dat: If-Modified-Sinceに同一値を送るとミリ秒切り捨てにより200になる（modifiedAtのミリ秒が0でない場合）', async () => {
  // modifiedAtはDate.now()由来で通常ミリ秒が0でないため、Last-Modified(秒精度)をそのまま
  // If-Modified-Sinceに送り返すと、内部比較用のDate(ミリ秒付き)の方が僅かに後ろになり
  // <=を満たさず200になる。元のテストはDate.now()の実際のミリ秒に依存しており、
  // 1000回に1回程度の確率でミリ秒がちょうど0になった場合に304を返してテストが落ちる
  // フレーキーさがあったため、ここではmodifiedAtのミリ秒を明示的に0でない値に固定する。
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000007');
  const fixedMs = Date.UTC(2024, 0, 1, 0, 0, 30, 500); // ミリ秒=500(≠0)に固定
  await t.state.storage.put('modifiedAt', fixedMs);

  const res1 = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat'), env, ctx);
  const lm = res1.headers.get('last-modified');
  assert.equal(lm, 'Mon, 01 Jan 2024 00:00:30 GMT'); // toUTCStringはミリ秒を切り捨てる

  const resSame = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat', { headers: { 'if-modified-since': lm } }), env, ctx);
  assert.equal(resSame.status, 200, 'ミリ秒切り捨てにより同一値でも304にならない（実挙動として保存）');

  const oneSecondLater = new Date(new Date(lm).getTime() + 1000).toUTCString();
  const res2 = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat', { headers: { 'if-modified-since': oneSecondLater } }), env, ctx);
  assert.equal(res2.status, 304);
  assert.equal(res2.headers.get('last-modified'), lm);
  assert.equal(res2.headers.get('content-type'), null);
});

test('KB-23: dat: modifiedAtのミリ秒がちょうど0の場合は、同一値を送り返すと切り捨てによる誤差が無く304になる', async () => {
  // KB-23の原因(秒精度の往復で誤差が生じる)が成立しない特殊ケース。modifiedAtのミリ秒が
  // ちょうど0であれば、toUTCString()の往復で情報が失われないため、同一値の送り返しでも
  // <=判定が成立し304になる。これも実際に起こりうる挙動として固定する。
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000107');
  const fixedMs = Date.UTC(2024, 0, 1, 0, 0, 30, 0); // ミリ秒=0に固定
  await t.state.storage.put('modifiedAt', fixedMs);

  const res1 = await fetchHandler(new Request('http://x/board1/dat/1800000107.dat'), env, ctx);
  const lm = res1.headers.get('last-modified');
  assert.equal(lm, 'Mon, 01 Jan 2024 00:00:30 GMT');

  const resSame = await fetchHandler(new Request('http://x/board1/dat/1800000107.dat', { headers: { 'if-modified-since': lm } }), env, ctx);
  assert.equal(resSame.status, 304, 'ミリ秒がちょうど0の場合は誤差が無いため同一値でも304になる');
});

test('dat: Last-Modifiedは保存されているmodifiedAt由来で、現在時刻ではない（コメント訂正: dat中の最終レスの日付ではない）', async () => {
  // 訂正: このテストの元のタイトル/コメントは「Last-Modifiedはdatのタイムスタンプ由来」としていたが、
  // main.js:189-206を実際に読むと、Thread DOに保存された`modifiedAt`(Date.now()起源)が最優先で
  // 使われ、datの最終行の日付文字列をパースする経路は`publicData.modifiedAt`が存在しない場合の
  // フォールバックに過ぎない。このテストが検証しているのはまさに前者(modifiedAt優先)であり、
  // 「dat中の最終レスのタイムスタンプ由来」という説明は実装と矛盾していたため訂正する。
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000008');
  const fixedPast = new Date('2020-01-01T00:00:00Z').getTime();
  await t.state.storage.put('modifiedAt', fixedPast); // addRes直後のmodifiedAtを過去日時に差し替える
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000008.dat'), env, ctx);
  assert.equal(res.headers.get('last-modified'), new Date(fixedPast).toUTCString());
});

test('dat: modifiedAtが未設定の場合のみ、datの最終行の日付文字列がLast-Modifiedのフォールバックとして使われる', async () => {
  // main.js:196-206: publicData.modifiedAtが無い(falsy)場合に限り、dat最終行の日付欄をパースして
  // lastModifiedに使う。この経路が実際に機能することを固定する。
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000108', { date: '2021/03/04(木) 09:08:07' });
  await t.state.storage.delete('modifiedAt');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000108.dat'), env, ctx);
  assert.equal(res.headers.get('last-modified'), new Date('2021/03/04(木) 09:08:07').toUTCString());
});

test('dat: editRes後はRange/If-Modified-Sinceを無視して全文200を返す', async () => {
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000009');
  await t.moderate('editRes', { bbs: 'board1', key: '1800000009', number: '1', name: 'x', mail: '', message: 'edited' });
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000009.dat', {
    headers: { range: 'bytes=0-3', 'if-modified-since': new Date(Date.now() + 86400000).toUTCString() },
  }), env, ctx);
  assert.equal(res.status, 200, '編集済みスレはedited=trueのためrange/IMSが共に無効化される');
});

// ---------- 2. /{bbs}/subject.txt ----------

test('KB-05: subject.txt: 200・Shift_JISバイト列・bareなmax-age/s-maxageヘッダ（Cache-Controlではない既知の不具合）', async () => {
  const { env, ctx, board } = setup();
  await board('board1').updateThread({ key: '1800000010', title: 'AAA', resNum: 3 }, false, 700);
  const res = await fetchHandler(new Request('http://x/board1/subject.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
  // 仕様書は "max-age=5, s-maxage=1" と記載しているが、実際にはCache-Controlヘッダではなく
  // 生の "max-age" / "s-maxage" という独自ヘッダ名で出力される（11章記載の既知の不具合）。
  assert.equal(res.headers.get('max-age'), '5');
  assert.equal(res.headers.get('s-maxage'), '1');
  assert.equal(res.headers.get('cache-control'), null);
  assert.deepEqual(await bytesOf(res), sjisBytes('1800000010.dat<>AAA (3)\n'));
});

test('subject.txt: 板にスレが無ければ200・空文字列', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/emptyboard/subject.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(await res.text(), '');
});

test('KB-06: subject.txt: 既知の不具合 — lastModifiedが常にnew Date()のため、前回のLast-Modifiedを' +
  'If-Modified-Sinceに送っても304にならず200が返る（専ブラの定期巡回が軽減されない）', async () => {
  // lastModified = new Date()(現在時刻)はミリ秒成分が実行タイミングに依存するため、元のテストは
  // 1000回に1回程度の確率でミリ秒がちょうど0になり304が返ってテストが落ちるフレーキーさが
  // あった（下の「ミリ秒がちょうど0」テストで再現している通り）。ここではグローバルDateを
  // ミリ秒≠0に固定して決定論的にする。
  await withFixedTime(Date.UTC(2024, 0, 1, 0, 0, 30, 500), async () => {
    const { env, ctx, board } = setup();
    await board('board1').updateThread({ key: '1800000011', title: 'BBB', resNum: 1 }, false, 700);
    const res1 = await fetchHandler(new Request('http://x/board1/subject.txt'), env, ctx);
    const lm = res1.headers.get('last-modified');
    assert.equal(lm, 'Mon, 01 Jan 2024 00:00:30 GMT');
    const res2 = await fetchHandler(new Request('http://x/board1/subject.txt', { headers: { 'if-modified-since': lm } }), env, ctx);
    assert.equal(res2.status, 200, '実際の巡回シナリオ（前回のLast-Modifiedをそのまま送る）では常に200');
  });
});

test('KB-06: subject.txt: new Date()のミリ秒がちょうど0の瞬間にリクエストが来た場合は、切り捨て誤差が無いため304になる', async () => {
  // KB-06は「lastModifiedが常に現在時刻」であることそのものが原因ではなく、toUTCString()に
  // よる秒精度への切り捨てが往復比較に誤差を生むことが原因。ミリ秒がちょうど0であれば
  // 誤差が生じないため、この特殊ケースでは304になる。これも実際に起こりうる挙動として固定する。
  await withFixedTime(Date.UTC(2024, 0, 1, 0, 0, 30, 0), async () => {
    const { env, ctx, board } = setup();
    await board('board1').updateThread({ key: '1800000111', title: 'BBB', resNum: 1 }, false, 700);
    const res1 = await fetchHandler(new Request('http://x/board1/subject.txt'), env, ctx);
    const lm = res1.headers.get('last-modified');
    assert.equal(lm, 'Mon, 01 Jan 2024 00:00:30 GMT');
    const res2 = await fetchHandler(new Request('http://x/board1/subject.txt', { headers: { 'if-modified-since': lm } }), env, ctx);
    assert.equal(res2.status, 304, 'ミリ秒がちょうど0の場合は誤差が無いため304になる');
  });
});

test('subject.txt: タスク指示文の想定に反し、意図的に遠い未来のIf-Modified-Sinceを送ると実際には304になる', async () => {
  // 依頼文面は「未来のIf-Modified-Sinceを送っても200になる」ことを想定していたが、
  // respondPlainTextの比較自体は `lastModified <= new Date(ifModifiedSinceHeader)` という
  // 素直な比較であり、本当に現在時刻より先の日付を送れば304になる。実際に不具合として
  // 効いているのは「lastModifiedが常に現在時刻」であるため前回値を送る通常の巡回では
  // 304にならないという点であり、それを上のテストで固定化した。ここではコードの実際の
  // 動作（ドキュメント/依頼文の想定と異なる部分）を記録する。
  const { env, ctx, board } = setup();
  await board('board1').updateThread({ key: '1800000012', title: 'CCC', resNum: 1 }, false, 700);
  const farFuture = new Date(Date.now() + 365 * 24 * 3600 * 1000).toUTCString();
  const res = await fetchHandler(new Request('http://x/board1/subject.txt', { headers: { 'if-modified-since': farFuture } }), env, ctx);
  assert.equal(res.status, 304);
});

// ---------- 3. /{bbs}/SETTING.TXT ----------

test('SETTING.TXT: R2に無ければデフォルト設定で<DIR_NAME>が板名に置換される', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/SETTING.TXT'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
  const text = new sjis().decode([...await bytesOf(res)]);
  assert.match(text, /^BBS_TITLE=board1＠3ch掲示板$/m);
});

test('SETTING.TXT: R2にあればその内容を使い、<DIR_NAME>を置換する', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('board2/SETTING.TXT', 'BBS_TITLE=<DIR_NAME> custom\nFOO=bar\n');
  const res = await fetchHandler(new Request('http://x/board2/SETTING.TXT'), env, ctx);
  const text = new sjis().decode([...await bytesOf(res)]);
  assert.match(text, /^BBS_TITLE=board2 custom$/m);
  assert.match(text, /^FOO=bar$/m);
});

// ---------- 4. /{bbs}/head.txt ----------

test('head.txt: R2内容をShift_JISでそのまま返す', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('board1/head.txt', 'ローカルルールです');
  const res = await fetchHandler(new Request('http://x/board1/head.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
  assert.deepEqual(await bytesOf(res), sjisBytes('ローカルルールです'));
});

test('head.txt: R2に無ければ空文字列で200', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/noheadboard/head.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.deepEqual(await bytesOf(res), new Uint8Array());
});

// ---------- 5. /{bbs}/ , /{bbs}/index.html ----------

for (const path of ['/board1/', '/board1/index.html']) {
  test(`板トップ ${path}: BBS_TITLEを埋め込んだShift_JIS HTML`, async () => {
    const { env, ctx } = setup();
    const res = await fetchHandler(new Request('http://x' + path), env, ctx);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'text/html;charset=Shift_JIS');
    // type="html"なのでLast-Modified/Accept-Rangesは付与されない（type==="plain"のときだけ付与)
    assert.equal(res.headers.get('last-modified'), null);
    assert.equal(res.headers.get('accept-ranges'), null);
    const html = new sjis().decode([...await bytesOf(res)]);
    assert.match(html, /<title>board1＠3ch掲示板<\/title>/);
  });
}

// ---------- 5b. 3chiko.html統合 ----------

test('トップページ /: Static Assets(ASSETS binding)経由で3chiko.htmlを返す', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/'), env, ctx);
  assert.equal(res.status, 200);
  const html = await res.text();
  assert.match(html, /<!-- 3chiko\.html -->/);
});

test('read.cgi /test/read.cgi/{bbs}/{key}/: 3chiko.htmlを返す', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/test/read.cgi/board1/1800000000/'), env, ctx);
  assert.equal(res.status, 200);
  const html = await res.text();
  assert.match(html, /<!-- 3chiko\.html -->/);
});

for (const path of ['/board1/kako/', '/board1/kako/1700']) {
  test(`過去ログ倉庫 ${path}: 3chiko.htmlを返す（subject.txt系APIとは別ルート）`, async () => {
    const { env, ctx } = setup();
    const res = await fetchHandler(new Request('http://x' + path), env, ctx);
    assert.equal(res.status, 200);
    const html = await res.text();
    assert.match(html, /<!-- 3chiko\.html -->/);
  });
}

test('板トップ /{bbs}/?webclient=3chiko: クエリ指定で3chiko.htmlを返す', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/?webclient=3chiko'), env, ctx);
  assert.equal(res.status, 200);
  const html = await res.text();
  assert.match(html, /<!-- 3chiko\.html -->/);
});

test('板トップ /{bbs}/: webclient=3chiko cookieでも3chiko.htmlを返す', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/', { headers: { cookie: 'webclient=3chiko' } }), env, ctx);
  assert.equal(res.status, 200);
  const html = await res.text();
  assert.match(html, /<!-- 3chiko\.html -->/);
});

test('板トップ /{bbs}/: webclient指定が無ければ従来どおり専ブラ用HTMLのまま', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=Shift_JIS');
  const html = new sjis().decode([...await bytesOf(res)]);
  assert.match(html, /専ブラ用/);
});

// ---------- 6. /bbsmenu.html ----------

test('bbsmenu.html: R2にbbsmenu.htmlがあればcache-control:no-storeで直接返す', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('bbsmenu.html', '<html>custom menu</html>');
  const res = await fetchHandler(new Request('http://x/bbsmenu.html'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await bytesOf(res), sjisBytes('<html>custom menu</html>'));
});

test('bbsmenu.html: bbsmenu.html無し・headline.txtありならheadlineから生成し、bareなmax-age/s-maxage', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('headline.txt', '1700000000\nboard1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n');
  const res = await fetchHandler(new Request('http://x/bbsmenu.html'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('max-age'), '60');
  assert.equal(res.headers.get('s-maxage'), '60');
  assert.equal(res.headers.get('cache-control'), null);
  const html = new sjis().decode([...await bytesOf(res)]);
  assert.match(html, /Board One/);
});

test('bbsmenu.html: bbsmenu.htmlもheadline.txtも無ければ404', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/bbsmenu.html'), env, ctx);
  assert.equal(res.status, 404);
});

// ---------- 7. /headline/subject.txt ----------

test('headline/subject.txt: R2のheadline.txtからUTF-8で整形して返す（cache-control:no-store）', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('headline.txt', '1700000000\nboard1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n');
  const res = await fetchHandler(new Request('http://x/headline/subject.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.equal(res.headers.get('cache-control'), 'no-store');
  assert.deepEqual(await bytesOf(res), utf8Bytes('board1<>Board One<>1700000000<>ThreadTitle<>lastmsg\n'));
});

test('headline/subject.txt: headline.txtが無ければ404', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/headline/subject.txt'), env, ctx);
  assert.equal(res.status, 404);
});

// ---------- 8. /{bbs}/kako/... ----------

test('kako/{年}/subject.txt: R2の年別ログをUTF-8で返し、削除済みキーは除外・cache-control:no-store', async () => {
  const { env, ctx, r2, board } = setup();
  r2.set('board1/thread/1700/subject.txt', '1700000000\n1700000001<>ThreadA<>name<>id<>date<>msg\n1700000002<>ThreadB<>name<>id<>date<>msg\n');
  const res1 = await fetchHandler(new Request('http://x/board1/kako/1700/subject.txt'), env, ctx);
  assert.equal(res1.status, 200);
  assert.equal(res1.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.equal(res1.headers.get('cache-control'), 'no-store');
  assert.equal(await res1.text(), '1700000001<>ThreadA<>name<>id<>date<>msg\n1700000002<>ThreadB<>name<>id<>date<>msg\n');

  await board('board1').removeThread('1700000001');
  const res2 = await fetchHandler(new Request('http://x/board1/kako/1700/subject.txt'), env, ctx);
  assert.equal(await res2.text(), '1700000002<>ThreadB<>name<>id<>date<>msg\n');
});

test('kako/{年}/subject.txt: R2に無ければ404', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/board1/kako/1800/subject.txt'), env, ctx);
  assert.equal(res.status, 404);
});

test('kako/subject.txt: 年一覧はbareなmax-age=86400,s-maxage=86400（Cache-Controlではない）', async () => {
  const { env, ctx, r2 } = setup();
  r2.set('board1/thread/subject.txt', '1700000000\n1700<>1700000000\n');
  const res = await fetchHandler(new Request('http://x/board1/kako/subject.txt'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.equal(res.headers.get('max-age'), '86400');
  assert.equal(res.headers.get('s-maxage'), '86400');
  assert.equal(res.headers.get('cache-control'), null);
  assert.equal(await res.text(), '1700<>1700000000\n');
});

// ---------- 9. /auth.js ----------

test('auth.js: 固定スクリプトをUTF-8 application/javascriptで返す', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/auth.js'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'application/javascript;charset=UTF-8');
  assert.deepEqual(await bytesOf(res), utf8Bytes('/* test */'));
});

// ---------- 10. /test/sakubbs.cgi/25 ----------

test('sakubbs.cgi/25: 固定文字列 "sakubbs.cgi v25"', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/test/sakubbs.cgi/25'), env, ctx);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.equal(await res.text(), 'sakubbs.cgi v25');
});

// ---------- 11. 未知のパス ----------

test('未知のパス: 404レスポンス形状（パスをエスケープして本文に含む）', async () => {
  const { env, ctx } = setup();
  const res = await fetchHandler(new Request('http://x/unknown/path/here'), env, ctx);
  assert.equal(res.status, 404);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  const text = await res.text();
  assert.match(text, /<title>Error 404<\/title>/);
  assert.match(text, /\/unknown\/path\/here/);
});

// ---------- 12. POST /test/bbs.cgi , /test/subbbs.cgi のルーティング ----------

test('POST /test/bbs.cgi と /test/subbbs.cgi はどちらも投稿エントリポイントに到達する', async () => {
  const { env, ctx } = setup();
  const body = () => new URLSearchParams({ bbs: 'board1', key: '', MESSAGE: 'hi', FROM: 'a', mail: '', subject: 't', time: '1', submit: '書き込む' });
  const res1 = await fetchHandler(new Request('http://x/test/bbs.cgi', { method: 'POST', body: body() }), env, ctx);
  assert.equal(res1.status, 200);
  assert.equal(res1.headers.get('content-type'), 'text/html;charset=UTF-8');

  const res2 = await fetchHandler(new Request('http://x/test/subbbs.cgi', { method: 'POST', body: body() }), env, ctx);
  assert.equal(res2.status, 200);
  assert.equal(res2.headers.get('content-type'), 'text/html;charset=UTF-8');
});
