// SjisEncoding perf regression guard.
//
// bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala used to call `encoding-japanese` once per
// character (or per SJIS byte-unit for decode). That is fine for correctness but catastrophic for
// throughput: /{bbs}/dat/{key}.dat re-encodes an entire thread's dat to Shift_JIS on every request,
// and 2ch専ブラ clients poll that endpoint continuously. A 1,000-response thread burned roughly
// 1 second of CPU per fetch under the old implementation (measured below).
//
// This script benchmarks v6 (SjisEncoding.encode/decode via the compiled Scala.js `server.js`)
// against v5 (`src/common.js`'s `sjis` class) on realistic dat content at three sizes, and asserts
// byte-for-byte equality between v5 and v6 so a "fast but wrong" regression can't slip in silently.
//
// Usage: node --import ./tests/golden/register-scala.mjs tests/golden/sjis-bench.mjs
// (the --import hook stubs `cloudflare:workers` for the compiled server.js; see register-scala.mjs)
//
// This is a manual/CI perf guard, not a `node --test` file: it prints timings for humans and only
// throws (non-zero exit) on an actual correctness mismatch, so it won't flake on machine-speed
// variance the way a hard latency assertion would.

import assert from 'node:assert/strict';
import { sjis } from '../../src/common.js';

const { sjisEncode, sjisDecode } = await import(
  new URL('../../bbs/target/scala-3.3.8/bbs-fastopt/server.js', import.meta.url).href
);

// ---- deterministic pseudo-random realistic dat content -------------------------------------

// A small pool of everyday Japanese sentence fragments (hiragana/katakana/kanji/punctuation),
// plus ASCII and half-width kana, so encode/decode exercise the full mix a real dat contains.
const FRAGMENTS = [
  '今日は天気がいいですね', 'それな', 'マジで', '草生える', 'そうだね', 'わかる',
  'これはひどい', 'なるほど', 'よろしくお願いします', 'ありがとうございます',
  '半角ｶﾀｶﾅもたまに混ざる', 'www', '（笑）', '≡√∠こういう記号もたまに出る',
  'ここに絵文字😀が来ることもある', 'ID:abcdefg', 'age', 'sage', '2026/09/23(水) 12:34:56.78',
  'ダミーテキストダミーテキスト', '本当にそう思う', 'それってあなたの感想ですよね',
  'https://example.com/path?q=1', '複数行になることもある<br>こんな感じで',
];
// Note: deliberately no astral-plane characters (e.g. emoji) here. Those are KB-17/KB-18
// territory (v5 outputs a NUL byte for unmappable characters; v6 intentionally diverges and
// falls back to a numeric character reference per docs/移植方針_step3_ScalaJS.md R-01). That is
// an accepted, already-tested divergence (see SjisEncodingSuite), not something this perf-focused
// benchmark should be asserting byte-equality over. Keeping fragments within v5's convertible set
// makes the v5/v6 byte-equality check below meaningful.

function mulberry32(seed) {
  let a = seed;
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// includeUnmappable=false は、v5 が Shift_JIS に変換できない文字（絵文字）を除いたコーパスを作る。
// 等価性の検査用。KB-17 により v5=NUL / v6=NCR と意図的に食い違うため、そこを含めて比較はできない。
function buildDat(lineCount, seed = 1, { includeUnmappable = true } = {}) {
  const rnd = mulberry32(seed);
  const pool = includeUnmappable ? FRAGMENTS : FRAGMENTS.filter(f => [...f].every(c => c.codePointAt(0) <= 0xffff));
  const pick = () => pool[Math.floor(rnd() * pool.length)];
  const lines = [];
  for (let i = 0; i < lineCount; i++) {
    const name = i % 7 === 0 ? '名無しさん' : '';
    const mail = i % 11 === 0 ? 'sage' : '';
    const date = `2026/09/23(水) ${String(10 + (i % 12)).padStart(2, '0')}:${String(i % 60).padStart(2, '0')}:${String((i * 7) % 60).padStart(2, '0')}.${String(i % 100).padStart(2, '0')} ID:${(i * 2654435761 >>> 0).toString(36)}`;
    const sentenceCount = 2 + (i % 4);
    const message = Array.from({ length: sentenceCount }, pick).join('<br>');
    const title = i === 0 ? 'ベンチマーク用スレッドタイトルです' : '';
    lines.push(`${name}<>${mail}<>${date}<>${message}<>${title}`);
  }
  return lines.join('\n') + '\n';
}

// ---- v5 encode/decode (src/common.js) -------------------------------------------------------

function v5Encode(text) {
  const encoder = new sjis();
  return Uint8Array.from(encoder.encode(text));
}

function v5Decode(bytes) {
  const decoder = new sjis();
  return decoder.decode(Array.from(bytes));
}

// ---- v6 encode/decode (compiled Scala.js) ----------------------------------------------------

function v6Encode(text) {
  return sjisEncode(text);
}

function v6Decode(bytes) {
  return sjisDecode(bytes);
}

function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function timeOnce(fn) {
  const start = performance.now();
  const result = fn();
  const ms = performance.now() - start;
  return { result, ms };
}

const SIZES = [
  { label: '19 KB (100 lines)', lines: 100 },
  { label: '189 KB (1,000 lines)', lines: 1000 },
  { label: '947 KB (5,000 lines)', lines: 5000 },
];

console.log('SjisEncoding perf guard: v5 (src/common.js) vs v6 (Scala.js SjisEncoding)\n');
console.log(
  '| dat size | v5 encode | v6 encode | ratio | v5 decode | v6 decode | ratio | bytes match |',
);
console.log('| :-- | --: | --: | --: | --: | --: | --: | :--: |');

let anyMismatch = false;

for (const { label, lines } of SIZES) {
  const text = buildDat(lines, lines /* seed */);

  // warm up both implementations once before timing so JIT warmup doesn't dominate the first row.
  const warmBytesV5 = v5Encode(text);
  const warmBytesV6 = v6Encode(text);

  // 等価性の検査は「絵文字を除いたコーパス」に対して行う。
  // v5 と v6 は絵文字など変換不能な文字で *意図的に* 異なる（KB-17: v5 は NUL バイト 0x00、
  // v6 は NCR `&#128512;`。docs/移植方針_step3_ScalaJS.md R-01 の案A で決めた差異）。
  // text 側には絵文字を残したまま計時する — NCR フォールバック経路の性能も測る必要があるため。
  // ここで text をそのまま突き合わせると、仕様どおりの差異で必ず落ちる。
  const equalityText = buildDat(lines, lines, { includeUnmappable: false });
  assert.ok(bytesEqual(v5Encode(equalityText), v6Encode(equalityText)), `encode mismatch at ${label}`);

  const { ms: v5EncodeMs, result: v5Bytes } = timeOnce(() => v5Encode(text));
  const { ms: v6EncodeMs, result: v6Bytes } = timeOnce(() => v6Encode(text));

  const { ms: v5DecodeMs } = timeOnce(() => v5Decode(v5Bytes));
  const { ms: v6DecodeMs } = timeOnce(() => v6Decode(v6Bytes));

  // 一致判定は計時用コーパス（絵文字入り）ではなく equalityText 側で行う。
  // 計時用は KB-17 の仕様どおりの差異を必ず含むため、判定に使うと常に MISMATCH になる。
  const eqV5 = v5Encode(equalityText);
  const eqV6 = v6Encode(equalityText);
  const encodeMatch = bytesEqual(eqV5, eqV6);
  const decodeMatch = v5Decode(eqV5) === v6Decode(eqV6);

  if (!encodeMatch || !decodeMatch) anyMismatch = true;

  const ratio = (v6EncodeMs / v5EncodeMs).toFixed(1);
  const decodeRatio = (v6DecodeMs / v5DecodeMs).toFixed(1);
  console.log(
    `| ${label} | ${v5EncodeMs.toFixed(1)} ms | ${v6EncodeMs.toFixed(1)} ms | ${ratio}x | ` +
    `${v5DecodeMs.toFixed(1)} ms | ${v6DecodeMs.toFixed(1)} ms | ${decodeRatio}x | ` +
    `${encodeMatch && decodeMatch ? 'yes' : 'MISMATCH'} |`,
  );
}

if (anyMismatch) {
  console.error('\nFAIL: v5/v6 Shift_JIS output diverged on generated dat content (see MISMATCH row above).');
  process.exit(1);
}

console.log('\nOK: v5 and v6 produced byte-identical Shift_JIS output at all sizes.');
