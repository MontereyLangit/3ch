// Differential test: v5 (JS, src/main.js via wrangler.jsonc) vs v6 (Scala.js, dist/index.js via
// wrangler.v6.jsonc), both run under REAL `wrangler dev` (real workerd, not a Node polyfill), each
// on its own port with `--local`/local persistence only -- no production R2/KV/DO is ever touched.
//
// This replaces the earlier version of this file, which drove both sides through wrangler's
// `unstable_dev()` library call against synthetic per-run entry files that mocked `Date`/`Math.random`
// for determinism. `unstable_dev` does run on real workerd (Miniflare spawns the actual `workerd`
// binary locally), but it does not exercise the checked-in `wrangler.jsonc` / `wrangler.v6.jsonc`
// through the literal `wrangler dev` CLI, and it required a wrapper around v5's entry file to get
// there. This version spawns `wrangler dev` itself (via its JS bin entry, for cross-shell reliability)
// against the real, unmodified config files, and does not mock the clock -- timestamps and thread
// keys are left to real wall-clock time and normalized only at comparison time, per the task brief.
//
// Coverage: docs/現行システム仕様書.md §2 public endpoints, 専ブラ Range/If-Modified-Since behavior,
// posting (thread creation + replies, including CP932 duplicate-code chars / emoji / an unmappable
// PUA char), admin moderation/board/thread management, and an exhaustive Shift_JIS encode/decode
// sweep run on real workerd for both sides (closing R-06; see docs/移植方針_step3_ScalaJS.md).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, rm, writeFile, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const repoRoot = resolve(import.meta.dirname, '..', '..');
const wranglerBin = resolve(repoRoot, 'node_modules/wrangler/bin/wrangler.js');
const stateRoot = resolve(repoRoot, '.test-state/http-differential');
const ADMIN_PASSWORD = 'migration-differential-test-only';
// Shared by BOTH sides via `--var`, overriding wrangler.jsonc's real production salt and
// wrangler.v6.jsonc's own placeholder salt, so trip/ID hashing is directly comparable between them.
// The real production salt in wrangler.jsonc is never read, logged, or used.
const SALT = 'migration-differential-test-salt-only';
const auth = 'Basic ' + Buffer.from('admin:' + ADMIN_PASSWORD).toString('base64');
// Both src/bbs.cgi.js and bbs/src/main/scala/.../Posting.scala hardcode this exact literal as the
// only accepted local Referer prefix (`^http://127\.0\.0\.1:8787`), independent of which port the
// dev server actually listens on. Verified empirically against both real wrangler dev instances.
const FIXED_REFERER = 'http://127.0.0.1:8787/';

const PORTS = { v5: 18787, v6: 18801, v5sjis: 18790, v6sjis: 18791 };

function spawnWrangler(args, cwd) {
  const proc = spawn(process.execPath, [wranglerBin, 'dev', ...args], { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
  let log = '';
  proc.stdout.on('data', d => { log += d; });
  proc.stderr.on('data', d => { log += d; });
  return { proc, get log() { return log; } };
}

async function waitReady(handle, port, timeoutMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (handle.proc.exitCode !== null) throw new Error(`wrangler dev on port ${port} exited early (code ${handle.proc.exitCode}):\n${handle.log}`);
    try { await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(2000) }); return; }
    catch { await new Promise(r => setTimeout(r, 300)); }
  }
  throw new Error(`wrangler dev on port ${port} did not become ready within ${timeoutMs}ms:\n${handle.log}`);
}

async function stopAll(handles) {
  for (const h of handles) h.proc.kill('SIGTERM');
  const deadline = Date.now() + 10000;
  for (const h of handles) {
    while (h.proc.exitCode === null && Date.now() < deadline) await new Promise(r => setTimeout(r, 200));
    if (h.proc.exitCode === null) h.proc.kill('SIGKILL');
  }
}

test('v5 / Scala.js+HonoX on real `wrangler dev` (workerd): HTTP status, headers and response bytes', { timeout: 300000 }, async t => {
  await rm(stateRoot, { recursive: true, force: true });
  await mkdir(stateRoot, { recursive: true });
  const persistV5 = resolve(stateRoot, 'persist-v5');
  const persistV6 = resolve(stateRoot, 'persist-v6');
  await mkdir(persistV5, { recursive: true });
  await mkdir(persistV6, { recursive: true });

  const commonVars = ['--var', `salt:${SALT}`, '--var', `ADMIN_PASSWORD:${ADMIN_PASSWORD}`];
  const v5 = spawnWrangler(['--config', resolve(repoRoot, 'wrangler.jsonc'), '--port', String(PORTS.v5), '--local', '--persist-to', persistV5, ...commonVars], repoRoot);
  const v6 = spawnWrangler(['--config', resolve(repoRoot, 'wrangler.v6.jsonc'), '--port', String(PORTS.v6), '--local', '--persist-to', persistV6, ...commonVars], repoRoot);
  const v5sjis = spawnWrangler(['--config', resolve(repoRoot, 'tests/golden/fixtures/v5-sjis-fixture.wrangler.jsonc'), '--port', String(PORTS.v5sjis), '--local'], resolve(repoRoot, 'tests/golden/fixtures'));
  const v6sjis = spawnWrangler(['--config', resolve(repoRoot, 'tests/golden/fixtures/v6-sjis-fixture.wrangler.jsonc'), '--port', String(PORTS.v6sjis), '--local'], resolve(repoRoot, 'tests/golden/fixtures'));
  const handles = [v5, v6, v5sjis, v6sjis];
  t.after(() => stopAll(handles));
  await Promise.all([
    waitReady(v5, PORTS.v5), waitReady(v6, PORTS.v6),
    waitReady(v5sjis, PORTS.v5sjis), waitReady(v6sjis, PORTS.v6sjis),
  ]);

  const origins = [`http://127.0.0.1:${PORTS.v5}`, `http://127.0.0.1:${PORTS.v6}`];

  // ============================================================
  // PART 1: exhaustive Shift_JIS encode/decode sweep on real workerd (R-06 closure).
  // ============================================================
  const sjisOrigins = [`http://127.0.0.1:${PORTS.v5sjis}`, `http://127.0.0.1:${PORTS.v6sjis}`];
  const [sjisV5, sjisV6] = await Promise.all(sjisOrigins.map(o => fetch(o + '/sjis-table').then(r => r.json())));

  // --- 1a. v5-on-real-workerd vs the Node-recorded golden table: confirms R-06's 443 documented
  // Node/workerd TextDecoder('sjis') differences (tests/golden/sjis-runtime-differences.json) are
  // reproducible via the literal `wrangler dev` CLI (not just wrangler's unstable_dev API), and that
  // no *new* runtime divergence exists beyond the recorded list.
  const goldenTable = JSON.parse(await readFile(resolve(repoRoot, 'tests/golden/sjis-table.json'), 'utf8'));
  const expectedRuntimeDiffs = JSON.parse(await readFile(resolve(repoRoot, 'tests/golden/sjis-runtime-differences.json'), 'utf8'));
  function actualCp(entry) { return entry && !entry.error && entry.value ? entry.value.codePointAt(0) : null; }
  const runtimeDiffs = [];
  for (const x of goldenTable.singleByte) { const cp = actualCp(sjisV5.decodeSingle[x.byte]); if (cp !== x.char) runtimeDiffs.push({ bytes: [x.byte], node: x.char, workerd: cp }); }
  for (const x of goldenTable.doubleByte) { const cp = actualCp(sjisV5.decodePairs[x.lead * 256 + x.second]); if (cp !== x.char) runtimeDiffs.push({ bytes: [x.lead, x.second], node: x.char, workerd: cp }); }
  assert.deepEqual(runtimeDiffs, expectedRuntimeDiffs, 'v5 on real `wrangler dev` must reproduce exactly the documented Node/workerd TextDecoder(\'sjis\') differences, no more, no fewer');

  // --- 1b. v5-on-real-workerd vs v6-on-real-workerd, exhaustively: per docs/移植方針_step3_ScalaJS.md
  // R-01, every input v5 correctly converts must match v6 byte-for-byte / char-for-char; divergence is
  // only allowed where v5 itself failed to convert (KB-17: NUL-byte encode fallback / KB-18: thrown
  // TypeError decode, empty-string "swallowed" incomplete lead byte).
  //
  // 注: 案A（encoding-japanese）時代にはここに「v5 が U+FFFD を返した位置は差異を許容する
  // （ただし [0xEF,0xFC] の上書き位置を除く）」という緩和があった。案A破棄により v5 と v6 は
  // 同じ `TextDecoder('sjis')` から同じ手順でテーブルを作るため、U+FFFD になる位置も
  // 完全に一致するはずで、この緩和はもはや不要。残すと本物の差異を隠すので外した。
  const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);
  const v5EncodeFailed = bytes => bytes.length === 1 && bytes[0] === 0;
  const v5DecodeFailed = entry => !!entry.error || entry.value === '';
  const unexpectedEncode = [];
  for (let cp = 0; cp < 65536; cp++) {
    const a = sjisV5.encodeBmp[cp], b = sjisV6.encodeBmp[cp];
    if (!v5EncodeFailed(a) && !eq(a, b)) unexpectedEncode.push({ cp, v5: a, v6: b });
  }
  const unexpectedDecodeSingle = [];
  for (let byte = 0; byte < 256; byte++) {
    const a = sjisV5.decodeSingle[byte], b = sjisV6.decodeSingle[byte];
    if (!v5DecodeFailed(a) && !eq(a.value, b.value)) unexpectedDecodeSingle.push({ byte, v5: a, v6: b });
  }
  // Only forced pairs whose first byte is a genuine 2-byte lead (0x81-0x9F / 0xE0-0xFC) exercise the
  // real 2-byte decode path; other "pairs" are an artifact of sweeping the full 65536-value domain and
  // are informational only (see report: v5's trie treats a non-lead first byte as a complete 1-byte
  // char, then the second byte dangles as a separate, likely-incomplete unit -- not a meaningful
  // 2-byte comparison either side of the port).
  const isLead = b => (b >= 0x81 && b <= 0x9f) || (b >= 0xe0 && b <= 0xfc);
  const unexpectedDecodePairs = [];
  for (let i = 0; i < 65536; i++) {
    const lead = i >> 8, second = i & 255;
    if (!isLead(lead)) continue;
    const a = sjisV5.decodePairs[i], b = sjisV6.decodePairs[i];
    if (!v5DecodeFailed(a) && !eq(a.value, b.value)) unexpectedDecodePairs.push({ lead, second, v5: a, v6: b });
  }
  await writeFile(resolve(stateRoot, 'sjis-real-workerd-diagnostics.json'), JSON.stringify({
    note: 'Real-workerd v5-vs-v6 Shift_JIS sweep (this test run). unexpected* must be empty for R-01 acceptance.',
    supplementary: { v5: sjisV5.supplementary, v6: sjisV6.supplementary },
    unexpectedEncode, unexpectedDecodeSingle, unexpectedDecodePairs,
  }, null, 1));
  assert.deepEqual(unexpectedEncode, [], 'v5-correctly-encoded BMP code points must match v6 byte-for-byte on real workerd');
  assert.deepEqual(unexpectedDecodeSingle, [], 'v5-correctly-decoded single bytes must match v6 char-for-char on real workerd');
  assert.deepEqual(unexpectedDecodePairs, [], 'v5-correctly-decoded 2-byte sequences (valid lead byte) must match v6 char-for-char on real workerd');

  // ============================================================
  // PART 2: HTTP differential battery (real, unmodified wrangler.jsonc / wrangler.v6.jsonc).
  // ============================================================
  let comparisons = 0;
  // Real epoch-second thread keys created during this run, per origin, in creation order. Tracked so
  // request PATHS can be built per-origin (e.g. `/alpha/dat/<that origin's own real key>.dat`) --
  // NOT used for normalization (see below): a moderation-rejected post's error page embeds a
  // "would-be" key-shaped link too (a live epoch second read at render time, no x-threadkey header
  // to capture it from), so tracking only *successful* keys is not enough to catch every occurrence.
  const threadKeysByOrigin = [[], []];
  // v5 emits "YYYY/MM/DD(曜) HH:MM:SS[.ff] ID:xxxx" via Intl.DateTimeFormat('ja-JP', ...); v6 must
  // reproduce it byte-for-byte (KB-independent -- this is not a known bug, just real wall-clock text).
  // The 2-byte gap between '(' and ')' is the SJIS-encoded weekday kanji (verified empirically).
  const DATETIME_RE = /(\d{4}\/\d{2}\/\d{2}\()[\s\S]{1,4}(\)\s\d{2}:\d{2}:\d{2}(?:\.\d+)?)/g;
  // Thread keys are `gb.unixtime` (epoch seconds, 10 digits until year 2286) -- the only 10-digit
  // numerals this BBS's own content ever produces (none of this test's fixed literal field values are
  // 10 digits except the deliberately-fixed '1800000001' move-target key, which normalizes the same
  // way on both sides and so loses no comparison power). Narrower than masking "any digit run" would
  // be, and catches every occurrence -- including ones with no x-threadkey header to key off of (e.g.
  // a moderation-rejected post's error page still renders a live "go to top" link with a fresh
  // epoch-second value).
  const EPOCH_KEY_RE = /\b\d{10}\b/g;
  // KB-17 (documented, expected divergence -- see the dedicated check right after the post that
  // introduces it below): v5 falls back to a single NUL byte for a character it cannot encode; v6
  // falls back to a numeric character reference. Once posted, that thread's dat content keeps
  // reappearing in every later fetch in this test (subject.txt refresh, admin thread view, Range
  // probes, ...), so the SAME already-verified divergence must not fail those later byte comparisons.
  // Scoped narrowly to exactly the two NCR values this test's own KB-17 probe produces.

  function normalize(latin1Text) {
    let text = latin1Text;
    text = text.replace(EPOCH_KEY_RE, '@@KEY@@');
    text = text.replace(/\x00/g, '@@UNMAPPABLE@@'); // v5's fallback for a char it cannot SJIS-encode (KB-17)
    text = text.replace(/&#128512;|&#57344;/g, '@@UNMAPPABLE@@'); // v6's NCR fallback for the same two probe chars
    text = text.replace(DATETIME_RE, '@@DATETIME@@');
    // RFC 1123 HTTP-date, as used in Last-Modified / If-Modified-Since round-trips.
    text = text.replace(/[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT/g, '@@HTTPDATE@@');
    // bbsmenu.html が埋め込む「更新日 YYYY/M/D H:MM:SS」。生成時刻そのものなので、v5 と v6 を順に
    // 叩く都合上そこで秒境界を跨ぐと必ず1秒ずれる（実測で6回に1回程度発生）。
    // 本質的に可変な値のみを対象にするため、この形式に限定して正規化する。
    text = text.replace(/\d{4}\/\d{1,2}\/\d{1,2} \d{1,2}:\d{2}:\d{2}/g, '@@GENERATEDAT@@');
    return text;
  }
  function rememberThreadKey(originIndex, key) {
    // 値による重複除去はしない。キーは10桁の秒なので、両 wrangler プロセスが同じ秒に別板のスレを
    // 作ると片側だけが「既出」と判定されて1件に潰れ、左右の列長がずれる（実測で4回に1回程度発生し、
    // 存在しないキーと停止済みスレを突き合わせる偽の差分になっていた）。
    // 常に push すれば、キーが衝突しても両側が同じ回数記録するので対称性が保たれる。
    // 以降の参照は [0]（alpha の最初のスレ）固定なので、重複が混ざっても意味は変わらない。
    if (key && /^\d{10}$/.test(key)) threadKeysByOrigin[originIndex].push(key);
  }

  async function pair(path, { form, headers = {}, admin = false, method = form ? 'POST' : 'GET', expected, body } = {}) {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      // `form` may be a function of the origin index: needed whenever the request body must embed
      // that origin's OWN real thread key (v5 and v6 usually mint the same epoch-second key since
      // both requests fire within the same test tick, but that is not guaranteed across a second
      // boundary, so posting v5's key against v6 -- or vice versa -- would be a test-harness bug,
      // not a real product divergence).
      const resolvedForm = typeof form === 'function' ? form(i) : form;
      const response = await fetch(origin + path, {
        method, redirect: 'manual',
        headers: { ...(admin ? { authorization: auth, origin } : {}), ...headers },
        body: resolvedForm ? new URLSearchParams(resolvedForm) : body,
      });
      const bytes = Buffer.from(await response.arrayBuffer());
      const h = Object.fromEntries(response.headers);
      if (h.date) { assert.ok(Number.isFinite(Date.parse(h.date))); delete h.date; } // transport-level, not application content
      if (h['content-length']) assert.equal(Number(h['content-length']), bytes.length, path);
      const threadKey = h['x-threadkey'];
      rememberThreadKey(i, threadKey);
      // 左右の記録済みキー列は常に同じ長さでなければならない。ずれた状態で以降の `threadKeysByOrigin[i][n]`
      // を使うと、v5 と v6 に**別のスレッド**を投げて比較することになり、テストが黙って無意味になる
      // （実際にこのズレで偽の差分が出た: 片側が存在しないキー、もう片側が自分の停止済みスレを叩いていた）。
      // キーは10桁の秒なので、両 wrangler プロセスが同じ秒に別板のスレを作ると重複除去の効き方が左右で
      // 変わりうる。黙って進まず、ここで落とす。
      if (i === origins.length - 1) {
        const lengths = threadKeysByOrigin.map(keys => keys.length);
        assert.ok(new Set(lengths).size === 1,
          `thread key desync at ${path}: ${JSON.stringify(threadKeysByOrigin)} — 左右で別スレッドを比較する状態なので中断する`);
      }
      if (h['last-modified']) h['last-modified'] = normalize(h['last-modified']);
      if (threadKey) h['x-threadkey'] = normalize(threadKey);
      snapshots.push({ status: response.status, headers: h, bodyNormalized: normalize(bytes.toString('latin1')), rawLength: bytes.length });
    }
    assert.equal(snapshots[0].status, snapshots[1].status, `status ${method} ${path}`);
    assert.deepEqual(snapshots[0].headers, snapshots[1].headers, `headers ${method} ${path}`);
    assert.equal(snapshots[0].bodyNormalized, snapshots[1].bodyNormalized, `body ${method} ${path}`);
    if (expected !== undefined) assert.equal(snapshots[0].status, expected, path);
    comparisons++;
    return { status: snapshots[0].status, headers: snapshots[0].headers };
  }
  const save = (path, form, expected = 303) => pair(path, { form, admin: true, expected });
  // Covers all ten CP932 duplicate-code chars (docs/移植方針_step3_ScalaJS.md R-01), an emoji
  // (astral, KB-17), and a Private-Use-Area char (KB-17) -- the task's explicit char coverage list.
  const SJIS_SENSITIVE_MESSAGE = '本文≒≡∫√⊥∠∵∩∪￢絵文字\nsecond\nthird';
  // Every LAST_KEY use below replies to the ONE 'alpha' thread created at `post('alpha')` further down
  // (no second 'alpha' thread is ever created in this run), so "alpha's own key" is always
  // threadKeysByOrigin[i][0] -- deliberately NOT `.at(-1)`. `threadKeysByOrigin` is a single array
  // shared across ALL boards (alpha/beta/...), and `rememberThreadKey` dedupes by the literal 10-digit
  // key value; since keys are epoch SECONDS, an unrelated board's thread minted in the same wall-clock
  // second as 'alpha's can legitimately carry the identical numeral. When that coincidence hits one
  // origin's timeline but not the other's (a real, observed race between the two independently-spawned
  // `wrangler dev` processes), `.at(-1)` desyncs per-origin: one side keeps pointing at 'alpha's real
  // key, the other silently slides onto a same-second sibling board's key, replying to a thread that
  // doesn't exist under 'alpha' on one side while (if that numeral also happens to be that origin's own
  // 'alpha' key) hitting the real, just-stopped 'alpha' thread on the other -- a false-positive
  // "product divergence" that is actually only this array's indexing, not a real v5/v6 difference.
  const LAST_KEY = Symbol('last-key-per-origin');
  const post = (bbs, thread = '', message = SJIS_SENSITIVE_MESSAGE, subject = 'タイトル') => pair('/test/bbs.cgi', {
    form: i => ({ bbs, key: thread === LAST_KEY ? threadKeysByOrigin[i][0] : thread, MESSAGE: message, FROM: '名前#日本語トリップ', mail: 'sage', subject: thread === '' ? subject : '', time: '1', submit: '書き込む' }),
    headers: { 'user-agent': 'MigrationBrowser/1.0', referer: FIXED_REFERER, 'CF-Connecting-IP': '192.0.2.1' }, expected: 200,
  });

  // --- docs/現行システム仕様書.md §2 public endpoints + 404 ---
  for (const path of ['/missing', '/auth.js', '/test/sakubbs.cgi/25', '/test/bbs.cgi', '/bbsmenu.html', '/headline/subject.txt', '/alpha/subject.txt', '/alpha/head.txt', '/alpha/SETTING.TXT', '/alpha/', '/alpha/index.html', '/alpha/kako/subject.txt', '/alpha/kako/1789/subject.txt']) await pair(path);
  // Prefix matching and non-GET dispatch are intentional legacy behavior (see 既知不具合台帳.md).
  await pair('/alpha/SETTING.TXT/extra', { method: 'PUT', expected: 200 });
  await pair('/alpha/subject.txt.trailing', { method: 'OPTIONS', expected: 200 });

  // --- admin auth / board setup ---
  await pair('/admin/', { expected: 401 });
  await pair('/admin/', { admin: true, expected: 200 });
  await pair('/admin/menu', { admin: true, method: 'DELETE', expected: 405 });
  await pair('/admin/menu', { admin: true, form: { html: 'no' }, headers: { origin: 'https://bad.invalid' }, expected: 403 });
  await pair('/admin/board?bbs=../bad', { admin: true, expected: 400 });
  for (const bbs of ['alpha', 'beta']) {
    await save('/admin/board?bbs=' + bbs, { action: 'save', head: '<b>ルール≒①</b>', settings: `BBS_TITLE=${bbs}板\nBBS_NONAME_NAME=名無し\nBBS_MAX_MENU_THREAD=700` });
    await pair('/admin/board?bbs=' + bbs, { admin: true });
    await pair('/' + bbs + '/');
  }

  // --- moderation (deny.cgi / ngwords.cgi) ---
  await save('/admin/access', { rules: '[<><><>deny' }, 400);
  await save('/admin/access', { rules: '^alpha$<><><>deny' });
  await post('alpha');
  await save('/admin/access', { rules: '' });
  await save('/admin/ngwords', { rules: '<><>禁止<>deny' });
  await post('alpha', '', SJIS_SENSITIVE_MESSAGE, '禁止');
  await save('/admin/ngwords', { rules: '' });

  // --- posting: thread creation + replies, including the Shift_JIS-sensitive characters ---
  const created = await post('alpha');
  assert.equal(created.headers['x-threadkey'], '@@KEY@@'); // already normalized identically for both origins
  await post('alpha', LAST_KEY); // reply, using each origin's own real key

  // --- KB-17 (documented, expected divergence): v5 cannot encode an emoji or a PUA char and falls
  // back to a NUL byte; v6 (SjisEncoding.scala, R-01) falls back to a numeric character
  // reference. Verified directly against each side's own real dat content (not cross-origin byte
  // equality, since a difference here is the intended, already-documented KB-17 behavior). Posted as
  // a new thread on its own board ('gamma', otherwise unused): a reply to the shared 'alpha' thread
  // would make v5/v6 diverge in total byte length (NCR is longer than a single NUL byte), breaking
  // unrelated Content-Range/Content-Length checks used later on that thread; and a second new
  // *alpha* thread this soon collides with v5's own same-second-new-thread guard ("別の人が同時刻に
  // スレッドを立てようとしています", a real, unrelated timing guard reproduced while writing this
  // test), which a dedicated board sidesteps entirely.
  {
    const kb17Message = '絵文字\u{1F600}不明字\uE000';
    for (const origin of origins) {
      const response = await fetch(origin + '/test/bbs.cgi', {
        method: 'POST', redirect: 'manual',
        headers: { 'user-agent': 'MigrationBrowser/1.0', referer: FIXED_REFERER, 'CF-Connecting-IP': '192.0.2.1', 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ bbs: 'gamma', key: '', MESSAGE: kb17Message, FROM: '名前', mail: 'sage', subject: 'kb17', time: '1', submit: '書き込む' }),
      });
      assert.equal(response.status, 200);
      await response.arrayBuffer();
      const key = response.headers.get('x-threadkey');
      const dat = Buffer.from(await (await fetch(origin + `/gamma/dat/${key}.dat`)).arrayBuffer());
      const text = dat.toString('latin1');
      const isV5 = origin === origins[0];
      // "絵文字" (encodable, must appear literally) followed by the emoji fallback, then "不明字"
      // (encodable) followed by the PUA fallback.
      assert.ok(text.includes('\x8a\x47\x95\xb6\x8e\x9a'), `${isV5 ? 'v5' : 'v6'} dat should contain the SJIS bytes for 絵文字`);
      assert.ok(text.includes('\x95\x73\x96\xbe\x8e\x9a'), `${isV5 ? 'v5' : 'v6'} dat should contain the SJIS bytes for 不明字`);
      if (isV5) {
        assert.ok(text.includes('\x8e\x9a\x00\x95\x73'), 'v5 (KB-17): emoji must fall back to a single NUL byte between 字 and 不');
        assert.ok(text.includes('\x8e\x9a\x00 <>'), 'v5 (KB-17): trailing PUA char must fall back to a single NUL byte before the field-closing delimiter');
      } else {
        assert.ok(text.includes('\x8e\x9a&#128512;\x95\x73'), 'v6 (KB-17): emoji must fall back to its NCR, &#128512;, between 字 and 不');
        assert.ok(text.includes('\x8e\x9a&#57344; <>'), 'v6 (KB-17): trailing PUA char must fall back to its NCR, &#57344;, before the field-closing delimiter');
      }
    }
    comparisons++;
  }
  await post('beta');
  // subbbs.cgi (2ch-compatible reply endpoint, percent-encoded Shift_JIS body)
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0]; // first alpha thread created above
      const response = await fetch(origin + '/test/subbbs.cgi/extra', {
        method: 'POST', redirect: 'manual',
        headers: { 'content-type': 'application/x-www-form-urlencoded', 'user-agent': 'Monazilla/1.00 Migration', referer: FIXED_REFERER, 'CF-Connecting-IP': '192.0.2.2' },
        body: `bbs=alpha&key=${key}&FROM=name&mail=&MESSAGE=%82%A0%87%90&time=1&submit=%8F%91%82%AB%8D%9E%82%DE`,
      });
      const bytes = Buffer.from(await response.arrayBuffer());
      const h = Object.fromEntries(response.headers); delete h.date;
      if (h['content-length']) assert.equal(Number(h['content-length']), bytes.length);
      if (h['x-threadkey']) h['x-threadkey'] = normalize(h['x-threadkey']);
      snapshots.push({ status: response.status, headers: h, body: normalize(bytes.toString('latin1')) });
    }
    assert.equal(snapshots[0].status, 200);
    assert.equal(snapshots[0].status, snapshots[1].status, 'subbbs.cgi status');
    assert.deepEqual(snapshots[0].headers, snapshots[1].headers, 'subbbs.cgi headers');
    assert.equal(snapshots[0].body, snapshots[1].body, 'subbbs.cgi body');
    comparisons++;
  }

  // --- results of posting: subject.txt / dat / headline / bbsmenu / kako reflect the new content ---
  async function pairPerOriginKeyed(pathForKey, opts) {
    // Some paths embed a real thread key (e.g. `/alpha/dat/<key>.dat`). Build the ACTUAL path per
    // origin from that origin's own real key, then compare the (normalized) results as usual.
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const path = pathForKey(threadKeysByOrigin[i]);
      const response = await fetch(origin + path, { method: 'GET', redirect: 'manual', headers: { ...(opts?.admin ? { authorization: auth, origin } : {}), ...(opts?.headers || {}) } });
      const bytes = Buffer.from(await response.arrayBuffer());
      const h = Object.fromEntries(response.headers); delete h.date;
      if (h['content-length']) assert.equal(Number(h['content-length']), bytes.length);
      if (h['last-modified']) h['last-modified'] = normalize(h['last-modified']);
      snapshots.push({ status: response.status, headers: h, body: normalize(bytes.toString('latin1')) });
    }
    assert.equal(snapshots[0].status, snapshots[1].status, pathForKey(['<key>']));
    assert.deepEqual(snapshots[0].headers, snapshots[1].headers, pathForKey(['<key>']));
    assert.equal(snapshots[0].body, snapshots[1].body, pathForKey(['<key>']));
    if (opts?.expected !== undefined) assert.equal(snapshots[0].status, opts.expected);
    comparisons++;
    return snapshots[0];
  }
  await pair('/alpha/subject.txt');
  await pairPerOriginKeyed(k => `/alpha/dat/${k[0]}.dat`);
  await pair('/headline/subject.txt');
  await pair('/bbsmenu.html');
  await pair('/alpha/kako/subject.txt');
  await pairPerOriginKeyed(k => `/alpha/kako/${k[0].slice(0, 4)}/subject.txt`);
  await pair('/admin/threads?bbs=alpha', { admin: true });
  await pairPerOriginKeyed(k => `/admin/thread?bbs=alpha&key=${k[0]}`, { admin: true });
  await pair('/admin/access', { admin: true });
  await pair('/admin/ngwords', { admin: true });
  await pair('/admin/menu', { admin: true });

  // --- Range (open/bounded/out-of-range/malformed) ---
  for (const range of ['bytes=0-5', 'bytes=-5', 'bytes=999999-', 'bad', 'bytes=3-2', 'bytes=0-', 'bytes=0-999999', 'bytes=9007199254740993-']) {
    await pairPerOriginKeyed(k => `/alpha/dat/${k[0]}.dat`, { headers: { range } });
  }

  // --- If-Modified-Since: observe each origin's own real Last-Modified, then probe around it ---
  {
    await pairPerOriginKeyed(k => `/alpha/dat/${k[0]}.dat`);
    const lastModifiedByOrigin = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const r = await fetch(origin + `/alpha/dat/${key}.dat`);
      lastModifiedByOrigin.push(new Date(r.headers.get('last-modified')));
    }
    for (const deltaMs of [0, 1000, -1000]) {
      const snapshots = [];
      for (const [i, origin] of origins.entries()) {
        const key = threadKeysByOrigin[i][0];
        const since = new Date(lastModifiedByOrigin[i].getTime() + deltaMs).toUTCString();
        const response = await fetch(origin + `/alpha/dat/${key}.dat`, { headers: { 'if-modified-since': since } });
        const bytes = Buffer.from(await response.arrayBuffer());
        const h = Object.fromEntries(response.headers); delete h.date;
        if (h['last-modified']) h['last-modified'] = '@@HTTPDATE@@';
        snapshots.push({ status: response.status, headers: h, body: normalize(bytes.toString('latin1')) });
      }
      assert.equal(snapshots[0].status, snapshots[1].status, `if-modified-since delta=${deltaMs}`);
      assert.deepEqual(snapshots[0].headers, snapshots[1].headers, `if-modified-since delta=${deltaMs}`);
      assert.equal(snapshots[0].body, snapshots[1].body, `if-modified-since delta=${deltaMs}`);
      comparisons++;
    }
    await pair('/alpha/dat/nonexistent0.dat', { headers: { 'if-modified-since': 'invalid' } });
  }

  // --- admin thread lifecycle: stop/resume/edit/delete-res/move/delete ---
  await pairPerOriginKeyed(k => `/admin/thread?bbs=alpha&key=${k[0]}`, { admin: true });
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams({ action: 'stop' }) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [303, 303]);
    comparisons++;
  }
  await post('alpha', LAST_KEY);
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams({ action: 'resume' }) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [303, 303]);
    comparisons++;
  }
  await post('alpha', LAST_KEY, '再開後');
  {
    const editForm = { action: 'editRes', number: '2', name: '<script>', mail: 'sage', message: '編集\n本文 & < > "' };
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams(editForm) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [303, 303]);
    comparisons++;
  }
  await pairPerOriginKeyed(k => `/admin/thread?bbs=alpha&key=${k[0]}`, { admin: true });
  await pairPerOriginKeyed(k => `/alpha/dat/${k[0]}.dat`, { headers: { range: 'bytes=5-' }, expected: 200 });
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams({ action: 'deleteRes', number: '999' }) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [400, 400]);
    comparisons++;
  }
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams({ action: 'deleteRes', number: '1' }) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [303, 303]);
    comparisons++;
  }
  {
    const snapshots = [];
    for (const [i, origin] of origins.entries()) {
      const key = threadKeysByOrigin[i][0];
      const response = await fetch(origin + `/admin/thread?bbs=alpha&key=${key}`, { method: 'POST', redirect: 'manual', headers: { authorization: auth, origin }, body: new URLSearchParams({ action: 'move', targetBoard: 'beta', targetKey: '1800000001' }) });
      await response.arrayBuffer();
      snapshots.push(response.status);
    }
    assert.deepEqual(snapshots, [303, 303]);
    comparisons++;
  }
  await pairPerOriginKeyed(k => `/alpha/dat/${k[0]}.dat`);
  await pair('/alpha/subject.txt');
  await pair('/beta/subject.txt');
  await pair('/beta/dat/1800000001.dat'); // fixed literal target key, not time-derived -- no normalization needed
  await pairPerOriginKeyed(k => `/alpha/kako/${k[0].slice(0, 4)}/subject.txt`);
  await pair('/headline/subject.txt');
  await pairPerOriginKeyed(k => `/admin/thread?bbs=alpha&key=${k[0]}`, { admin: true });
  await pair('/admin/thread?bbs=beta&key=1800000001', { admin: true });
  await save('/admin/thread?bbs=beta&key=1800000001', { action: 'delete' });
  await pair('/beta/dat/1800000001.dat', { expected: 404 });
  await save('/admin/menu', { html: '<html>独自メニュー≒①</html>' });
  await pair('/bbsmenu.html');

  await writeFile(resolve(stateRoot, 'result.json'), JSON.stringify({ comparisons, byteExactAfterNarrowNormalization: true, threadKeysByOrigin }) + '\n');
  t.diagnostic(`${comparisons} paired HTTP requests matched (status + headers + body bytes, after normalizing only real-time-derived thread keys / timestamps)`);
});

test('checked-in v6 config: exported DO binding and RPC (sanity check on real `wrangler dev`)', { timeout: 60000 }, async t => {
  const persist = resolve(stateRoot, 'persist-v6-health');
  await mkdir(persist, { recursive: true });
  const handle = spawnWrangler(['--config', resolve(repoRoot, 'wrangler.v6.jsonc'), '--port', '18802', '--local', '--persist-to', persist], repoRoot);
  t.after(() => stopAll([handle]));
  await waitReady(handle, 18802);
  const response = await fetch('http://127.0.0.1:18802/_v6/health');
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'ch3s v6s ok (do:pong)');
});
