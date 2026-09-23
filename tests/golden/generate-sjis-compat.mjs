// tests/golden/generate-sjis-compat.mjs
//
// docs/移植方針_step3_ScalaJS.md R-01（案A: encoding-japanese + NEC重複符号の双方向互換
// マッピング、2026-09-23確定）の実装検証スクリプト。
//
// 1) v5 (src/common.js の sjis クラス) の実測テーブル/エンコード表を全数走査し、
//    CP932 重複符号（同じ文字が2箇所にエンコードされる）ペアを「手で決め打ちせず」
//    機械的に洗い出す（tests/golden/sjis-dup-code-table.json に出力）。
// 2) bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala と全く同じアルゴリズムを
//    JSでミラーし、その新実装をv5と全数照合する:
//      - v5テーブル全件(9,027件)の decode 方向
//      - BMP全域(U+0000-U+FFFF)の encode 往復
//      - 絵文字・補助面サンプルの encode 往復
//      - decode方向の2バイト全数スイープ(有効リードバイト×256 + 無効リードバイト代表)
//      - 連結文字列(重複符号文字が隣接するケース)でチャンク境界の破綻がないことの確認
// 3) 残った差異を KB-04 / KB-17 / KB-18 / Node-workerd起因(制御コード) のいずれかに
//    分類し、どれにも該当しない差異(UNEXPECTED)が1件でもあれば異常終了する。
//    最終成果物: tests/golden/sjis-compat-exceptions.json
//
// 実行方法: node tests/golden/generate-sjis-compat.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { sjis } from '../../src/common.js';
import Encoding from 'encoding-japanese';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const v5 = new sjis();

function ejsEncodeArray(text) {
  return Array.from(Encoding.convert(text, { to: 'SJIS', from: 'UNICODE', type: 'array' }));
}
function ejsDecodeString(bytes) {
  return Encoding.convert(bytes, { to: 'UNICODE', from: 'SJIS', type: 'string' });
}
function bytesEq(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}
function encodeOld(text) {
  return Array.from(v5.encode(text)).map(x => (x === undefined ? 0 : x));
}
function decodeOldSafe(bytes) {
  try { return { value: v5.decode(bytes), threw: false }; }
  catch (e) { return { value: null, threw: true }; }
}

// ============================================================
// STEP 1: 重複符号ペアの機械的抽出（決め打ち禁止）
// ============================================================
const tableJson = JSON.parse(readFileSync(join(__dirname, 'sjis-table.json'), 'utf8'));

const decodeMismatches = [];
for (const entry of tableJson.doubleByte) {
  const v5Char = String.fromCodePoint(entry.char);
  let ejsChar, threw = false;
  try { ejsChar = ejsDecodeString([entry.lead, entry.second]); } catch (e) { threw = true; }
  if (threw || ejsChar !== v5Char) {
    decodeMismatches.push({ lead: entry.lead, second: entry.second, v5Char, v5Cp: entry.char, ejsChar: threw ? null : ejsChar, threw });
  }
}

const encodeMismatches = [];
for (const [ch, v5Bytes] of Object.entries(v5.e)) {
  const ejsBytes = ejsEncodeArray(ch);
  if (!bytesEq(v5Bytes, ejsBytes)) {
    let v5DecodeOfEjsBytes = null, v5DecodeThrew = false;
    try { v5DecodeOfEjsBytes = v5.decode(ejsBytes); } catch (e) { v5DecodeThrew = true; }
    encodeMismatches.push({
      char: ch, cp: ch.codePointAt(0), v5Bytes, ejsBytes,
      v5AlsoAcceptsEjsBytesAsSameChar: !v5DecodeThrew && v5DecodeOfEjsBytes === ch,
    });
  }
}

// 真の「重複符号」= v5が2バイトの別位置でも同じ文字として受理するケース。
const genuineDupPairs = encodeMismatches.filter(m =>
  m.v5AlsoAcceptsEjsBytesAsSameChar && m.v5Bytes.length === 2 && m.ejsBytes.length === 2
);

// U+FFFD（置換文字）: v5独自の「未割当位置の衝突」による副産物。genuineDupとは性質が違うが、
// 「v5が正しく変換できていた(=往復一致する)文字」である以上、同じ上書き機構で救済対象にする。
// (mechanically found in encodeMismatches; NOT hand-picked — see report.)
const replacementCharArtifacts = encodeMismatches.filter(m => m.cp === 0xfffd);

const overridePairs = [...genuineDupPairs, ...replacementCharArtifacts].map(m => ({
  char: m.char, cp: m.cp, v5Bytes: m.v5Bytes, ejsBytes: m.ejsBytes,
  kind: m.cp === 0xfffd ? 'replacementCharArtifact' : 'necDuplicateCode',
}));

console.log('genuine CP932 duplicate-code pairs found mechanically:', genuineDupPairs.length);
console.log('replacement-char (U+FFFD) artifact pairs found mechanically:', replacementCharArtifacts.length);
console.log('total override pairs:', overridePairs.length);

const knownNine = ['≒', '≡', '∫', '√', '⊥', '∠', '∵', '∩', '∪'];
const dupChars = new Set(genuineDupPairs.map(d => d.char));
console.log('R-01 known-nine all present in mechanical set:', knownNine.every(c => dupChars.has(c)));
console.log('mechanical genuine-dup set size:', dupChars.size, '(R-01 listed 9; this run also finds any others, e.g. ￢)');

writeFileSync(join(__dirname, 'sjis-dup-code-table.json'), JSON.stringify({
  $schema: 'sjis-dup-code-table.v1',
  note:
    'docs/移植方針_step3_ScalaJS.md R-01 の双方向互換マッピング。v5(src/common.jsのsjisクラス、' +
    'tests/golden/sjis-table.json)の実測テーブルとencode表(v5.e)を全数機械走査して抽出した。' +
    '「9文字決め打ち」ではなく全数比較の結果であり、9文字(necDuplicateCode)に加えて' +
    'replacementCharArtifact(U+FFFD)を含む。bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala に' +
    '同じデータを埋め込んで使用する(このJSONはその生成過程の記録兼監査用データ)。',
  generatedBy: 'tests/golden/generate-sjis-compat.mjs',
  generatedAt: new Date().toISOString(),
  overridePairs,
}, null, 1) + '\n');
console.log('wrote tests/golden/sjis-dup-code-table.json');

// ============================================================
// STEP 2: 新実装(SjisEncoding.scalaと同一アルゴリズム)のJSミラー
// ============================================================
const encodeOverride = new Map(overridePairs.map(p => [p.char, p.v5Bytes]));
const decodeOverride = new Map(overridePairs.map(p => [`${p.v5Bytes[0]},${p.v5Bytes[1]}`, p.char]));

function isHighSurrogate(c) { const x = c.charCodeAt(0); return x >= 0xd800 && x <= 0xdbff; }
function isLowSurrogate(c) { const x = c.charCodeAt(0); return x >= 0xdc00 && x <= 0xdfff; }

function encodeChunkNew(chunk) {
  if (chunk.length === 1 && encodeOverride.has(chunk)) return encodeOverride.get(chunk);
  const bytes = ejsEncodeArray(chunk);
  if (ejsDecodeString(bytes) === chunk) return bytes;
  const cp = chunk.codePointAt(0);
  return ejsEncodeArray(`&#${cp};`);
}
function encodeNew(text) {
  const out = [];
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    const isPair = isHighSurrogate(c) && i + 1 < text.length && isLowSurrogate(text[i + 1]);
    const len = isPair ? 2 : 1;
    out.push(...encodeChunkNew(text.substring(i, i + len)));
    i += len;
  }
  return out;
}
function isLead(b) { return (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc); }
function decodeNew(bytes) {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b = bytes[i];
    if (isLead(b) && i + 1 < bytes.length) {
      const second = bytes[i + 1];
      const key = `${b},${second}`;
      if (decodeOverride.has(key)) { out += decodeOverride.get(key); i += 2; continue; }
      out += ejsDecodeString([b, second]);
      i += 2;
    } else {
      out += ejsDecodeString([b]);
      i += 1;
    }
  }
  return out;
}

// ============================================================
// STEP 3: 全数照合
// ============================================================
const results = {
  overridePairsRoundTrip: { total: 0, diffs: [] },
  tableDecode: { total: 0, diffs: [] },
  bmpEncode: { total: 0, diffs: [] },
  astralEncode: { total: 0, diffs: [] },
  decodeSweep: { total: 0, diffs: [] },
  adjacencyEncode: { total: 0, diffs: [] },
  adjacencyDecode: { total: 0, diffs: [] },
};

// 3.0 上書きペア自体がencode/decode双方向でv5と完全一致することを直接確認
for (const p of overridePairs) {
  results.overridePairsRoundTrip.total++;
  const gotEncode = encodeNew(p.char);
  const gotDecode = decodeNew(p.v5Bytes);
  if (!bytesEq(gotEncode, p.v5Bytes) || gotDecode !== p.char) {
    results.overridePairsRoundTrip.diffs.push({ char: p.char, expectedBytes: p.v5Bytes, gotEncode, gotDecode });
  }
}

// 3.1 v5テーブル全件(doubleByte)のdecode方向
for (const entry of tableJson.doubleByte) {
  results.tableDecode.total++;
  const oldChar = String.fromCodePoint(entry.char);
  let newChar, threw = false;
  try { newChar = decodeNew([entry.lead, entry.second]); } catch (e) { threw = true; }
  if (threw || newChar !== oldChar) {
    results.tableDecode.diffs.push({ lead: entry.lead, second: entry.second, oldChar, oldCp: entry.char, newChar, threw });
  }
}
for (const entry of tableJson.singleByte) {
  results.tableDecode.total++;
  const oldChar = String.fromCodePoint(entry.char);
  let newChar, threw = false;
  try { newChar = decodeNew([entry.byte]); } catch (e) { threw = true; }
  if (threw || newChar !== oldChar) {
    results.tableDecode.diffs.push({ byte: entry.byte, oldChar, oldCp: entry.char, newChar, threw, single: true });
  }
}

// 3.2 BMP全域 encode往復
for (let cp = 0; cp <= 0xffff; cp++) {
  results.bmpEncode.total++;
  const text = String.fromCharCode(cp);
  const oldBytes = encodeOld(text);
  let newBytes, threw = false;
  try { newBytes = encodeNew(text); } catch (e) { threw = true; }
  if (threw || !bytesEq(oldBytes, newBytes)) {
    results.bmpEncode.diffs.push({ cp, char: text, oldBytes, newBytes, threw });
  }
}

// 3.3 補助面サンプル
const astralRanges = [
  [0x1f300, 0x1faff], [0x1d400, 0x1d7ff], [0x20000, 0x20010], [0x2f800, 0x2f810],
];
for (const [start, end] of astralRanges) {
  for (let cp = start; cp <= end; cp++) {
    results.astralEncode.total++;
    const text = String.fromCodePoint(cp);
    const oldBytes = encodeOld(text);
    let newBytes, threw = false;
    try { newBytes = encodeNew(text); } catch (e) { threw = true; }
    if (threw || !bytesEq(oldBytes, newBytes)) {
      results.astralEncode.diffs.push({ cp, char: text, oldBytes, newBytes, threw });
    }
  }
}

// 3.4 decode 2バイト全数スイープ
const validLeadSet = new Set(tableJson.doubleByte.map(e => e.lead));
for (let b = 0; b <= 0xff; b++) {
  results.decodeSweep.total++;
  const o = decodeOldSafe([b]);
  let n, nThrew = false;
  try { n = decodeNew([b]); } catch (e) { nThrew = true; }
  if (o.threw !== nThrew || (!o.threw && o.value !== n)) {
    results.decodeSweep.diffs.push({ bytes: [b], old: o, newThrew: nThrew, newValue: nThrew ? null : n });
  }
}
for (const lead of validLeadSet) {
  for (let second = 0; second <= 0xff; second++) {
    results.decodeSweep.total++;
    const bytes = [lead, second];
    const o = decodeOldSafe(bytes);
    let n, nThrew = false;
    try { n = decodeNew(bytes); } catch (e) { nThrew = true; }
    if (o.threw !== nThrew || (!o.threw && o.value !== n)) {
      results.decodeSweep.diffs.push({ bytes, old: o, newThrew: nThrew, newValue: nThrew ? null : n });
    }
  }
}
for (let lead = 0; lead <= 0xff; lead++) {
  if (tableJson.singleByte.some(e => e.byte === lead) || validLeadSet.has(lead)) continue;
  for (const second of [0x00, 0x40, 0x80, 0xff]) {
    results.decodeSweep.total++;
    const bytes = [lead, second];
    const o = decodeOldSafe(bytes);
    let n, nThrew = false;
    try { n = decodeNew(bytes); } catch (e) { nThrew = true; }
    if (o.threw !== nThrew || (!o.threw && o.value !== n)) {
      results.decodeSweep.diffs.push({ bytes, old: o, newThrew: nThrew, newValue: nThrew ? null : n });
    }
  }
}

// 3.5 隣接性チェック: 重複符号文字を他の文字で挟んでチャンク境界の破綻がないか
// Note: probes intentionally exclude characters with their own known KB-17/KB-18
// divergence (e.g. emoji) -- adjacency here tests chunk-boundary correctness for the
// override table, not those already-covered, already-classified divergences.
const probeChars = ['A', 'あ', 'ア', '①', '纊', '亜'];
for (const p of overridePairs) {
  for (const probe of probeChars) {
    results.adjacencyEncode.total++;
    const text = probe + p.char + probe;
    const oldBytes = encodeOld(text);
    let newBytes, threw = false;
    try { newBytes = encodeNew(text); } catch (e) { threw = true; }
    if (threw || !bytesEq(oldBytes, newBytes)) {
      results.adjacencyEncode.diffs.push({ text, oldBytes, newBytes, threw });
    }
  }
  results.adjacencyDecode.total++;
  const probeBytes = encodeOld('A');
  const bytes = [...probeBytes, ...p.v5Bytes, ...probeBytes];
  const o = decodeOldSafe(bytes);
  let n, nThrew = false;
  try { n = decodeNew(bytes); } catch (e) { nThrew = true; }
  if (o.threw !== nThrew || (!o.threw && o.value !== n)) {
    results.adjacencyDecode.diffs.push({ bytes, old: o, newThrew: nThrew, newValue: nThrew ? null : n });
  }
}

for (const [name, r] of Object.entries(results)) {
  console.log(`${name}: total=${r.total} diffs=${r.diffs.length}`);
}

// ============================================================
// STEP 4: 分類 (KB-04 / KB-17 / KB-18 / controlCodeNodeWorkerdArtifact / UNEXPECTED)
// ============================================================
const IBM_EXT_LEADS = new Set([0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa, 0xfb, 0xfc]);
const CONTROL_CODE_BYTES = new Set([0x1a, 0x1c, 0x7f]); // node-vs-workerd capture artifact (R-06)

function classifyTableDecodeDiff(d) {
  if (d.single) {
    if (CONTROL_CODE_BYTES.has(d.byte)) return 'controlCodeNodeWorkerdArtifact';
    return 'UNEXPECTED';
  }
  if (IBM_EXT_LEADS.has(d.lead)) return 'KB-04';
  if (d.oldChar === '�') return 'KB-18'; // replacement-char hole not in overridePairs (i.e. not the [0xEF,0xFC] winner)
  return 'UNEXPECTED';
}
function classifyEncodeDiff(d) {
  if (CONTROL_CODE_BYTES.has(d.cp)) return 'controlCodeNodeWorkerdArtifact';
  const oldWasNul = d.oldBytes.length === 1 && d.oldBytes[0] === 0 && d.cp !== 0;
  if (oldWasNul) return 'KB-17';
  return 'UNEXPECTED';
}
function classifyDecodeSweepDiff(d) {
  const lead = d.bytes[0];
  if (CONTROL_CODE_BYTES.has(lead) && d.bytes.length === 1) return 'controlCodeNodeWorkerdArtifact';
  if (d.old.threw && d.newThrew === false) {
    if (d.bytes.length === 2 && IBM_EXT_LEADS.has(lead)) return 'KB-04+KB-18';
    return 'KB-18';
  }
  if (!d.old.threw && d.newThrew) return 'UNEXPECTED';
  if (!d.old.threw && d.newThrew === false && d.old.value !== d.newValue) {
    const lastByte = d.bytes[d.bytes.length - 1];
    if (validLeadSet.has(lastByte) && d.newValue === d.old.value + '?') return 'KB-18';
    // v5's Node-captured table maps some JIS X0208-unassigned second-byte positions to the
    // Unicode replacement character (U+FFFD) instead of throwing (ICU TextDecoder behavior).
    // encoding-japanese instead falls back to '?'. Same root cause as the tableDecode
    // "replacement-char hole" case; the one position where this represents a genuinely
    // v5-correct, round-trippable character ([0xEF,0xFC]) is handled via the override table,
    // not here.
    if (d.old.value === '�') return 'KB-18';
    return 'UNEXPECTED';
  }
  return 'UNEXPECTED';
}

function summarize(diffs, classifier) {
  const byCat = new Map();
  const examples = new Map();
  for (const d of diffs) {
    const cat = classifier(d);
    byCat.set(cat, (byCat.get(cat) || 0) + 1);
    if (!examples.has(cat)) examples.set(cat, []);
    if (examples.get(cat).length < 6) examples.get(cat).push(d);
  }
  return { byCat: Object.fromEntries(byCat), examples: Object.fromEntries(examples) };
}

const tableDecodeSummary = summarize(results.tableDecode.diffs, classifyTableDecodeDiff);
const bmpSummary = summarize(results.bmpEncode.diffs, classifyEncodeDiff);
const astralSummary = summarize(results.astralEncode.diffs, classifyEncodeDiff);
const decodeSweepSummary = summarize(results.decodeSweep.diffs, classifyDecodeSweepDiff);

const allByCat = {
  overridePairsRoundTrip: results.overridePairsRoundTrip.diffs.length, // must be 0
  adjacencyEncode: results.adjacencyEncode.diffs.length, // must be 0
  adjacencyDecode: results.adjacencyDecode.diffs.length, // must be 0
  tableDecode: tableDecodeSummary.byCat,
  bmpEncode: bmpSummary.byCat,
  astralEncode: astralSummary.byCat,
  decodeSweep: decodeSweepSummary.byCat,
};
console.log('\n=== classification ===');
console.log(JSON.stringify(allByCat, null, 1));

const unexpectedCount =
  results.overridePairsRoundTrip.diffs.length +
  results.adjacencyEncode.diffs.length +
  results.adjacencyDecode.diffs.length +
  (tableDecodeSummary.byCat.UNEXPECTED || 0) +
  (bmpSummary.byCat.UNEXPECTED || 0) +
  (astralSummary.byCat.UNEXPECTED || 0) +
  (decodeSweepSummary.byCat.UNEXPECTED || 0);

console.log('\nTOTAL UNEXPECTED:', unexpectedCount);

const exceptions = {
  $schema: 'sjis-compat-exceptions.v1',
  note:
    'R-01案A(encoding-japanese + 双方向互換マッピング)適用後もなお残る、v5(src/common.jsの' +
    'sjisクラス)との全差異の確定リスト。tests/golden/generate-sjis-compat.mjsが' +
    '全数照合(v5実表9,027件のdecode、BMP全域+補助面サンプルのencode往復、decode 2バイト全数' +
    'スイープ、上書きペアの隣接文字列)して生成した。UNEXPECTED件数が0であることを' +
    '実装が正しいことの根拠とする。',
  generatedBy: 'tests/golden/generate-sjis-compat.mjs',
  generatedAt: new Date().toISOString(),
  totals: {
    overridePairsRoundTripTotal: results.overridePairsRoundTrip.total,
    tableDecodeTotal: results.tableDecode.total,
    bmpEncodeTotal: results.bmpEncode.total,
    astralEncodeSampleTotal: results.astralEncode.total,
    decodeSweepTotal: results.decodeSweep.total,
    adjacencyEncodeTotal: results.adjacencyEncode.total,
    adjacencyDecodeTotal: results.adjacencyDecode.total,
  },
  unexpectedCount,
  categories: {
    'KB-04': {
      description: 'IBM拡張行(リードバイト0xF0-0xFC)はv5のテーブルに一切存在しない。新実装はencoding-japaneseの標準テーブル経由でこれらを解釈できてしまうため、v5(未対応)との差異になる。',
      tableDecodeCount: tableDecodeSummary.byCat['KB-04'] || 0,
    },
    'KB-04+KB-18': {
      description: 'IBM拡張行(0xF0-0xFC)の2バイト文字。v5は未知のリードバイトとしてTypeErrorを送出するが、新実装はKB-18の対応方針(フォールバック)に従い文字を返す。',
      decodeSweepCount: decodeSweepSummary.byCat['KB-04+KB-18'] || 0,
    },
    'KB-17': {
      description: 'v5のテーブルに存在しないUnicodeコードポイント(絵文字・補助面文字等)。v5はNULバイト1個になるが、新実装はNCR(&#codepoint;)にフォールバックする。',
      bmpEncodeCount: bmpSummary.byCat['KB-17'] || 0,
      astralEncodeSampleCount: astralSummary.byCat['KB-17'] || 0,
    },
    'KB-18': {
      description: 'v5が未知の第2バイト/末尾の不完全なリードバイトに遭遇した場合(TypeError送出、または無音破棄)、新実装は例外を投げずフォールバック文字を返す。加えて、v5のテーブル構築元(Node TextDecoder)がJIS X0208未定義の第2バイト位置をUnicode置換文字U+FFFDに丸め込んでいた「穴」(上書きペア[0xEF,0xFC]以外の約1,500件)も同じ理由でここに含まれる。',
      tableDecodeCount: tableDecodeSummary.byCat['KB-18'] || 0,
      decodeSweepCount: decodeSweepSummary.byCat['KB-18'] || 0,
    },
    controlCodeNodeWorkerdArtifact: {
      description: '制御コード0x1A/0x1C/0x7Fの3者。v5の実表はNodeのTextDecoder(\'sjis\')で構築したものだが、Node と本番Workers(workerd)のTextDecoder(\'sjis\')はこの3バイトについてのみ異なる文字を返す(tests/golden/sjis-runtime-differences.json)。workerdは新実装(encoding-japanese)と同じ素通し(恒等)変換であり、本番のv5と新実装の間には実際には差異が無い。Node実測を正としたsjis-table.jsonにのみ現れる見かけ上の差異。',
      tableDecodeCount: tableDecodeSummary.byCat['controlCodeNodeWorkerdArtifact'] || 0,
      decodeSweepCount: decodeSweepSummary.byCat['controlCodeNodeWorkerdArtifact'] || 0,
    },
  },
};

writeFileSync(join(__dirname, 'sjis-compat-exceptions.json'), JSON.stringify(exceptions, null, 1) + '\n');
console.log('wrote tests/golden/sjis-compat-exceptions.json');

if (unexpectedCount > 0) {
  console.error(`\nFAILED: ${unexpectedCount} unexpected (unclassified) divergences found.`);
  process.exitCode = 1;
} else {
  console.log('\nOK: no unexpected divergences.');
}
