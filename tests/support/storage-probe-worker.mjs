import { DurableObject } from 'cloudflare:workers';

// 固定値だけを使う独立した SQLite DO。本番の Account と保存領域を共有しない。
export class TestStorageProbe extends DurableObject {
  constructor(context, env) {
    super(context, env);
    this.probeContext = context;
  }

  async run() {
    return this.probeContext.blockConcurrencyWhile(async () => {
      const storage = this.probeContext.storage;
      const available = typeof storage.kv?.get === 'function'
        && typeof storage.kv?.put === 'function'
        && typeof storage.transactionSync === 'function'
        && typeof storage.sql?.exec === 'function';
      if (!available) return { available };

      storage.sql.exec('CREATE TABLE IF NOT EXISTS probe_counts (id INTEGER PRIMARY KEY, n INTEGER NOT NULL CHECK(n >= 0))');
      storage.sql.exec('INSERT OR REPLACE INTO probe_counts VALUES (1, 0)');
      const profile = { aid: 'abcdefghijklmnop', uid: 'user1234', kind: 'password', generation: 0, expiresAt: undefined };
      const credential = {
        algo: 'pbkdf2-sha256', iterations: 100000,
        salt: '000102030405060708090a0b0c0d0e0f', hash: 'ab'.repeat(32),
        updatedAt: 1800000000, throttle: { failures: [], locks: {} },
      };
      await storage.put({ profile, credential, activation: 'pending' });
      const syncReadsAsync = JSON.stringify(storage.kv.get('credential')) === JSON.stringify(credential)
        && storage.kv.get('profile').generation === 0;
      // 非同期 get のキャッシュを温めてから同期 KV で書き換える。
      const originalProfile = await storage.get('profile');
      const originalCredential = await storage.get('credential');
      const committedCredential = { ...credential, hash: 'bc'.repeat(32), updatedAt: 1800000001 };
      storage.transactionSync(() => {
        storage.kv.put('profile', { ...profile, generation: 1 });
        storage.kv.put('credential', committedCredential);
        storage.kv.put('activation', 'ready');
        storage.sql.exec('UPDATE probe_counts SET n = 1 WHERE id = 1');
      });
      const committedProfile = await storage.get('profile');
      const committed = await storage.get('credential');
      const commit = committedProfile.generation === 1
        && JSON.stringify(committed) === JSON.stringify(committedCredential)
        && storage.kv.get('activation') === 'ready'
        && storage.sql.exec('SELECT n FROM probe_counts WHERE id = 1').one().n === 1;

      let jsError = false;
      try {
        storage.transactionSync(() => {
          storage.kv.put('profile', { ...profile, generation: 2 });
          storage.kv.put('credential', { ...credential, hash: 'cd'.repeat(32) });
          storage.kv.put('activation', 'pending');
          storage.sql.exec('UPDATE probe_counts SET n = 2 WHERE id = 1');
          throw new Error('injected storage probe rollback');
        });
      } catch {
        jsError = true;
      }
      const afterJs = await storage.get(['profile', 'credential', 'activation']);
      const jsRollback = jsError && afterJs.get('profile').generation === 1
        && JSON.stringify(afterJs.get('credential')) === JSON.stringify(committedCredential)
        && afterJs.get('activation') === 'ready'
        && storage.sql.exec('SELECT n FROM probe_counts WHERE id = 1').one().n === 1;

      let sqlError = false;
      try {
        storage.transactionSync(() => {
          storage.kv.put('profile', { ...profile, generation: 3 });
          storage.kv.put('credential', { ...credential, hash: 'de'.repeat(32) });
          storage.kv.put('activation', 'pending');
          storage.sql.exec('UPDATE probe_counts SET n = 3 WHERE id = 1');
          storage.sql.exec('UPDATE probe_counts SET n = -1 WHERE id = 1');
        });
      } catch {
        sqlError = true;
      }
      const afterSql = await storage.get(['profile', 'credential', 'activation']);
      const sqlRollback = sqlError && afterSql.get('profile').generation === 1
        && JSON.stringify(afterSql.get('credential')) === JSON.stringify(committedCredential)
        && afterSql.get('activation') === 'ready'
        && storage.sql.exec('SELECT n FROM probe_counts WHERE id = 1').one().n === 1;

      const structuredValue = { when: new Date(0), bytes: new Uint8Array([0, 255]), optional: undefined, nil: null };
      const matchesStructured = value => value.when instanceof Date && value.when.getTime() === 0
        && value.bytes instanceof Uint8Array && value.bytes.length === 2 && value.bytes[0] === 0 && value.bytes[1] === 255
        && Object.hasOwn(value, 'optional') && value.optional === undefined && value.nil === null;
      storage.kv.put('structuredSync', structuredValue);
      const fromSync = await storage.get('structuredSync');
      await storage.put('structuredAsync', structuredValue);
      const fromAsync = storage.kv.get('structuredAsync');
      const serializer = matchesStructured(fromSync) && matchesStructured(fromAsync)
        && Object.hasOwn(storage.kv.get('profile'), 'expiresAt')
        && storage.kv.get('profile').expiresAt === undefined;
      const cachedReadCoherent = originalProfile.generation === 0 && committedProfile.generation === 1
        && originalCredential.hash === credential.hash && committed.hash === committedCredential.hash
        && storage.kv.get('profile').generation === 1
        && storage.kv.get('credential').hash === committedCredential.hash;
      return { available, syncReadsAsync, commit, jsRollback, sqlRollback, serializer, cachedReadCoherent };
    });
  }
}

export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname !== '/__test/storage-probe') return new Response('', { status: 404 });
    if (request.method !== 'GET') return new Response('', { status: 405 });
    const namespace = env.TEST_STORAGE_PROBE;
    const result = await namespace.get(namespace.idFromName('fixed-storage-probe')).run();
    return Response.json(result);
  },
};
