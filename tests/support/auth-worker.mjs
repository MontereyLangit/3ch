// 認証の実 workerd テスト専用。DO と HTTP 処理は本番ビルドをそのまま呼ぶ。
import application from '../../dist/index.js';
export { Thread, Board, Account, AccountIndex, AuthRate } from '../../dist/index.js';

const namespaces = new Set(['THREAD_DO', 'BOARD_DO', 'ACCOUNT_DO', 'ACCOUNT_INDEX_DO', 'AUTH_RATE_DO']);
const externalCalls = [];

// 外部サービスの応答だけを固定する。本番コードにテスト用の認証経路は作らない。
globalThis.fetch = async (input, init) => {
  const url = typeof input === 'string' ? input : input.url;
  if (url !== 'https://challenges.cloudflare.com/turnstile/v0/siteverify') {
    throw new Error('unexpected external fetch in auth integration test');
  }
  const form = new URLSearchParams(init?.body);
  const token = form.get('response');
  externalCalls.push({ token, method: init?.method, remoteip: form.get('remoteip') });
  if (form.get('secret') !== 'local-turnstile-secret') return Response.json({ success: false });
  if (token === 'unavailable') return new Response('', { status: 503 });
  if (token === 'malformed') return new Response('invalid JSON');
  return Response.json({ success: token === 'accepted' || token === 'wrong-host', hostname: token === 'wrong-host' ? 'other.invalid' : 'localhost' });
};

export default {
  async fetch(request, env, context) {
    const url = new URL(request.url);
    if (url.pathname === '/__test/rpc') {
      const { binding, name, method, args = [] } = await request.json();
      if (!namespaces.has(binding)) return Response.json({ error: 'unknown binding' }, { status: 400 });
      try {
        const namespace = env[binding];
        const result = await namespace.get(namespace.idFromName(name))[method](...args);
        return Response.json({ result: result === undefined ? null : result });
      } catch (error) {
        return Response.json({ error: String(error?.message ?? error) }, { status: 500 });
      }
    }
    if (url.pathname === '/__test/external-calls') return Response.json(externalCalls);
    if (url.pathname.startsWith('/__test/no-secret/')) {
      url.pathname = url.pathname.replace('/__test/no-secret', '');
      return application.fetch(new Request(url, request), { ...env, AUTH_SECRET: '' }, context);
    }
    if (url.pathname.startsWith('/__test/no-turnstile/')) {
      url.pathname = url.pathname.replace('/__test/no-turnstile', '');
      return application.fetch(new Request(url, request), { ...env, TURNSTILE_SECRET_KEY: '' }, context);
    }
    if (url.pathname.startsWith('/__test/no-rate/')) {
      url.pathname = url.pathname.replace('/__test/no-rate', '');
      return application.fetch(new Request(url, request), { ...env, AUTH_RATE_DO: undefined }, context);
    }
    return application.fetch(request, env, context);
  },
};
