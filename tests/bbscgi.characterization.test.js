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
// onlyIf.etagMatches / onlyIf.doesNotExist を使った条件付き put に依存しているため、これが無いと
// 成功するはずの投稿までエラーになってしまう。
function makeR2() {
  const store = new Map(); let etagCounter = 0;
  return {
    async get(key) { const rec = store.get(key); if (!rec) return null; return { text: async () => rec.text, etag: rec.etag }; },
    async put(key, value, opts = {}) {
      const rec = store.get(key);
      if (opts.onlyIf) {
        if (opts.onlyIf.doesNotExist && rec) return { uploaded: false };
        if (opts.onlyIf.etagMatches && (!rec || rec.etag !== opts.onlyIf.etagMatches)) return { uploaded: false };
      }
      const etag = 'etag' + (++etagCounter);
      store.set(key, { text: value, etag });
      return { uploaded: true };
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
  const { env } = setup();
  const { res } = await post(env, { FROM: '名前太郎', mail: '', MESSAGE: 'テスト本文', subject: 'たておき' });
  const key = res.headers.get('x-threadkey');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const lines = dat.split('\n').filter(Boolean);
  assert.equal(lines.length, 1);
  const fields = lines[0].split('<>');
  assert.equal(fields.length, 5);
  assert.equal(fields[1], ''); // メールは常に空
  assert.match(fields[2], /^\d{4}\/\d{2}\/\d{2}\(.\) \d{2}:\d{2}:\d{2} ID:[A-Za-z0-9+/]{8}$/);
  assert.equal(fields[3], ' テスト本文 '); // 本文の前後に必ず半角スペース
  const id = fields[2].match(/ID:([A-Za-z0-9+/]{8})$/)[1];
  assert.equal(fields[4], `たておき [ID:${id}★]`); // 1レス目はスレタイ末尾に[ID:xxxx★]が付く
  assert.match(fields[0], /^名前太郎 <\/b>\(JP\.[A-Za-z0-9+/]{2}\.[A-Za-z0-9+/]{2} [A-Za-z0-9+/]{2}-[0-9a-f]{16}\)<b>$/);
});

test('dat行フォーマット: 返信(>>2)はメールと5番目フィールド(タイトル)が空', async () => {
  const { env } = setup();
  const { res: res1 } = await post(env, { subject: 'たておき2' });
  const key = res1.headers.get('x-threadkey');
  const { res: res2 } = await post(env, { key, subject: '', mail: '', MESSAGE: '2番目の返信' });
  assert.equal(res2.headers.get('x-resnum'), '2');
  const dat = await env.THREAD_DO.get('sample/' + key).getDat();
  const lines = dat.split('\n').filter(Boolean);
  assert.equal(lines.length, 2);
  const fields = lines[1].split('<>');
  assert.equal(fields.length, 5);
  assert.equal(fields[1], ''); // メール欄は常に空
  assert.equal(fields[3], ' 2番目の返信 ');
  assert.equal(fields[4], ''); // 2レス目以降はタイトルなし
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

test('makeIdAndShikibetuId: 同じ入力(IP・AS・地域・UA・時刻窓)なら同じIDと識別IDが再現される（決定性）', async () => {
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
