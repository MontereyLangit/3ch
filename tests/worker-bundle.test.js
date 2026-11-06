import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';

const bundle = new URL('../dist/index.js', import.meta.url);

test('Worker ビルドが全 Durable Object クラスを公開する', {
  skip: existsSync(bundle) ? false : 'dist/index.js がありません（npm run v6:build を先に実行）',
}, () => {
  const source = readFileSync(bundle, 'utf8');
  const declaration = [...source.matchAll(/\bexport\s*\{([^}]*)\}/g)].at(-1);
  assert.ok(declaration, 'Worker の名前付き export が必要');
  const names = declaration[1].split(',').map(entry => entry.trim().split(/\s+as\s+/).at(-1));
  for (const name of ['Board', 'Thread', 'Account', 'AccountIndex', 'AuthRate', 'default']) {
    assert.ok(names.includes(name), `Worker ビルドに ${name} の export がない`);
  }
});
