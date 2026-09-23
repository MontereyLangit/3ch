// Diagnostic-only Worker entry for the HTTP differential test (tests/golden/http-differential.test.mjs).
// Imports the real, unmodified v5 `sjis` class from src/common.js (no edits to src/) and exposes
// an exhaustive encode/decode sweep as JSON, so it can be exercised on real workerd via `wrangler dev`
// (see tests/golden/fixtures/v5-sjis-fixture.wrangler.jsonc). Not part of the production Worker.
import { sjis } from '../../../src/common.js';

function toBytes(maybeArray) {
  // Mirror the real production path (src/common.js sendMessage: `Uint8Array.from(sjisEncoder.encode(text))`)
  // so unmappable characters coerce to 0x00 exactly as they do in an actual response body (KB-17).
  return Array.from(Uint8Array.from(maybeArray));
}

export default {
  fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== '/sjis-table') return new Response('not found', { status: 404 });
    const codec = new sjis();
    const decode = bytes => {
      try { return { value: codec.decode(Uint8Array.from(bytes)) }; }
      catch (e) { return { error: e.name, message: e.message }; }
    };
    return Response.json({
      encodeBmp: Array.from({ length: 65536 }, (_, n) => toBytes(codec.encode(String.fromCharCode(n)))),
      decodeSingle: Array.from({ length: 256 }, (_, n) => decode([n])),
      decodePairs: Array.from({ length: 65536 }, (_, n) => decode([n >> 8, n & 255])),
      supplementary: ['\u{1F600}', '\uD800A', 'A\uDFFF', '􏿿'].map(s => toBytes(codec.encode(s))),
    });
  },
};
