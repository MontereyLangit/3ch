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
    .replace('"./bbs.cgi.js"', JSON.stringify(new URL('bbs.cgi.js', srcDir).href));
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

function setup() {
  const objects = new Map(), boards = new Map(), r2 = new Map();
  const env = { ch3www: { get: async key => r2.has(key) ? { text: async () => r2.get(key) } : null } };
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
  const expected = sjisBytes('name<><>2026/09/22(火) 12:00:00<>hello<>title\n');
  assert.deepEqual(await bytesOf(res), expected);
  assert.equal(res.headers.get('content-length'), String(expected.length));
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

test('dat: Range bytes=N- は206でContent-Range/Content-Length/Accept-Ranges付き', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000002');
  const total = sjisBytes('name<><>2026/09/22(火) 12:00:00<>hello<>title\n').length;
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000002.dat', { headers: { range: 'bytes=5-' } }), env, ctx);
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), `bytes 5-${total - 1}/${total}`);
  assert.equal(res.headers.get('content-length'), String(total - 5));
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
});

test('dat: Range bytes=N-M は範囲だけの206', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000003');
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000003.dat', { headers: { range: 'bytes=2-5' } }), env, ctx);
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 2-5/46');
  assert.equal(res.headers.get('content-length'), '4');
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

test('dat: If-Modified-Sinceが最終更新以降なら304、同一値ではミリ秒切り捨てにより200になる', async () => {
  const { env, ctx, thread } = setup();
  await createThread(thread, 'board1', '1800000007');
  const res1 = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat'), env, ctx);
  const lm = res1.headers.get('last-modified');

  // Last-Modifiedは秒精度(toUTCString)なので、そのまま同じ文字列をIf-Modified-Sinceに
  // 返しても実際のlastModified(ミリ秒付き)の方が僅かに後ろになり、<=を満たさず200になる。
  const resSame = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat', { headers: { 'if-modified-since': lm } }), env, ctx);
  assert.equal(resSame.status, 200, '秒精度の往復により同一値でも304にならない（実挙動として保存）');

  const oneSecondLater = new Date(new Date(lm).getTime() + 1000).toUTCString();
  const res2 = await fetchHandler(new Request('http://x/board1/dat/1800000007.dat', { headers: { 'if-modified-since': oneSecondLater } }), env, ctx);
  assert.equal(res2.status, 304);
  assert.equal(res2.headers.get('last-modified'), lm);
  assert.equal(res2.headers.get('content-type'), null);
});

test('dat: Last-Modifiedはdat中の最終レスのタイムスタンプ由来で、現在時刻ではない', async () => {
  const { env, ctx, thread } = setup();
  const t = await createThread(thread, 'board1', '1800000008');
  const fixedPast = new Date('2020-01-01T00:00:00Z').getTime();
  await t.state.storage.put('modifiedAt', fixedPast); // addRes直後のmodifiedAtを過去日時に差し替える
  const res = await fetchHandler(new Request('http://x/board1/dat/1800000008.dat'), env, ctx);
  assert.equal(res.headers.get('last-modified'), new Date(fixedPast).toUTCString());
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
  const { env, ctx, board } = setup();
  await board('board1').updateThread({ key: '1800000011', title: 'BBB', resNum: 1 }, false, 700);
  const res1 = await fetchHandler(new Request('http://x/board1/subject.txt'), env, ctx);
  const lm = res1.headers.get('last-modified');
  const res2 = await fetchHandler(new Request('http://x/board1/subject.txt', { headers: { 'if-modified-since': lm } }), env, ctx);
  assert.equal(res2.status, 200, '実際の巡回シナリオ（前回のLast-Modifiedをそのまま送る）では常に200');
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
