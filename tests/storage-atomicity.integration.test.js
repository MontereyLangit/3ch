import { test } from 'node:test';
import assert from 'node:assert/strict';
import { unstable_dev } from 'wrangler';

test('Wrangler: SQLite DO の同期 KV と SQL が同じトランザクションで確定・取消される', { timeout: 120000 }, async t => {
  const worker = await unstable_dev(new URL('./support/storage-probe-worker.mjs', import.meta.url).pathname, {
    config: new URL('./support/wrangler.storage-probe.jsonc', import.meta.url).pathname,
    local: true, persist: false, port: 0, logLevel: 'error',
    experimental: { disableExperimentalWarning: true, disableDevRegistry: true, watch: false },
  });
  t.after(() => worker.stop());
  const origin = `http://${worker.address}:${worker.port}`;
  const expected = {
    available: true,
    syncReadsAsync: true,
    commit: true,
    jsRollback: true,
    sqlRollback: true,
    serializer: true,
    cachedReadCoherent: true,
  };
  // 同じ DO を再利用し、既存の表・キャッシュがあっても結果が変わらないことを確認する。
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await fetch(origin + '/__test/storage-probe');
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), expected);
  }
  assert.equal((await fetch(origin + '/__test/storage-probe', { method: 'POST', body: 'ignored' })).status, 405);
  assert.equal((await fetch(origin + '/missing')).status, 404);
});
