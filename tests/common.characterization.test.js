import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  sjis, md5, sha1Base64, hash, crypt, translateSaltChar, hexToBinary,
  respondPlainText, respond404, respond500,
} from '../src/common.js';

// ---- helpers ----
const bytes = async res => new Uint8Array(await res.arrayBuffer());
const text = async res => Buffer.from(await res.arrayBuffer()).toString('utf8');

// ============================================================
// sjis: Shift_JIS encode/decode (hand-rolled table)
// ============================================================
test('sjis.encode: ASCII・かな・カナ・漢字・半角カナは正しいSJISバイト列になる', () => {
  const s = new sjis();
  assert.deepEqual(s.encode('A'), [0x41]);
  assert.deepEqual(s.encode('あ'), [0x82, 0xa0]);
  assert.deepEqual(s.encode('ア'), [0x83, 0x41]);
  assert.deepEqual(s.encode('亜'), [0x88, 0x9f]);
  assert.deepEqual(s.encode('ｱ'), [0xb1]); // 半角カナ(1バイト)
  assert.deepEqual(s.encode('　'), [0x81, 0x40]); // 全角スペース
});

test('sjis.decode: encodeしたバイト列は元の文字列に復元できる', () => {
  const s = new sjis();
  for (const ch of ['A', 'あ', 'ア', '亜', 'ｱ', '　']) {
    assert.equal(s.decode(s.encode(ch)), ch);
  }
  assert.equal(s.decode([0xb1, 0xb2, 0xb3]), 'ｱｲｳ');
});

test('KB-04: sjis: NEC特殊文字(①,Ⅲ)は実際にはテーブルに含まれ変換できる（仕様書の「文字化け・脱落」記述と矛盾）', () => {
  // 仕様書 §10.1 / §11 は「CP932の機種依存文字(①、Ⅲ)で文字化け・脱落が発生する」とするが、
  // 実際にはリードバイト0x81-0x9F,0xE0-0xEFの範囲（NEC選定IBM拡張文字含む）はテーブル構築時にカバーされ、
  // ①やⅢは正しくエンコード/デコードされる。
  const s = new sjis();
  assert.deepEqual(s.encode('①'), [0x87, 0x40]);
  assert.equal(s.decode([0x87, 0x40]), '①');
  assert.deepEqual(s.encode('Ⅲ'), [0x87, 0x56]);
  assert.equal(s.decode([0x87, 0x56]), 'Ⅲ');
  // NEC選定IBM拡張文字(row ED, リードバイト0xE0-0xEF内)も同様にカバーされる
  assert.equal(s.decode([0xed, 0x40]), '纊');
});

test('KB-17: sjis.encode: 絵文字などテーブル未収録の文字はundefinedを混入させる（バグ）', () => {
  // テーブルに存在しない文字は this.e[x] が undefined を返し、
  // flatMap がそれをそのまま1要素として残すため、結果配列に undefined が混入する。
  const s = new sjis();
  assert.deepEqual(s.encode('😀'), [undefined]);
  assert.deepEqual(s.encode('👍'), [undefined]);
  // Uint8Array.from(...) に渡すと undefined は 0 (NUL) に変換される（呼び出し側での実際の挙動）
  assert.deepEqual(Uint8Array.from(s.encode('😀')), Uint8Array.from([0]));
  // 文字列中に混在した場合も該当位置だけ undefined になる
  const enc = s.encode('A😀あ');
  assert.deepEqual(enc, [0x41, undefined, 0x82, 0xa0]);
});

test('KB-18: sjis.encode: 真のIBM拡張文字域(リードバイト0xF0-0xFC)はテーブル構築range外のため対応する文字がそもそも存在しない', () => {
  // テーブル構築ループはリードバイト 0x81-0x9F と 0xE0-0xEF のみを生成し、0xF0-0xFC (IBM拡張漢字) は生成されない。
  const s = new sjis();
  // decode時、テーブルに無いリードバイトを渡すと例外がスローされる（グレースフルな処理ではない）
  assert.throws(() => s.decode([0xfa, 0x40]), TypeError);
  assert.throws(() => s.decode([0xfc, 0x4b]), TypeError);
});

test('sjis.decode: 不完全な2バイト文字列は例外を投げるか無音で消える', () => {
  const s = new sjis();
  // 末尾で2バイト目が欠けている場合は無音でデコードされず消える（例外なし）
  assert.equal(s.decode([0x82]), '');
  assert.equal(s.decode([0x41, 0x82, 0xa0, 0x82]), 'Aあ');
  // リード バイトは正当だが2バイト目が未知の組み合わせだと例外をスローする
  assert.throws(() => s.decode([0x82, 0xff]), TypeError);
  // 未知のリードバイト単体でも例外をスローする
  assert.throws(() => s.decode([0xfa]), TypeError);
});

// ============================================================
// md5: hand-rolled MD5 (matches standard MD5 for these inputs)
// ============================================================
test('md5: ASCII・空文字列・マルチバイトは標準MD5と一致する', () => {
  // 実測値: Node組み込みcrypto (crypto.createHash('md5')) との比較で一致を確認済み。
  assert.equal(md5(''), 'd41d8cd98f00b204e9800998ecf8427e');
  assert.equal(md5('abc'), '900150983cd24fb0d6963f7d28e17f72');
  assert.equal(md5('hello world'), '5eb63bbbe01eeed093cb22bb8f5acdc3');
  assert.equal(md5('こんにちは'), 'c0e89a293bd36c7a768e4e9d2c5475a8');
  assert.equal(md5('3ch'), 'c774850af125a2d9bdd1b61f90efe2b6');
});

test('md5: ブロック境界(56/64バイト前後)でも標準MD5と一致する', () => {
  assert.equal(md5('a'.repeat(55)), 'ef1772b6dff9a122358552954ad0df65');
  assert.equal(md5('a'.repeat(56)), '3b0c8ac703f828b04c6c197006d17218');
  assert.equal(md5('a'.repeat(63)), 'b06521f39153d618550606be297466d5');
  assert.equal(md5('a'.repeat(64)), '014842d480b571495a4a0363793f7367');
  assert.equal(md5('a'.repeat(65)), 'c743a45e0d2e6a95cb859adae0248435');
});

// ============================================================
// sha1Base64 / hash
// ============================================================
test('sha1Base64: SHA-1のBase64を先頭27文字にスライスした値を返す', async () => {
  assert.equal(await sha1Base64(''), '2jmj7l5rSw0yVb/vlWAYkK/YBwk');
  assert.equal(await sha1Base64('abc'), 'qZk+NkcGgWq6PiVxeFDCbJzQ2J0');
  assert.equal(await sha1Base64('こんにちは'), 'IEJ6cIw/bwfPEqsjVXmC2ebSO2E');
});

test('hash: 16進文字列(小文字, ゼロパディング)を返す', async () => {
  const sha256 = await hash('SHA-256', 'test');
  assert.equal(sha256, '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08');
  assert.equal(typeof sha256, 'string');
  assert.match(sha256, /^[0-9a-f]+$/);
  assert.equal(sha256.length, 64);
  const sha1 = await hash('SHA-1', 'test');
  assert.equal(sha1, 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3');
});

// ============================================================
// translateSaltChar
// ============================================================
test('KB-19: translateSaltChar: 剰余演算の優先順位バグにより、変換対象の記号は軒並みundefinedになる（仕様書との重大な相違）', () => {
  // 仕様書 §6.1 は ":;<=>?@[\]^_" -> "ABCDEFGabcdef" の変換を主張するが、
  // 実装は `table[(code - base) % table.length]` の意図に対し
  // 実際は演算子優先順位により `table[code - (base % table.length)]` として評価される
  // （かつ table 自体も "ABCDEFGabcdef" ではなく "A-Ga-f./" というリテラル文字列）。
  // 結果、対象範囲のインデックスは table の範囲(0-7)を超え、すべて undefined を返す。
  for (const c of [':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_']) {
    assert.equal(translateSaltChar(c), undefined);
  }
  // 0x7B-0xFF の範囲(バッククォート直後～)も同じバグの影響を受け、'~'や'}' も undefined になる
  assert.equal(translateSaltChar('~'), undefined);
  assert.equal(translateSaltChar('\xff'), undefined);
});

test('translateSaltChar: 制御文字コード0-7のみ偶然テーブル"A-Ga-f./"のインデックスと一致し文字を返す', () => {
  // これも0x00-0x2Dブロックの一部が偶然 (code - 0) % 8 の範囲内(0-7)に収まるために起きる副作用であり、意図した動作ではない。
  const table = 'A-Ga-f./';
  for (let code = 0; code <= 7; code++) {
    assert.equal(translateSaltChar(String.fromCharCode(code)), table[code]);
  }
  // 同ブロックのcode 8以降はundefinedに戻る
  assert.equal(translateSaltChar(String.fromCharCode(8)), undefined);
  assert.equal(translateSaltChar(String.fromCharCode(45)), undefined);
});

test('translateSaltChar: 対象外の文字（英数字や記号）はそのまま返す', () => {
  // '~' (0x7E) は 0x7B-0xFF の変換対象範囲に含まれるため、ここでは含めない（別テストでundefinedになることを確認）。
  for (const c of ['A', 'a', '0', '9', '.', '/']) {
    assert.equal(translateSaltChar(c), c);
  }
});

// ============================================================
// hexToBinary
// ============================================================
test('hexToBinary: 16進文字列をバイナリ文字列(1文字=1バイト)に変換する', () => {
  assert.equal(hexToBinary(''), '');
  assert.equal(hexToBinary('41'), 'A');
  assert.equal(hexToBinary('4142'), 'AB');
  assert.deepEqual([...hexToBinary('ff')].map(c => c.charCodeAt(0)), [0xff]);
  assert.deepEqual([...hexToBinary('e29c93')].map(c => c.charCodeAt(0)), [0xe2, 0x9c, 0x93]);
});

test('hexToBinary: 奇数長の入力は末尾の半端な1文字を切り捨てる', () => {
  // Array.from({length: hex.length/2}) が小数長を切り捨てるため
  assert.equal(hexToBinary('414'), 'A');
});

// ============================================================
// crypt: Unix crypt(3) DES
// ============================================================
test('crypt: 通常のソルトで安定したハッシュを返す（実測値を固定）', () => {
  assert.equal(crypt('password', 'ab'), 'abJnggxhB/yWI');
  assert.equal(crypt('', '..'), '..X8NBuQ4l6uQ');
  assert.equal(crypt('test', 'AB'), 'ABwOg1D2JDxIQ');
  assert.equal(crypt('longpasswordmorethan8chars', 'xy'), 'xy7XrDVdSINMQ');
});

test('crypt: 出力先頭2文字は常にソルトそのもの（内部変換後の先頭ソルト文字がiobuf[1]==0のケースを含む）', () => {
  const out = crypt('password', 'ab');
  assert.equal(out.slice(0, 2), 'ab');
  assert.equal(out.length, 13);
});

test('crypt: 内部でソルト文字を(>Z, >9)の順に補正する処理が実際に働く', () => {
  // salt charCode('{')=0x7b > 'Z'(0x5a) なので内部で -6、さらに>'9'なので-7、というcrypt(3)独自の変換が走る。
  // ここでは実測値を固定してその変換結果を凍結する。
  assert.equal(crypt('abc', '\x7b\x7c'), '{|GMstYGoUC76');
});

// ============================================================
// respondPlainText
// ============================================================
test('respondPlainText: UTF-8プレーンテキスト（type=plain）はcharset=UTF-8とLast-Modified/Accept-Rangesを持つ', async () => {
  const res = respondPlainText('hello日本語', 'plain');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.ok(res.headers.get('last-modified'));
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.equal(await text(res), 'hello日本語');
});

test('respondPlainText: Shift_JISプレーンテキスト（type=plain）はcharset=Shift_JISで生バイトを返す', async () => {
  const s = new sjis();
  const res = respondPlainText('あいう', 'plain', 'Shift_JIS');
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
  assert.deepEqual(await bytes(res), Uint8Array.from(s.encode('あいう')));
});

test('respondPlainText: type!=="plain"の場合はLast-Modified/Accept-Rangesを付与しない（UTF-8/Shift_JIS双方）', async () => {
  const resUtf8 = respondPlainText('<p>hi</p>', 'html');
  assert.equal(resUtf8.headers.get('content-type'), 'text/html;charset=UTF-8');
  assert.equal(resUtf8.headers.get('last-modified'), null);
  assert.equal(resUtf8.headers.get('accept-ranges'), null);

  const resSjis = respondPlainText('<p>hi</p>', 'html', 'Shift_JIS');
  assert.equal(resSjis.headers.get('content-type'), 'text/html;charset=Shift_JIS');
  assert.equal(resSjis.headers.get('last-modified'), null);
});

test('KB-20: respondPlainText: content-typeのセミコロン前後スペースはUTF-8のRangeレスポンスのみ不統一（実装の綴りのばらつきを凍結）', async () => {
  // 通常時(UTF-8): "text/plain;charset=UTF-8" （スペース無し）
  // Range時(UTF-8): "text/plain; charset=UTF-8" （スペース有り） ← 綴りが違う
  // Range時(Shift_JIS): "text/plain;charset=Shift_JIS" （スペース無し、通常時と同じ）
  const normal = respondPlainText('0123456789', 'plain', 'UTF-8');
  const ranged = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=0-3');
  assert.equal(normal.headers.get('content-type'), 'text/plain;charset=UTF-8');
  assert.equal(ranged.headers.get('content-type'), 'text/plain; charset=UTF-8');
  const rangedSjis = respondPlainText('あいう', 'plain', 'Shift_JIS', 'bytes=0-1');
  assert.equal(rangedSjis.headers.get('content-type'), 'text/plain;charset=Shift_JIS');
});

test('respondPlainText: Range(開区間 bytes=N-)はNから末尾までを206で返す', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=3-');
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 3-9/10');
  assert.equal(res.headers.get('content-length'), '7');
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.equal(await text(res), '3456789');
});

test('respondPlainText: Range(閉区間 bytes=N-M)は指定範囲のみ206で返す', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=2-5');
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 2-5/10');
  assert.equal(res.headers.get('content-length'), '4');
  assert.equal(await text(res), '2345');
});

test('respondPlainText: end が総バイト数を超える場合は総バイト数-1に切り詰められる', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=5-100');
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 5-9/10');
});

test('KB-21: respondPlainText: サフィックス形式(bytes=-N)は「末尾Nバイト」ではなくstart=0として解釈される（HTTP仕様との相違）', async () => {
  // RFC 7233 の suffix-byte-range-spec (bytes=-500 は末尾500バイト) を実装しておらず、
  // 正規表現 `^bytes=(\d*)-(\d*)$` の第1グループが空文字になり start は既定の0にフォールバックする。
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=-5');
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 0-5/10');
  assert.equal(await text(res), '012345');
});

test('respondPlainText: start>=totalは416でContent-Range: bytes */{total}を返す', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=10-20');
  assert.equal(res.status, 416);
  assert.equal(res.headers.get('content-range'), 'bytes */10');
  assert.equal(await text(res), 'Requested Range Not Satisfiable');
});

test('respondPlainText: start>endも416でContent-Range: bytes */{total}を返す', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'bytes=5-2');
  assert.equal(res.status, 416);
  assert.equal(res.headers.get('content-range'), 'bytes */10');
});

test('respondPlainText: 書式が不正なRangeヘッダは400でボディ"Bad Range Header"を返す', async () => {
  const res = respondPlainText('0123456789', 'plain', 'UTF-8', 'not-a-range');
  assert.equal(res.status, 400);
  assert.equal(await text(res), 'Bad Range Header');
});

test('respondPlainText: Shift_JISでもRange/416/400が同様に機能し、マルチバイト境界を無頓着に切る', async () => {
  const s = new sjis();
  const total = s.encode('あいうえお').length; // 10 bytes (2 bytes/char)
  assert.equal(total, 10);
  // 文字境界を無視して2バイト目だけを切り出す（「あ」の後半バイトのみ）
  const res = respondPlainText('あいうえお', 'plain', 'Shift_JIS', 'bytes=1-2');
  assert.equal(res.status, 206);
  assert.equal(res.headers.get('content-range'), 'bytes 1-2/10');
  const got = await bytes(res);
  assert.deepEqual(got, Uint8Array.from([0xa0, 0x82])); // 「あ」の2バイト目 + 「い」の1バイト目
});

test('respondPlainText: If-Modified-SinceがlastModified以降なら304・ボディなしを返す', async () => {
  const lastModified = new Date('2024-01-01T00:00:00Z');
  const res = respondPlainText('hello', 'plain', 'UTF-8', '', lastModified, new Date('2024-06-01T00:00:00Z').toUTCString());
  assert.equal(res.status, 304);
  assert.equal(res.headers.get('last-modified'), lastModified.toUTCString());
  assert.equal((await bytes(res)).length, 0);
});

test('respondPlainText: If-Modified-SinceとlastModifiedが完全一致でも304を返す（<=判定）', async () => {
  const lastModified = new Date('2024-01-01T00:00:00Z');
  const res = respondPlainText('hello', 'plain', 'UTF-8', '', lastModified, lastModified.toUTCString());
  assert.equal(res.status, 304);
});

test('respondPlainText: If-Modified-SinceがlastModifiedより前なら304にならず通常応答する', async () => {
  const lastModified = new Date('2024-01-01T00:00:00Z');
  const res = respondPlainText('hello', 'plain', 'UTF-8', '', lastModified, new Date('2023-01-01T00:00:00Z').toUTCString());
  assert.equal(res.status, 200);
});

test('KB-05: respondPlainText: addHeaderで渡した値はそのままヘッダ名として設定される（Cache-Controlへの正規化はしない=既知の不具合）', async () => {
  // 仕様書 §11: 呼び出し側が headers["max-age"]="5" のように渡すため、
  // ここでは respondPlainText がそれを素通しし、正式な Cache-Control ヘッダには変換しないことを凍結する。
  const res = respondPlainText('hello', 'plain', 'UTF-8', '', new Date(), '', { 'max-age': '5', 'X-Custom': 'abc' });
  assert.equal(res.headers.get('max-age'), '5');
  assert.equal(res.headers.get('cache-control'), null);
  assert.equal(res.headers.get('x-custom'), 'abc');
});

// ============================================================
// respond404 / respond500
// ============================================================
test('respond404: 404・text/html;charset=UTF-8で、pathnameを本文にエスケープなしで埋め込む', async () => {
  const res = respond404('/test/read.cgi/foo/123');
  assert.equal(res.status, 404);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  const body = await text(res);
  assert.match(body, /Error 404/);
  assert.match(body, /\/test\/read\.cgi\/foo\/123/);
});

test('KB-22: respond404: pathnameに含まれるHTMLタグはエスケープされず素通しする（XSSに繋がりうる既知の挙動）', async () => {
  const res = respond404('<script>1</script>');
  const body = await text(res);
  assert.match(body, /<script>1<\/script>/);
});

test('respond500: 500・text/html;charset=UTF-8で、errorMsgを本文にそのまま埋め込む', async () => {
  const res = respond500('Something broke: <b>bad</b>');
  assert.equal(res.status, 500);
  assert.equal(res.headers.get('content-type'), 'text/html;charset=UTF-8');
  const body = await text(res);
  assert.match(body, /Internal Server Error/);
  assert.match(body, /Something broke: <b>bad<\/b>/);
});
