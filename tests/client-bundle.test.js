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
  assert.match(readFileSync(path.join(staticDir, 'client.js'), 'utf8'), /pong-from-client/);
  for (const file of readdirSync(staticDir, { recursive: true })) {
    if (!file.endsWith('.js')) continue;
    const source = readFileSync(path.join(staticDir, file), 'utf8');
    assert.doesNotMatch(source,
      /ch3s\.bbs\.|cloudflare:workers|ADMIN_PASSWORD|deny\.cgi|ngwords\.cgi/,
      `${file} にサーバ専用の処理が含まれています`);
  }
});
