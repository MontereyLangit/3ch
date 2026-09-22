import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { bbs_entry } from '../src/bbs.cgi.js';
import { sjis } from '../src/common.js';

/*
 * 特性テスト (characterization tests)
 * これらのテストは bbs.cgi.js の「現在の実際の動作」を凍結するものであり、
 * 正しさを検証するものではない。既知の不具合（docs/現行システム仕様書.md 11章）も
 * そのまま凍結し、修正しない。src/ 以下は変更しないこと。
 */

// thread.js / board.js は cloudflare:workers に依存するため、thread.test.js と同じ手法で読み込む。
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

// R2 (ch3www) の get/put/etag をエミュレートする。bbs.cgi.js の updateSubback / updateHeadline は
// onlyIf.etagMatches を使った条件付き put に依存しているため、これが無いと成功するはずの投稿まで
// エラーになってしまう。
//
// 実際のCloudflare R2Conditional (put の onlyIf) が認識するプロパティは
// etagMatches / etagDoesNotMatch / uploadedBefore / uploadedAfter のみで、`doesNotExist` という
// プロパティは存在しない。R2 は認識できないプロパティを黙って無視するため、
// `onlyIf: { doesNotExist: true }` を渡しても実機では条件なしのputとして常に成功してしまう
// （updateSubback がこれに依存している点はKB-25として台帳に記録した）。ここでは意図的に
// doesNotExist を評価せず、実機と同じ「無視される」挙動にする。
// また、コンディションにより書き込みが行われなかった場合 R2Bucket#put は `{ uploaded: false }`
// ではなく `null` を返し、成功時の `uploaded` はアップロード日時を表す `Date` である
// （真偽値ではない）。ここではこの実際のAPI形状に合わせる。
function makeR2() {
  const store = new Map(); let etagCounter = 0;
  return {
    async get(key) { const rec = store.get(key); if (!rec) return null; return { text: async () => rec.text, etag: rec.etag }; },
    async put(key, value, opts = {}) {
      const rec = store.get(key);
      if (opts.onlyIf?.etagMatches !== undefined && (!rec || rec.etag !== opts.onlyIf.etagMatches)) return null;
      if (opts.onlyIf?.etagDoesNotMatch !== undefined && rec && rec.etag === opts.onlyIf.etagDoesNotMatch) return null;
      const etag = 'etag' + (++etagCounter);
      store.set(key, { text: value, etag });
      return { uploaded: new Date(), etag };
    },
    _store: store,
  };
}

function setup() {
  const objects = new Map(), boards = new Map();
  const r2 = makeR2();
  const capStore = new Map();
  const env = { ch3www: r2, CAP: { get: async (key) => capStore.has(key) ? capStore.get(key) : null }, salt: 'test-salt-12345' };
  for (const [binding, Class, instances] of [['THREAD_DO', Thread, objects], ['BOARD_DO', Board, boards]]) {
    env[binding] = { idFromName: key => key, get: key => { if (!instances.has(key)) instances.set(key, new Class(state(), env)); return instances.get(key); } };
  }
  return { env, r2, capStore, thread: key => env.THREAD_DO.get(key), board: key => env.BOARD_DO.get(key) };
}

function sjisPercentEncode(str) {
  const enc = new sjis();
  const bytes = enc.encode(str);
  return bytes.map(b => '%' + b.toString(16).padStart(2, '0').toUpperCase()).join('');
}

function buildBody(fields, sjisMode = false) {
  const parts = [];
  for (const [k, v] of Object.entries(fields)) {
    if (v === undefined) continue;
    const encoded = sjisMode ? sjisPercentEncode(String(v)) : encodeURIComponent(String(v));
    parts.push(`${k}=${encoded}`);
  }
  return parts.join('&');
}

function makeRequest({ method = 'POST', headers = {}, cf = {}, body = '' } = {}) {
  const h = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  return {
    method,
    cf,
    headers: { get: (name) => h.has(name.toLowerCase()) ? h.get(name.toLowerCase()) : null },
    arrayBuffer: async () => new TextEncoder().encode(body).buffer,
  };
}

const defaultHeaders = () => ({
  'content-type': 'application/x-www-form-urlencoded',
  'referer': 'https://www.3chbbs.com/test/read.cgi/sample/1/',
  'user-agent': 'Mozilla/5.0 (TestBrowser)',
  'CF-Connecting-IP': '192.0.2.1',
});
const defaultCf = () => ({ country: 'JP', asn: '2516', region: 'Tokyo', city: 'Tokyo' });

// フィールドを与えて POST リクエストを bbs_entry に渡す。submit は既定でWebブラウザ相当(UTF-8)。
async function post(env, fieldsOverride = {}, { headers = {}, cf = {}, sjisMode = false, submitText = '書き込む' } = {}) {
  const submit = sjisMode ? sjisPercentEncode(submitText) : encodeURIComponent(submitText);
  const fields = { FROM: '', mail: '', MESSAGE: 'テスト本文', subject: 'テストスレ', bbs: 'sample', key: '', time: '9999999999', ...fieldsOverride };
  const body = buildBody(fields, sjisMode) + `&submit=${submit}`;
  const req = makeRequest({ headers: { ...defaultHeaders(), ...headers }, cf: { ...defaultCf(), ...cf }, body });
  const res = await bbs_entry(req, env, {});
  const text = await res.text();
  return { res, text };
}

function errorMessage(text) {
  return text.match(/<font size=\+1 color=#FF0000><b>ERROR: ([\s\S]*?)<\/b><\/font>/)?.[1];
}

// ID/識別ID生成は new Date() (現在時刻) に依存するため、これを固定して決定論的な期待値を
// 検証できるようにする。bbs.cgi.js / thread.js 内の `new Date()`(引数無し) と `Date.now()` を
// 固定ミリ秒に差し替える。明示的な引数付き `new Date(x)` は素通しする。
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

// ==================== 1. checkRequest ====================

test('checkRequest: POSTでない場合は最初にエラーになる', async () => {
  const { env } = setup();
  const req = makeRequest({ method: 'GET', headers: defaultHeaders(), cf: defaultCf(), body: 'a=b' });
  const res = await bbs_entry(req, env, {});
  assert.equal(res.status, 200);
  assert.equal(errorMessage(await res.text()), 'POST メソッドを使ってください。<br>\nRecieve GET METHOD');
});

test('checkRequest: Content-Typeが不正な場合のエラー', async () => {
  const { env } = setup();
  const { res, text } = await post(env, {}, { headers: { 'content-type': 'text/plain' } });
  assert.equal(res.status, 200);
  assert.equal(errorMessage(text), '送信された内容が破損しています。->text/plain<br>\nBroken request body. ');
});

test('KB-24: checkRequest: Refererが不正な場合のエラー（メッセージ自体にERROR:が含まれ二重表示になる既知の癖）', async () => {
  const { env } = setup();
  const { res, text } = await post(env, {}, { headers: { referer: 'https://evil.example.com/' } });
  assert.equal(res.status, 200);
  // dispError に渡す文字列そのものが "ERROR: " から始まっているため、respondError が付ける
  // "ERROR: " と重複して "ERROR: ERROR: ..." になる。これは仕様書には明記されていない実装上の癖。
  assert.equal(errorMessage(text), 'ERROR: あなた騙されてますよ？->https://evil.example.com/<br>\nReferer is invalid.');
});

test('KB-24: checkRequest: UserAgentが7バイト未満の場合のエラー（同様に二重ERROR:）', async () => {
  const { env } = setup();
  const { text } = await post(env, {}, { headers: { 'user-agent': 'abc' } });
  assert.equal(errorMessage(text), 'ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: abc<br>\nUserAgent is invalid.');
});

test('checkRequest: UserAgentが384バイトを超える場合のエラー', async () => {
  const { env } = setup();
  const ua = 'a'.repeat(385);
  const { text } = await post(env, {}, { headers: { 'user-agent': ua } });
  assert.equal(errorMessage(text), `ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: ${ua}<br>\nUserAgent is invalid.`);
});

test('checkRequest: UserAgentに非ASCII文字が含まれる場合のエラー', async () => {
  const { env } = setup();
  const ua = 'Mozilla/5.0 日本語UA';
  const { text } = await post(env, {}, { headers: { 'user-agent': ua } });
  assert.equal(errorMessage(text), `ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: ${ua}<br>\nUserAgent is invalid.`);
});

test('checkRequest: client-ipヘッダがあると簡易Proxyチェックで拒否される', async () => {
  const { env } = setup();
  const { text } = await post(env, {}, { headers: { 'client-ip': '1.2.3.4' } });
  assert.equal(errorMessage(text), '変な情報が送られて来ました。<br>Client_IP: 1.2.3.4');
});

test('checkRequest: 複数の不正が同時にあってもソースコード上の記述順で最初のチェックが勝つ', async () => {
  const { env } = setup();
  // POST以外 + Content-Type不正 + Referer不正 + UA不正 + client-ip 同時に混在させ、method判定が最優先されることを確認
  const req = makeRequest({
    method: 'GET',
    headers: { 'content-type': 'text/plain', referer: 'https://evil.example.com/', 'user-agent': 'x', 'client-ip': '9.9.9.9' },
    cf: defaultCf(),
    body: buildBody({ FROM: '', mail: '', MESSAGE: 'x', subject: 'x', bbs: 'sample', key: '', time: '1', submit: '書き込む' }),
  });
  const res = await bbs_entry(req, env, {});
  assert.equal(errorMessage(await res.text()), 'POST メソッドを使ってください。<br>\nRecieve GET METHOD');
});

// ==================== 2. エラー時のレスポンス形式 ====================

test('エラーレスポンスはHTTP 200で返り、<!-- 2ch_X:error --> マーカーとERROR:表記を含む（専ブラ互換のための仕様）', async () => {
  const { env } = setup();
  const { res, text } = await post(env, { MESSAGE: '' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  assert.match(text, /<!-- 2ch_X:error -->/);
  assert.match(text, /<font size=\+1 color=#FF0000><b>ERROR: 本文がありません！<\/b><\/font>/);
});

// ==================== 3. 成功時のレスポンス形式 ====================

test('成功レスポンスはHTTP 200でx-threadkey/x-resnumヘッダとmeta refreshを含む', async () => {
  const { env } = setup();
  const { res, text } = await post(env, { FROM: 'nm', mail: 'ml', MESSAGE: 'hello', subject: 'sb' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  const key = res.headers.get('x-threadkey');
  assert.match(key, /^\d+$/);
  assert.equal(res.headers.get('x-resnum'), '1');
  // meta refresh のURLは bbs/key。テンプレート中は l50 の直後に2つの半角スペースが入る実装上の癖。
  assert.match(text, new RegExp(`<meta content=5;URL=/test/read\\.cgi/sample/${key}/l50  http-equiv=refresh>`));
});

// ==================== 4. dat行フォーマット ====================

test('dat行フォーマット: スレ立て(>>1)は<>5フィールド、本文前後に半角スペース、末尾フィールドにスレタイ+ID', async () => {
  // ID/識別IDはnew Date()に依存するため時刻を固定し、文字クラス・長さだけでなく実測した
  // 具体的な文字列そのもの(ハッシュ値込み)を検証する。文字クラスチェックだけでは、ハッシュの
  // 入力順序・ソルト・時刻窓が変わる移植ミスを検知できない。
  // また、mailに"" (空文字)を渡すとKB-10(メール欄は常に空)の検証にならない
  // (「メールを保存する実装」でも空入力なら偶然一致してしまう)ため、あえて非空の'sage'を渡す。
  // さらに`split('\n').filter(Boolean)`で行を数えるとdat全体の末尾改行の有無や余分な空行の
  // 混入を検知できないため、dat文字列全体を(末尾改行込みで)厳密に比較する。
  await withFixedTime(1700000000 * 1000, async () => {
    const { env } = setup();
    const { res } = await post(env, { FROM: '名前太郎', mail: 'sage', MESSAGE: 'テスト本文', subject: 'たておき' });
    const key = res.headers.get('x-threadkey');
    assert.equal(key, '1700000000');
    const dat = await env.THREAD_DO.get('sample/' + key).getDat();
    // 実測値(salt='test-salt-12345', IP=192.0.2.1, asn=2516, country=JP, region=Tokyo, city=Tokyo,
    // unixtime=1700000000固定)。末尾の改行1つも含めdat全体を厳密一致させる。
    assert.equal(
      dat,
      '名前太郎 </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> テスト本文 <>たておき [ID:zyIjCtRI★]\n'
    );
    const fields = dat.trimEnd().split('<>');
    assert.equal(fields.length, 5);
    assert.equal(fields[1], ''); // メールは常に空(KB-10)。mail='sage'という非空入力でも保存されない。
  });
});

test('dat行フォーマット: 返信(>>2)はメールと5番目フィールド(タイトル)が空', async () => {
  // ここでもmailに非空の'sage'を渡し、KB-10(メール欄は常に空)がたまたま空入力で一致している
  // だけではないことを検証する。dat全体を末尾改行込みで厳密一致させ、余分な空行や
  // 改行の欠落も検知できるようにする。
  await withFixedTime(1700000000 * 1000, async () => {
    const { env } = setup();
    const { res: res1 } = await post(env, { subject: 'たておき2' });
    const key = res1.headers.get('x-threadkey');
    const { res: res2 } = await post(env, { key, subject: '', mail: 'sage', MESSAGE: '2番目の返信' });
    assert.equal(res2.headers.get('x-resnum'), '2');
    const dat = await env.THREAD_DO.get('sample/' + key).getDat();
    // 実測値。1行目・2行目とも末尾に改行が1つずつ入り、2行目のメール欄(fields[1])とタイトル欄
    // (fields[4])はどちらも空文字になる。
    assert.equal(
      dat,
      '名無しさん＠３ちゃんねる </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> テスト本文 <>たておき2 [ID:zyIjCtRI★]\n' +
      '名無しさん＠３ちゃんねる </b>(JP.SA.h3 Kx-042e35264c7d19b9)<b><><>2023/11/15(水) 07:13:20 ID:zyIjCtRI<> 2番目の返信 <>\n'
    );
    const lines = dat.trimEnd().split('\n');
    assert.equal(lines.length, 2);
    const fields = lines[1].split('<>');
    assert.equal(fields.length, 5);
    assert.equal(fields[1], ''); // メール欄は常に空(KB-10)。mail='sage'という非空入力でも保存されない。
    assert.equal(fields[3], ' 2番目の返信 ');
    assert.equal(fields[4], ''); // 2レス目以降はタイトルなし
  });
});

// ==================== 5. readForm: 文字コード判定・サニタイズ ====================

test('readForm: submit=書のUTF-8バイト列が無いと専ブラ(Monazilla)以外はSJIS扱いにされ拒否される', async () => {
  const { env } = setup();
  const { text } = await post(env, {}, { headers: { 'user-agent': 'Mozilla/5.0 NotMona' }, sjisMode: true, submitText: '書き込む' });
  const expected = "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post.";
  // このエラー画面自体はSJISでエンコードされるため、生バイトから復号して比較する。
  const bytes = new Uint8Array(await (await bbs_entry(
    makeRequest({ headers: { ...defaultHeaders(), 'user-agent': 'Mozilla/5.0 NotMona' }, cf: defaultCf(), body: buildBody({ FROM: '', mail: '', MESSAGE: 'x', subject: 'x', bbs: 'sample', key: '', time: '9999999999' }, true) + '&submit=' + sjisPercentEncode('書き込む') }),
    env, {}
  )).arrayBuffer());
  const decoded = new sjis().decode([...bytes]);
  assert.match(decoded, /ＥＲＲＯＲ！/);
  assert.ok(decoded.includes(expected));
});

test('readForm: Monazilla + Shift_JISエンコードされたPOSTは正しく復号される', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名無し', MESSAGE: '日本語の本文です', subject: 'SJISテスト' }, {
    headers: { 'user-agent': 'Monazilla/1.00' }, sjisMode: true, submitText: '書き込む',
  });
  assert.equal(res.headers.get('x-resnum'), '1');
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  assert.match(dat, / 日本語の本文です /);
});

test('readForm: HTML特殊文字のエスケープ (& " < > \')', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: `a&b"c<d>e'f`, MESSAGE: `msg&<>"'`, subject: 'esc' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.equal(fields[0].split(' </b>')[0], 'a&amp;b&quot;c&lt;d&gt;e&apos;f');
  assert.equal(fields[3], ' msg&amp;&lt;&gt;&quot;&apos; ');
});

test('readForm: タブは半角スペースに、制御文字は除去される', async () => {
  const { env } = setup();
  const { res } = await post(env, { MESSAGE: 'a\tb\x01\x02c', subject: 'tabtest' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.equal(fields[3], ' a bc '); // タブ->半角スペース、\x01\x02は除去
});

test('readForm: ★→☆, ◆→◇, ＃→#の偽キャップ・偽トリップ変換', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '★fake◆trip＃hash', MESSAGE: 'x', subject: 'sub2' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  // FROM先頭が "☆fake◇trip" になり、"＃hash" 以降はトリップキーとして分離されトリップ生成に使われる
  assert.match(name, /^☆fake◇trip <\/b>◆[./0-9A-Za-z]{10}<\/b> /);
});

// ==================== 6. <br>整形の既知バグ ====================

test('KB-03: 既知の不具合: MESSAGE.replace("<br>"," <br> ")は最初の1箇所のみ整形される（複数行本文で凍結）', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前太郎', MESSAGE: '1行目\n2行目\n3行目', subject: 'brbug' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const message = dat.split('\n')[0].split('<>')[3];
  // 本来なら全ての<br>前後にスペースが入るべきだが、String.prototype.replaceが第1引数に文字列(非グローバル正規表現)を
  // 渡されているため最初の1箇所しか置換されない。2つ目以降の<br>は前後スペース無しのまま凍結する。
  assert.equal(message, ' 1行目 <br> 2行目<br>3行目 ');
});

// ==================== 7. checkFieldSize ====================

test('checkFieldSize: subject/name/mailの文字数上限エラー', async () => {
  const { env } = setup();
  assert.equal(errorMessage((await post(env, { subject: 'あ'.repeat(65) })).text), 'サブジェクトが長すぎます！(65/64)');
  assert.equal(errorMessage((await post(env, { FROM: 'あ'.repeat(65), subject: 'x' })).text), '名前が長すぎます！(65/64)');
  assert.equal(errorMessage((await post(env, { mail: 'a'.repeat(65), subject: 'x' })).text), 'メールアドレスが長すぎます！(65/64)');
});

test('checkFieldSize: >>2以降の本文は4096文字まで、超えるとエラー', async () => {
  const { env } = setup();
  const { res } = await post(env, { subject: 'msgcap' });
  const key = res.headers.get('x-threadkey');
  const { text } = await post(env, { key, subject: '', MESSAGE: 'a'.repeat(4097) });
  assert.equal(errorMessage(text), '本文が長すぎます！(4097/4096)');
});

test('checkFieldSize: >>1の本文は65536文字まで拡張される（4097文字以上でも>>1なら通る）', async () => {
  const { env: env1 } = setup();
  const { text: t1 } = await post(env1, { bbs: 'boardone', subject: 'bigmsg1', MESSAGE: 'a'.repeat(65536) });
  assert.doesNotMatch(t1, /ERROR/);
  const { env: env2 } = setup();
  const { text: t2 } = await post(env2, { bbs: 'boardtwo', subject: 'bigmsg2', MESSAGE: 'a'.repeat(65537) });
  assert.equal(errorMessage(t2), '本文が長すぎます！(65537/65536)');
});

test('checkFieldSize: 行数チェックは>>2以降のみ適用される (BBS_LINE_NUMBER=30のとき<br>分割数60まで許可)', async () => {
  const { env } = setup();
  const { res } = await post(env, { subject: 'linecap' });
  const key = res.headers.get('x-threadkey');
  const ok = Array.from({ length: 60 }, (_, i) => `l${i}`).join('\n'); // 59個の<br> -> split長60
  const { text: tOk } = await post(env, { key, subject: '', MESSAGE: ok });
  assert.doesNotMatch(tOk, /ERROR/);
  const bad = Array.from({ length: 61 }, (_, i) => `l${i}`).join('\n'); // 60個の<br> -> split長61
  const { text: tBad } = await post(env, { key, subject: '', MESSAGE: bad });
  assert.equal(errorMessage(tBad), '改行が多すぎます！(61/60)');
  // >>1(スレ立て)では行数チェック自体が適用されない
  const { text: tNewThreadManyLines } = await post(env, { bbs: 'linecapboard', subject: 'linecapnew', MESSAGE: bad });
  assert.doesNotMatch(tNewThreadManyLines, /ERROR/);
});

test('checkFieldSize: 名無し補完は板設定BBS_NONAME_NAME、無ければ既定値', async () => {
  const { env, r2 } = setup();
  const { res: r1 } = await post(env, { FROM: '', subject: 'defaultnoname' });
  const dat1 = await env.THREAD_DO.get('sample/' + r1.headers.get('x-threadkey')).getDat();
  assert.match(dat1.split('\n')[0], /^名無しさん＠３ちゃんねる /);

  await r2.put('sample2/SETTING.TXT', 'BBS_NONAME_NAME=カスタム名無し\n');
  const { res: r2res } = await post(env, { bbs: 'sample2', FROM: '', subject: 'customnoname' });
  const dat2 = await env.THREAD_DO.get('sample2/' + r2res.headers.get('x-threadkey')).getDat();
  assert.match(dat2.split('\n')[0], /^カスタム名無し /);
});

// ==================== 8. processTrip ====================

test('processTrip: 12バイト以上のトリップキーはSHA-1先頭12文字、+は.に置換される', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前#abcdefghijklmnop', subject: 'triptest1' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆FPOZUois0Ynm<\/b> /);
});

test('processTrip: 12バイト未満のトリップキーはDES crypt末尾10文字', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前#abc', subject: 'triptest2' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆L8nEQyID76<\/b> /);
});

test('processTrip: #+16桁16進数の特殊トリップ（DES, ソルト省略時は".."）', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前##0123456789abcdef', subject: 'triptest3' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆ClNHFHdYIw<\/b> /);
});

test('processTrip: #+16桁16進数+ソルト2文字指定', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前##0123456789abcdefXY', subject: 'triptest3b' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆MIe14Gpa2k<\/b> /);
});

test('processTrip: 先頭が$の場合は16進フォーマットの正規表現に一致せず???になる（$は正規表現内で考慮されていない既知の癖）', async () => {
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前#$0123456789abcdef', subject: 'triptest4' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆\?\?\?<\/b> /);
});

test('processTrip: トリップキーが1023バイトを超えるとエラー', async () => {
  const { env } = setup();
  const { text } = await post(env, { FROM: '名前#' + 'a'.repeat(1024), subject: 'triptest5' });
  assert.equal(errorMessage(text), 'トリップキー長杉！<br>\nYour tripkey must be less than 1023 bytes.');
});

// これまでのトリップの例はいずれもASCIIキーのみだった。processTripの分岐はSJISエンコードした
// 「バイト数」(gb.tripKey自体の文字数ではない)が12以上かどうかで決まるため、日本語キーで
// 11/12バイトの境界をまたぐケースが無いと、KB-14(SJISバイト列をUTF-8として再エンコードして
// SHA-1を取る3ch独自の癖)を素直なバイトハッシュで実装した移植ミスを検知できない
// (ASCIIキーはSJISでも1バイト/文字なのでUTF-8再エンコードの影響が出にくい)。
test('processTrip: 日本語トリップキー、SJISで11バイト(DES branch)と12バイト(SHA-1 branch)の境界', async () => {
  const { env: env11 } = setup();
  // 'ぁ'(SJIS 2バイト)×5 + 'x'(1バイト) = 11バイト -> DES branch
  const { res: res11 } = await post(env11, { FROM: '名前#ぁぁぁぁぁx', subject: 'trip-des-jp' });
  const dat11 = await env11.THREAD_DO.get('sample/' + res11.headers.get('x-threadkey')).getDat();
  assert.match(dat11.split('\n')[0].split('<>')[0], /^名前 <\/b>◆f6w4Q5QyaY<\/b> /);

  const { env: env12 } = setup();
  // 'ぁ'×6 = 12バイト -> ちょうど境界でSHA-1 branchに入る
  const { res: res12 } = await post(env12, { FROM: '名前#ぁぁぁぁぁぁ', subject: 'trip-sha1-jp' });
  const dat12 = await env12.THREAD_DO.get('sample/' + res12.headers.get('x-threadkey')).getDat();
  assert.match(dat12.split('\n')[0].split('<>')[0], /^名前 <\/b>◆5CrvAuK4nFvz<\/b> /);
});

test('processTrip: SHA-1 branchの出力に+が含まれる場合は.に置換される（日本語トリップキーで実際に検証）', async () => {
  // 元のASCIIキーの例(processTrip: 12バイト以上...)でも+の置換自体はコメントで説明されていたが、
  // 実際に+を含む出力になるASCIIキーの具体例が示されていなかった。ここでは実際にSHA-1のBase64
  // 先頭12文字に+を含む日本語トリップキーを実測して固定する。
  const { env } = setup();
  // 'げ'(SJIS 2バイト)×6 = 12バイト。sha1Base64(...).slice(0,12) === '4Wz7VgvHRoe+' であることを実測済み。
  const { res } = await post(env, { FROM: '名前#げげげげげげ', subject: 'trip-plus-jp' });
  const dat = await env.THREAD_DO.get('sample/' + res.headers.get('x-threadkey')).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前 <\/b>◆4Wz7VgvHRoe\.<\/b> /); // 末尾の+が.に置換されている
});

// ==================== 9. processCap ====================

test('processCap: 旧仕様(!接頭辞)の弱キャップは "ハンドル ★" になり、ID:???/識別ID:★になる', async () => {
  const { env, capStore } = setup();
  capStore.set('weakpass', 'ワイズハンドル');
  const { res } = await post(env, { mail: '#!weakpass', FROM: '', subject: 'captest1' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.match(fields[0], /^ワイズハンドル ★ <\/b>\(★\)<b>$/);
  assert.match(fields[2], /ID:\?\?\?$/);
});

test('processCap: 名前を入れていると "名前＠ハンドル ★" になる', async () => {
  const { env, capStore } = setup();
  capStore.set('weakpass2', 'ハンドル２');
  const { res } = await post(env, { mail: '#!weakpass2', FROM: '名前です', subject: 'captest2' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名前です＠ハンドル２ ★ <\/b>\(★\)<b>$/);
});

test('processCap: 末尾★の強キャップはcheckFieldSizeの文字数制限をバイパスする', async () => {
  const { env, capStore } = setup();
  capStore.set('strongpass', 'つよいてすと★');
  const longName = 'a'.repeat(100); // 既定のBBS_NAME_COUNT(64)を超える
  const { res, text } = await post(env, { mail: '#!strongpass', FROM: longName, subject: 'captest3' });
  assert.doesNotMatch(text, /ERROR/);
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, new RegExp(`^${longName}＠つよいてすと ★ </b>\\(★\\)<b>$`));
});

test('processCap: mail欄の＃(全角)も#に変換されキャップとして認識される', async () => {
  const { env, capStore } = setup();
  capStore.set('weakpass3', 'ゼンカクハンドル');
  const { res } = await post(env, { mail: '＃!weakpass3', FROM: '', subject: 'captest4' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  assert.match(dat.split('\n')[0], /^ゼンカクハンドル ★ /);
});

// これまでのキャップの例はすべて旧仕様(!接頭辞)の弱キャップだった。!が無い通常パス
// (SHA-1(SHA-256(MD5(pass)+salt))を27文字に切り詰めてKVキーにする)は一度も通っておらず、
// このロジックを移植で丸ごと落とした/間違えた場合でもテストが検知できなかった。
test('processCap: !接頭辞が無い通常パスは SHA-1(SHA-256(MD5(pass)+salt)) の先頭27文字がKVキーになる', async () => {
  const { env, capStore } = setup();
  const rawPass = 'mynormalpass';
  // 実測値: salt='test-salt-12345' の下で
  // sha1Base64(sha256hex(md5(rawPass) + md5(salt))) の先頭27文字(=sha1Base64の全体)。
  const capPass = 'tSRvRHdM/NG0AWoskdPh+cSK1zY';
  assert.equal(capPass.length, 27);
  capStore.set(capPass, '通常キャップハンドル');
  const { res } = await post(env, { mail: '#' + rawPass, FROM: '', subject: 'capnormal' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.match(fields[0], /^通常キャップハンドル ★ <\/b>\(★\)<b>$/);
  assert.match(fields[2], /ID:\?\?\?$/);

  // rawPassが1文字でも違えばKVキーも変わり、キャップとして認識されないことも合わせて確認する
  // (通常パスが本当にハッシュ計算を行っていることの反証)。
  const { env: env2, capStore: capStore2 } = setup();
  capStore2.set(capPass, '通常キャップハンドル');
  const { res: res2 } = await post(env2, { mail: '#' + rawPass + 'x', FROM: '', subject: 'capnormal2' });
  const key2 = res2.headers.get('x-threadkey');
  const dat2 = await env2.THREAD_DO.get('sample/' + key2).getDat();
  assert.doesNotMatch(dat2.split('\n')[0], /通常キャップハンドル/);
});

// ==================== 10. ID・識別ID生成 ====================

test('makeIdAndShikibetuId: BBS_ID=off の場合、ID表示は日付のみでスレタイにID付与も無い', async () => {
  const { env, r2 } = setup();
  await r2.put('sample/SETTING.TXT', 'BBS_ID=off\n');
  const { res } = await post(env, { subject: 'idofftest' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.doesNotMatch(fields[2], /ID:/); // 日付のみ、IDは付かない
  assert.equal(fields[4], 'idofftest'); // スレタイにも[ID:...★]が付かない
});

test('makeIdAndShikibetuId: BBS_ID_SHIKIBETSU=d では識別IDは空文字だが、名前欄には空の" </b>()<b>"が残る（既知の癖）', async () => {
  const { env, r2 } = setup();
  await r2.put('sample/SETTING.TXT', 'BBS_ID_SHIKIBETSU=d\n');
  const { res } = await post(env, { subject: 'iddtest' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const name = dat.split('\n')[0].split('<>')[0];
  assert.match(name, /^名無しさん＠３ちゃんねる <\/b>\(\)<b>$/);
});

test('makeIdAndShikibetuId: 同じ入力(IP・AS・地域・UA・時刻窓)なら同じIDと識別IDが再現される（決定性）、かつ実測した具体的な値と一致する', async () => {
  // 単純な「2回実行して一致するか」だけの比較では、ハッシュの入力順序やソルト、時刻窓の取り方が
  // 違う実装(同じ入力に対して決定論的でありさえすれば良い実装)でも通ってしまい、移植の検証に
  // ならない。時刻を固定し、実測した具体的な文字列と一致することまで確認する。
  // また、時刻を固定しない元のテストは、2回のpostがBBS_ID_RESETの日付境界を跨ぐと
  // (滅多に起きないが)IDが変わってしまい、テストがフレーキーになる問題があった。
  await withFixedTime(1700000000 * 1000, async () => {
    const { env: env1 } = setup();
    const { env: env2 } = setup();
    const opts = { headers: { 'CF-Connecting-IP': '203.0.113.9' }, cf: { country: 'US', asn: '999', region: 'CA', city: 'LA' } };
    const { res: r1 } = await post(env1, { subject: 'dettest1', FROM: 'samename' }, opts);
    const { res: r2res } = await post(env2, { subject: 'dettest2', FROM: 'samename' }, opts);
    const dat1 = await env1.THREAD_DO.get('sample/' + r1.headers.get('x-threadkey')).getDat();
    const dat2 = await env2.THREAD_DO.get('sample/' + r2res.headers.get('x-threadkey')).getDat();
    const f1 = dat1.split('\n')[0].split('<>');
    const f2 = dat2.split('\n')[0].split('<>');
    assert.equal(f1[0], f2[0]); // 名前欄(識別ID込み)が一致
    assert.equal(f1[2].match(/ID:.*/)[0], f2[2].match(/ID:.*/)[0]); // IDが一致
    // 実測値(salt='test-salt-12345', IP=203.0.113.9, asn=999, country=US, region=CA, city=LA,
    // unixtime=1700000000固定)。
    assert.equal(f1[0], 'samename </b>(US.3T.fb No-042e35264c7d19b9)<b>');
    assert.equal(f1[2].match(/ID:.*/)[0], 'ID:tAkuU2RS');
  });
});

test('makeIdAndShikibetuId: BBS_ID_RESETの日付境界(JST)を1秒跨ぐとID・識別IDが変わる', async () => {
  // BBS_ID_RESET(既定86400秒)による時刻窓は Math.floor((unixtime+32400)/BBS_ID_RESET) で計算され、
  // 32400(=9時間, JSTオフセット)が加算されるためJST日付境界で切り替わる。
  // unixtime ≡ 54000 (mod 86400) が窓の切り替わり瞬間で、その1秒前(53999)と(54000)を比較する。
  const results = {};
  for (const t of [53999, 54000]) {
    await withFixedTime(t * 1000, async () => {
      const { env } = setup();
      const bbs = 'boardboundary' + t;
      const { res } = await post(env, { FROM: 'boundary', subject: 'boundarytest', bbs });
      const dat = await env.THREAD_DO.get(`${bbs}/${res.headers.get('x-threadkey')}`).getDat();
      results[t] = dat.split('\n')[0].split('<>');
    });
  }
  // 実測値。窓が変わるため、識別ID(地域/AS由来ハッシュ)・ID(IP由来ハッシュ)ともに変化する。
  assert.equal(results[53999][0], 'boundary </b>(JP.yb.YR 67-ff3061e72f92f8bb)<b>');
  assert.equal(results[53999][2].match(/ID:.*/)[0], 'ID:QVHSvTOt');
  assert.equal(results[54000][0], 'boundary </b>(JP.8T.pU V0-4f59499349177794)<b>');
  assert.equal(results[54000][2].match(/ID:.*/)[0], 'ID:Qzj1oBxa');
  assert.notEqual(results[53999][0], results[54000][0]);
  assert.notEqual(results[53999][2], results[54000][2]);
});

test('makeIdAndShikibetuId: IPv6アドレスはコロン区切りの先頭4ブロックのみがID計算の種になる', async () => {
  // checkRequestのgb.idnotane生成 (`${octet[0]}:${octet[1]}:${octet[2]}:${octet[3]}`) により、
  // IPv6アドレスは先頭4ブロックだけが使われ、残りのブロックが変わってもIDは変化しないはず。
  await withFixedTime(1700000000 * 1000, async () => {
    const { env: envA } = setup();
    const { res: resA } = await post(envA, { FROM: 'ipv6a', subject: 'ipv6a' }, { headers: { 'CF-Connecting-IP': '2001:0db8:85a3:0000:0000:8a2e:0370:7334' } });
    const datA = await envA.THREAD_DO.get(`sample/${resA.headers.get('x-threadkey')}`).getDat();
    const fieldsA = datA.split('\n')[0].split('<>');

    // 先頭4ブロックが同じで、5ブロック目以降だけ異なるアドレス -> IDは同じになるはず
    const { env: envB } = setup();
    const { res: resB } = await post(envB, { FROM: 'ipv6b', subject: 'ipv6b' }, { headers: { 'CF-Connecting-IP': '2001:0db8:85a3:0000:ffff:ffff:ffff:ffff' } });
    const datB = await envB.THREAD_DO.get(`sample/${resB.headers.get('x-threadkey')}`).getDat();
    const fieldsB = datB.split('\n')[0].split('<>');

    // 4番目のブロックが異なるアドレス -> IDは変わるはず
    const { env: envC } = setup();
    const { res: resC } = await post(envC, { FROM: 'ipv6c', subject: 'ipv6c' }, { headers: { 'CF-Connecting-IP': '2001:0db8:85a3:9999:0000:8a2e:0370:7334' } });
    const datC = await envC.THREAD_DO.get(`sample/${resC.headers.get('x-threadkey')}`).getDat();
    const fieldsC = datC.split('\n')[0].split('<>');

    const idA = fieldsA[2].match(/ID:.*/)[0];
    const idB = fieldsB[2].match(/ID:.*/)[0];
    const idC = fieldsC[2].match(/ID:.*/)[0];
    assert.equal(idA, idB); // 5ブロック目以降の違いはIDに影響しない
    assert.notEqual(idA, idC); // 4ブロック目までの違いはIDに影響する
    // 実測値。
    assert.equal(idA, 'ID:rdEW987b');
    assert.equal(idC, 'ID:HWZr8V4Y');
  });
});

test('makeIdAndShikibetuId: BBS_ID_GROUP/BBS_ID_RESETを明示指定した場合の実測値', async () => {
  // BBS_ID_GROUPが空の場合はbbs名がグループとして使われるが、明示指定すると板名の代わりに使われる。
  // BBS_ID_RESETも既定の86400(1日)から変更できる。両方を明示指定した場合の実際の出力値を固定する。
  await withFixedTime(1700000000 * 1000, async () => {
    const { env, r2 } = setup();
    await r2.put('idsettingsboard/SETTING.TXT', 'BBS_ID_GROUP=customgroup\nBBS_ID_RESET=3600\n');
    const { res } = await post(env, { FROM: 'settingstest', subject: 'settingstest', bbs: 'idsettingsboard' });
    const dat = await env.THREAD_DO.get(`idsettingsboard/${res.headers.get('x-threadkey')}`).getDat();
    const fields = dat.split('\n')[0].split('<>');
    // 実測値(BBS_ID_GROUP=customgroup, BBS_ID_RESET=3600, unixtime=1700000000固定)。
    assert.equal(fields[0], 'settingstest </b>(JP.7S.93 ju-ed4575071d4e9f87)<b>');
    assert.equal(fields[2].match(/ID:.*/)[0], 'ID:ZnlBkMof');
  });
});

// ==================== 11. 特殊ルーティング ====================

test('特殊ルーティング: !newthread コマンドで9始まりkeyからスレ立てに変換される', async () => {
  const { env } = setup();
  const { res } = await post(env, { bbs: 'sample', key: '9', subject: '', MESSAGE: '!newthread議題ですね\n本文本文' });
  assert.equal(res.headers.get('x-resnum'), '1');
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const fields = dat.split('\n')[0].split('<>');
  assert.equal(fields[3], '  <br> 本文本文 '); // subjectとして切り出された残りの本文（先頭<br>のぶん前後スペース重複）
  assert.match(fields[4], /^議題ですね \[ID:.*★\]$/);
});

test('特殊ルーティング: keyが924または900始まりだと板名が強制的に"924"へ書き換えられる', async () => {
  const { env } = setup();
  const { text } = await post(env, { bbs: 'othername', key: '9240000005', subject: '' });
  // 投稿されたbbs="othername"は無視され、"924"板のスレッドとして扱われるため該当スレ無しエラーのパスに"924"が現れる
  assert.equal(errorMessage(text), '該当するスレッドがありません。924/thread/9240/9240000005.cgi');
});

test('特殊ルーティング: 禁止された板名は拒否される', async () => {
  const { env } = setup();
  for (const bbs of ['', 'test', 'js', 'css', 'html', 'images']) {
    const { text } = await post(env, { bbs, key: '', subject: 'x' });
    assert.equal(errorMessage(text), '板の指定が不正です。<br>\nThe board directory is incorrect...', `bbs="${bbs}"`);
  }
});

// ==================== 12. スレッド上限 ====================

test('既知の不具合: dat行数が1000以上で拒否されるため実質999レスで書き込み不能になる', async () => {
  const { env } = setup();
  const { res } = await post(env, { subject: 'capthread' });
  const key = res.headers.get('x-threadkey');
  let lastResnum = null, failureText = null;
  for (let i = 2; i <= 1001; i++) {
    const { res: r, text } = await post(env, { key, subject: '', MESSAGE: 'res' + i });
    if (r.headers.get('x-resnum')) lastResnum = r.headers.get('x-resnum');
    else { failureText = text; break; }
  }
  assert.equal(lastResnum, '999'); // 1000レス目で拒否されるため実際は999レスで打ち止め
  assert.equal(errorMessage(failureText), 'このスレッドは1000を超えたのでもう書けません。');
});

// ==================== 13. R2条件付きput(updateSubback)の実際のAPI形状 ====================

test('KB-25: updateSubback: onlyIf.doesNotExistは実際のR2Conditionalに存在せず無視されるため、初回作成時の重複防止ガードが効かない', async () => {
  const { env, r2 } = setup();
  // これから立てるスレのキー(=現在unixtime)のprefixを先読みし、そこにすでに"他者の書き込み"が
  // あったことにする。updateSubback は subbackObj===null(=このprefixでの初回作成)の時、
  // onlyIf.doesNotExist を使って「まだ無ければ作る」つもりで put しているが、実際のR2はこの
  // プロパティを認識せず無条件putになるため、既存の内容があっても構わず上書きしてしまう。
  const nowPrefix = Math.floor(Date.now() / 1000).toString().slice(0, 4);
  const subbackFile = `sample/thread/${nowPrefix}/subject.txt`;
  const kakologFile = `sample/thread/subject.txt`;
  r2._store.set(subbackFile, { text: 'foreign-writer-was-here\n', etag: 'etag-foreign' });
  r2._store.set(kakologFile, { text: 'foreign-kakolog\n', etag: 'etag-foreign-kakolog' });

  const { res, text } = await post(env, { subject: 'firstpost' });
  // 実際にonlyIf.doesNotExistが機能していれば「既にファイルがある」と判定されエラーになるはずだが、
  // 現行v5はエラーにならず、そのまま上書きに成功してしまう。
  assert.equal(res.status, 200);
  assert.equal(errorMessage(text), undefined);
  assert.equal(res.headers.get('x-resnum'), '1');
  // 上書きされ、"foreign-writer-was-here" は跡形もなく消えている。
  assert.doesNotMatch(r2._store.get(subbackFile).text, /foreign-writer-was-here/);
  assert.match(r2._store.get(subbackFile).text, /firstpost/);
});

test('updateSubback: onlyIf.etagMatchesの競合注入時は実際にエラーになる（コンディショナルputが機能していることの検証）', async () => {
  const { env, r2 } = setup();
  // 1本目のスレ立て。同一4桁key帯のsubbackFileが作成される。
  const { res: res1 } = await post(env, { subject: 'conflict1' });
  const key1 = res1.headers.get('x-threadkey');
  assert.equal(res1.headers.get('x-resnum'), '1');

  const prefix = key1.slice(0, 4);
  const subbackFile = `sample/thread/${prefix}/subject.txt`;
  assert.ok(r2._store.has(subbackFile));

  // 秒境界を跨いで2本目のスレのkeyをkey1と異ならせる（同一keyだと後続の投稿がスレッド重複エラーに
  // なってしまい、updateSubbackのetagMatches分岐(=subbackObjが既に存在する場合)まで到達しない）。
  await new Promise(r => setTimeout(r, 1100));

  // 2本目の投稿がsubbackFileを読んだ直後に、別プロセスが同じファイルを書き換えたことを模す。
  // (readとputの間で実際にetagがズレる状況を注入する)
  const originalGet = r2.get.bind(r2);
  let hijacked = false;
  r2.get = async (key) => {
    const result = await originalGet(key);
    if (key === subbackFile && !hijacked) {
      hijacked = true;
      const rec = r2._store.get(key);
      r2._store.set(key, { ...rec, etag: 'etag-conflict-injected' });
    }
    return result;
  };

  const { res: res2, text: text2 } = await post(env, { subject: 'conflict2' });
  // respondErrorはx-threadkeyヘッダを付けないため、実際に使われたkey(=2本目のgb.unixtime)は
  // エラー文言中から取り出す。事前にDate.now()から予測せず、実際の出力から確認する。
  const errMsg = errorMessage(text2);
  const key2 = errMsg?.match(/key:(\d{10})/)?.[1];
  assert.ok(key2, `key2が取得できなかった: ${errMsg}`);
  assert.notEqual(key2, key1); // 秒境界を跨いでkeyが変わっていること
  // onlyIf.etagMatchesが実際に機能していれば、読んだ後にetagが変わっているためputが失敗しエラーになる。
  // このアサーションを外して条件付きロジックを取り除くと（常に無条件putにすると）この投稿は
  // 成功してしまう。つまりここでの失敗は、コンディショナルputが確かに機能している証拠になる。
  assert.equal(res2.status, 200);
  assert.equal(res2.headers.get('x-resnum'), null);
  assert.equal(errMsg, `updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:undefined bbs:sample key:${key2} number:1 errmsg:updateSubback->SubbackFIle_update_conflicted!)`);
});

// ==================== 14. BBS_LIVE (実況板の強制sage) ====================

// preparePosting: `BBS_LIVE=checked` の板では `Math.random() * 10 >= 1` (=Math.random() >= 0.1)
// のときに強制sageされる(10分の9の確率)。乱数を差し替えていないと、テストがどちらの分岐を
// 通ったか制御できず、実質この分岐は未検証のまま(実行するたびランダムに通ったり通らなかったり)
// になってしまう。ここではMath.randomを両側の値に固定し、sage時は板の並び順が変わらず
// headlineも更新されないこと、age時は先頭に移動しheadlineが更新されることを実測して固定する。
test('preparePosting: BBS_LIVEのランダムsage分岐をMath.randomを固定して両側とも検証する', async () => {
  await withFixedTime(1700000000 * 1000, async () => {
    const { env, r2, board } = setup();
    await r2.put('livetest/SETTING.TXT', 'BBS_LIVE=checked\n');

    const { res: resA } = await post(env, { bbs: 'livetest', subject: 'ThreadA', MESSAGE: 'first' });
    const keyA = resA.headers.get('x-threadkey');

    const keyB = await withFixedTime(1700000001 * 1000, async () => {
      const { res: resB } = await post(env, { bbs: 'livetest', subject: 'ThreadB', MESSAGE: 'first' });
      return resB.headers.get('x-threadkey');
    });

    // 実測値: スレ立て直後は新しい方(ThreadB)が先頭に来る。
    assert.equal(
      await board('livetest').getSubject(),
      '1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n1700000000.dat<>ThreadA [ID:zxQ0miZR★] (1)\n'
    );
    assert.equal(r2._store.get('headline.txt').text, '1700000001\nlivetest<>undefined<>1700000001<>1700000001<>first\n');

    const origRandom = Math.random;

    // 閾値以上(0.5 >= 0.1) -> 強制sage。板の並び順は変わらず(indexそのまま更新)、headlineも更新されない。
    await withFixedTime(1700000002 * 1000, async () => {
      Math.random = () => 0.5;
      try {
        const { res } = await post(env, { bbs: 'livetest', key: keyA, MESSAGE: 'reply-high' });
        assert.equal(res.headers.get('x-resnum'), '2');
      } finally { Math.random = origRandom; }
    });
    assert.equal(
      await board('livetest').getSubject(),
      '1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n1700000000.dat<>ThreadA [ID:zxQ0miZR★] (2)\n'
    );
    assert.equal(r2._store.get('headline.txt').text, '1700000001\nlivetest<>undefined<>1700000001<>1700000001<>first\n', 'sage時はheadlineが更新されない');

    // 閾値未満(0.05 < 0.1) -> 通常age。ThreadAが先頭に移動し、headlineも更新される。
    await withFixedTime(1700000003 * 1000, async () => {
      Math.random = () => 0.05;
      try {
        const { res } = await post(env, { bbs: 'livetest', key: keyA, MESSAGE: 'reply-low' });
        assert.equal(res.headers.get('x-resnum'), '3');
      } finally { Math.random = origRandom; }
    });
    assert.equal(
      await board('livetest').getSubject(),
      '1700000000.dat<>ThreadA [ID:zxQ0miZR★] (3)\n1700000001.dat<>ThreadB [ID:zxQ0miZR★] (1)\n'
    );
    assert.equal(r2._store.get('headline.txt').text, '1700000003\nlivetest<>undefined<>1700000003<>1700000000<>reply-low\n', 'age時はheadlineが更新される');

    // ちょうど閾値(Math.random()===0.1)は `0.1*10=1 >= 1` が真になるため、sage側(閾値含む)に入る。
    await withFixedTime(1700000004 * 1000, async () => {
      Math.random = () => 0.1;
      try {
        const { res } = await post(env, { bbs: 'livetest', key: keyB, MESSAGE: 'reply-boundary' });
        assert.equal(res.headers.get('x-resnum'), '2');
      } finally { Math.random = origRandom; }
    });
    assert.equal(
      await board('livetest').getSubject(),
      '1700000000.dat<>ThreadA [ID:zxQ0miZR★] (3)\n1700000001.dat<>ThreadB [ID:zxQ0miZR★] (2)\n',
      'Math.random()===0.1はsage側(閾値ぴったりでも強制sage)'
    );
  });
});
