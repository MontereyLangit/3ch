import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const staticDir = fileURLToPath(new URL('../dist/static/', import.meta.url));
const built = existsSync(path.join(staticDir, 'client.js'));

test('KB-27: ブラウザ向け JS にサーバ処理を含めない', {
  skip: built ? false : 'dist/static/client.js がありません（npm run v6:build を先に実行）',
}, () => {
  let islandsBundled = false;
  for (const file of readdirSync(staticDir, { recursive: true })) {
    if (!file.endsWith('.js')) continue;
    const source = readFileSync(path.join(staticDir, file), 'utf8');
    // island（Scala.js）は client.js から遅延読込される別チャンクに入る。
    if (source.includes('3ch:draft:')) islandsBundled = true;
    assert.doesNotMatch(source,
      /io\._3ch\.bbs\.|cloudflare:workers|ADMIN_PASSWORD|deny\.cgi|ngwords\.cgi/,
      `${file} にサーバ専用の処理が含まれています`);
  }
  assert.ok(islandsBundled, 'ブラウザ向け JS に bbs-client の island が含まれていません');
});
