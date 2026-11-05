// 実 workerd で DO の RPC を直接呼ぶテスト専用 Worker（tests/rpc.integration.test.js）。
// 本番ビルド dist/index.js の DO クラスをそのまま使い、本番 Worker の公開ルートは持たない。
export { Thread, Board } from '../../dist/index.js';

const namespaces = { THREAD_DO: 'THREAD_DO', BOARD_DO: 'BOARD_DO' };

export default {
  async fetch(request, env) {
    const { binding, name, method, args } = await request.json();
    const namespace = env[namespaces[binding]];
    if (!namespace) return Response.json({ error: `unknown binding ${binding}` }, { status: 400 });
    const stub = namespace.get(namespace.idFromName(name));
    try {
      const result = await stub[method](...args);
      return Response.json({ result: result === undefined ? null : result });
    } catch (error) {
      return Response.json({ error: String(error?.message ?? error) }, { status: 500 });
    }
  },
};
