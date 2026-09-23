// tests/golden/generate-common.mjs
//
// 言語中立な golden 入出力表を src/common.js から生成するスクリプト（Scala.js 移植の検証用）。
// docs/移植方針_step3_ScalaJS.md / CLAUDE.md §3.3 の方針に従い、src/ には一切手を入れず、
// 実際の v5 実装 (src/common.js) を import して実行結果を記録するだけに徹する。
//
// JS の `undefined` は JSON にそのまま出力できないため、他の値と区別できるよう
// 常に `{ "$type": "undefined" }` という専用のタグ付きオブジェクトへ変換する
// （JSON の `null` とも、文字列 `"undefined"` とも異なる第三の表現にする）。
//
// 実行方法: node tests/golden/generate-common.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import {
  sjis, md5, sha1Base64, hash, crypt, translateSaltChar, hexToBinary,
} from '../../src/common.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

// ---- JS 値 -> JSON-safe 値 ----
// undefined は {"$type":"undefined"} という専用タグに変換する。それ以外は再帰的にそのまま。
function toJsonSafe(v) {
  if (v === undefined) return { $type: 'undefined' };
  if (v === null) return null;
  if (Array.isArray(v)) return v.map(toJsonSafe);
  if (typeof v === 'object') {
    const out = {};
    for (const [k, val] of Object.entries(v)) out[k] = toJsonSafe(val);
    return out;
  }
  return v; // string, number, boolean
}

function writeJson(name, data) {
  const path = join(__dirname, name);
  writeFileSync(path, JSON.stringify(data, null, 1) + '\n', 'utf8');
  console.log(`wrote ${path}`);
}

// ============================================================
// 1. sjis: テーブル本体のダンプ（本番データのソース・オブ・トゥルース）
// ============================================================
// s.d の構造をそのまま吐き出す。単バイト側は「値が文字列（葉）」、リードバイト側は
// 「値がオブジェクト（第2バイトへの分岐）」で判別できる（common.js のコンストラクタ参照）。
// 反復順序は元のコンストラクタと同じ順（単バイトは昇順、2バイトはリードバイト昇順→第2バイト昇順）
// にしておく。Scala側はこの順で追記していけば this.e（文字→バイト列）の上書き順序も再現できる。
function dumpSjisTable() {
  const s = new sjis();
  const singleByte = [];
  const doubleByte = [];

  // 単バイト領域: 0x00-0x7F, 0xA1-0xDF（コンストラクタの i<191 の分岐と同じ範囲・順序）
  for (let i = 0; i < 191; i++) {
    const byte = i + (i < 128 ? 0 : 0xa1 - 128);
    const v = s.d[byte];
    if (typeof v === 'string') {
      singleByte.push({ byte, char: v.codePointAt(0) });
    }
    // v が undefined になることは実測上ない（構築ループが必ず埋める）が、
    // 将来の環境差異に備えて何も無ければ単に記録しない。
  }

  // 2バイト領域: リードバイト 0x81-0x9F, 0xE0-0xEF × 第2バイト 0x40-0x7E, 0x80-0xFC
  for (let i = 191; i < 238; i++) {
    const lead = i - 191 + (i - 191 < 31 ? 0x81 : 0xe0 - 31);
    const node = s.d[lead];
    if (typeof node !== 'object' || node === null) continue;
    for (let j = 0; j < 188; j++) {
      const second = j + 0x40 + (62 < j ? 1 : 0);
      const v = node[second];
      if (typeof v === 'string') {
        doubleByte.push({ lead, second, char: v.codePointAt(0) });
      }
    }
  }

  writeJson('sjis-table.json', {
    note: 'src/common.js の sjis クラスが実際に構築した内部テーブル(this.d)の完全ダンプ。' +
      'Scala側の本番実装はこのテーブルをそのまま埋め込み、TextDecoder(\'sjis\')等のホストAPIには依存しない' +
      '（JVM/JS双方で同一バイト列を保証するための意図的な設計判断）。' +
      '単バイト領域は昇順、2バイト領域はリードバイト昇順→第2バイト昇順で並べてあり、' +
      'この順に this.e（文字→バイト列, 後勝ち上書き）を再構築すればJSのthis.eと一致する。',
    singleByte,
    doubleByte,
  });

  return s;
}

// ============================================================
// 2. sjis.encode / sjis.decode: 振る舞い検証用のケース集
// ============================================================
function dumpSjisBehavior(s) {
  const encodeCases = [
    'A', 'あ', 'ア', '亜', 'ｱ', '　', '', 'ABCあいう',
    '①', 'Ⅲ', '㍻', '№', '㈱', // KB-04: NEC特殊文字（実際は変換できる）
    '😀', '👍', // KB-17: テーブル未収録 -> undefined混入
    'A😀あ', // 混在時、該当位置だけundefined
    '�', // 実装上、複数バイト列がこの文字に collide する（this.eは最後に代入された方が勝つ）
    '\uD800', '\uDBFF', '\uDC00', '\uDFFF', 'A\uD800あ', '\uD800\uD800',
  ];
  const encode = encodeCases.map(input => ({
    input: toJsonSafe(input),
    output: toJsonSafe(s.encode(input)),
  }));

  const decodeCases = [
    [], [0x41], [0x82, 0xa0], [0xb1, 0xb2, 0xb3],
    [0x87, 0x40], [0x87, 0x56], [0xed, 0x40], // KB-04
    [0x82], // 末尾不完全 -> 無音で消える
    [0x41, 0x82, 0xa0, 0x82], // 末尾不完全（正常デコード後）
  ];
  const decodeThrowCases = [
    [0xfa, 0x40], [0xfc, 0x4b], // KB-18: リードバイトが範囲外(0xF0-0xFC)
    [0x82, 0xff], // リードバイトは正当だが第2バイトが範囲外
    [0xfa], // 未知のリードバイト単体
  ];

  const decode = [];
  for (const bytes of decodeCases) {
    decode.push({ input: bytes, output: toJsonSafe(s.decode(bytes)), throws: null });
  }
  for (const bytes of decodeThrowCases) {
    let thrown = null;
    try {
      s.decode(bytes);
    } catch (e) {
      thrown = e.constructor.name;
    }
    decode.push({ input: bytes, output: null, throws: thrown });
  }

  writeJson('sjis-encode.json', encode);
  writeJson('sjis-decode.json', decode);
}

// ============================================================
// 3. md5
// ============================================================
function dumpMd5() {
  const inputs = [
    '', 'abc', 'hello world', 'こんにちは', '3ch',
    'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(63), 'a'.repeat(64), 'a'.repeat(65),
    'a'.repeat(0), 'a'.repeat(1), 'a'.repeat(119), 'a'.repeat(120), 'a'.repeat(121),
    '😀', 'password',
    '\uD800', '\uD800abc', 'abc\uDC00', // 孤立サロゲート: TextEncoderはU+FFFD(EF BF BD)に置換する
  ];
  const cases = inputs.map(input => ({ input, output: md5(input) }));
  writeJson('md5.json', cases);
}

// ============================================================
// 4. sha1Base64 / hash（Web Crypto依存。async）
// ============================================================
async function dumpShaAndHash() {
  const inputs = ['', 'abc', 'hello world', 'こんにちは', '3ch', 'password', '😀',
    '\x80\xff', String.fromCharCode(...Array.from({ length: 128 }, (_, i) => i + 128)),
    String.fromCharCode(...new sjis().encode('日本語トリップ'))];
  const sha1base64 = [];
  for (const input of inputs) {
    sha1base64.push({ input, output: await sha1Base64(input) });
  }
  writeJson('sha1base64.json', sha1base64);

  const hashCases = [];
  for (const algo of ['SHA-1', 'SHA-256']) {
    for (const input of inputs) {
      hashCases.push({ algo, input, output: await hash(algo, input) });
    }
  }
  writeJson('hash.json', hashCases);
}

// ============================================================
// 5. translateSaltChar: 0x00-0xFF を全数exhaustiveにダンプ（KB-19）
// ============================================================
function dumpTranslateSaltChar() {
  const cases = [];
  for (let code = 0; code <= 0xff; code++) {
    const c = String.fromCharCode(code);
    const out = translateSaltChar(c);
    cases.push({
      inputCharCode: code,
      output: out === undefined ? { $type: 'undefined' } : { charCode: out.charCodeAt(0) },
    });
  }
  writeJson('translate-salt-char.json', cases);
}

// ============================================================
// 6. hexToBinary
// ============================================================
function dumpHexToBinary() {
  const inputs = ['', '41', '4142', 'ff', 'e29c93', '414', '414243', 'FF', 'Ff', '0'];
  const cases = inputs.map(input => ({
    input,
    // 出力は不可視/非ASCII文字を含み得るバイナリ文字列なので、文字コードの配列として記録する。
    outputCharCodes: [...hexToBinary(input)].map(c => c.charCodeAt(0)),
  }));
  writeJson('hex-to-binary.json', cases);
}

// ============================================================
// 7. crypt: Unix crypt(3) DES
// ============================================================
function dumpCrypt() {
  const cases = [
    // 通常ケース（characterization testの実測値と同じ）
    { pw: 'password', salt: 'ab' },
    { pw: '', salt: '..' },
    { pw: 'test', salt: 'AB' },
    { pw: 'longpasswordmorethan8chars', salt: 'xy' },
    // 8バイト境界: 先頭8バイトが同じなら結果が同一になることを golden 側でも確認できるようにする
    { pw: '12345678', salt: 'ab' },
    { pw: '12345678extra-tail-ignored', salt: 'ab' },
    { pw: '1234567', salt: 'ab' }, // 7バイト（8バイト未満）
    { pw: '', salt: 'ab' }, // 空文字パスワード
    // ソルト文字の内部補正 (>Z, >9 の順) が働くケース
    { pw: 'abc', salt: '\x7b\x7c' },
    { pw: 'abc', salt: 'zz' },
    { pw: 'abc', salt: '99' },
    { pw: 'abc', salt: '..' },
    { pw: 'abc', salt: 'AZ' },
    // マルチバイト文字を含むパスワード（charCodeAt基準でバイト化される点を確認）
    { pw: 'こんにちは', salt: 'ab' },
    // ソルトが2文字未満（実測: salt[k]がundefinedになり、iobufはNUL・Eの入れ替えは発生しない）
    { pw: 'abc', salt: '' },
    { pw: 'abc', salt: 'a' },
  ];
  const out = cases.map(({ pw, salt }) => ({
    password: pw,
    salt,
    output: crypt(pw, salt),
  }));
  writeJson('crypt.json', out);
  writeJson('crypt-bytes.json', cases.map(({ pw, salt }) => ({
    password: pw, salt, output: toJsonSafe(crypt(pw, salt, true)),
  })));
}

// ============================================================
// main
// ============================================================
const s = dumpSjisTable();
dumpSjisBehavior(s);
dumpMd5();
await dumpShaAndHash();
dumpTranslateSaltChar();
dumpHexToBinary();
dumpCrypt();
console.log('done.');
