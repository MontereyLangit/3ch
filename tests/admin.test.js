import { test } from 'node:test';
import assert from 'node:assert/strict';
import { admin } from '../src/admin.js';

test('管理認証は未設定・誤認証を拒否し、UTF-8資格情報を受け付ける', async () => {
  assert.equal((await admin(new Request('https://example.test/admin/'), {})).status, 503);
  const env = { ADMIN_USER: '管理者', ADMIN_PASSWORD: 'テスト用パスワード' };
  assert.equal((await admin(new Request('https://example.test/admin/'), env)).status, 401);
  const authorization = 'Basic ' + Buffer.from(`${env.ADMIN_USER}:${env.ADMIN_PASSWORD}`).toString('base64');
  const response = await admin(new Request('https://example.test/admin/', { headers: { authorization } }), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.match(response.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.equal((await admin(new Request('https://example.test/admin/', { method: 'DELETE', headers: { authorization } }), env)).status, 405);
});

test('管理変更は同一オリジンを確認し、Origin省略時はFetch Metadataで確認する', async () => {
  const writes = [];
  const env = { ADMIN_PASSWORD: 'test', ch3www: { put: async (...args) => writes.push(args) } };
  const authorization = 'Basic ' + Buffer.from('admin:test').toString('base64');
  const post = headers => admin(new Request('https://example.test/admin/menu', { method: 'POST', headers: { authorization, ...headers }, body: 'html=menu' }), env);
  assert.equal((await post({})).status, 403);
  assert.equal((await post({ origin: 'https://evil.test' })).status, 403);
  assert.equal((await post({ origin: 'null', 'sec-fetch-site': 'same-origin' })).status, 403);
  assert.equal((await post({ 'sec-fetch-site': 'same-site' })).status, 403);
  assert.equal(writes.length, 0);
  assert.equal((await post({ 'sec-fetch-site': 'same-origin' })).status, 303);
  assert.equal((await post({ origin: 'https://example.test' })).status, 303);
  assert.equal(writes.length, 2);
});
