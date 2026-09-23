// Diagnostic-only Worker entry for the HTTP differential test (tests/golden/http-differential.test.mjs).
// Imports the real, compiled Scala.js output (bbs/target/scala-3.3.8/bbs-fastopt/server.js, built by
// `sbt bbs/fastLinkJS`) directly -- the same artifact the characterization tests link against -- and
// calls the diagnostic `sjisEncode`/`sjisDecode` exports added to ch3s.bbs.Main for this purpose
// (they are not wired into any HTTP route; see bbs/src/main/scala/ch3s/bbs/Main.scala). Exposes the
// same exhaustive encode/decode sweep as the v5 fixture so both can be compared on real workerd.
import { sjisEncode, sjisDecode } from '../../../bbs/target/scala-3.3.8/bbs-fastopt/server.js';

export default {
  fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== '/sjis-table') return new Response('not found', { status: 404 });
    const decode = bytes => {
      try { return { value: sjisDecode(new Uint8Array(bytes)) }; }
      catch (e) { return { error: e.name, message: e.message }; }
    };
    return Response.json({
      encodeBmp: Array.from({ length: 65536 }, (_, n) => Array.from(sjisEncode(String.fromCharCode(n)))),
      decodeSingle: Array.from({ length: 256 }, (_, n) => decode([n])),
      decodePairs: Array.from({ length: 65536 }, (_, n) => decode([n >> 8, n & 255])),
      supplementary: ['\u{1F600}', '\uD800A', 'A\uDFFF', '􏿿'].map(s => Array.from(sjisEncode(s))),
    });
  },
};
