import path from "node:path";
import { defineConfig, type Plugin } from "vite";
import honox from "honox/vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import tailwindcss from "@tailwindcss/vite";

// client/server は別々の sbt プロジェクトでリンクする（KB-27）。
// npm run v6:build は両ビルドを直列に実行し、sbt の同時起動を避ける。

// sbtビルドはこのVite/HonoXプロジェクトと同じルート（ch3s直下）にある。
const scalaCwd = path.resolve(__dirname);

function scalaProject(projectID: string, uriPrefix?: string): Plugin {
  const plugin = scalaJSPlugin({ cwd: scalaCwd, projectID, uriPrefix });
  const buildStart = plugin.buildStart;
  if (buildStart) {
    plugin.buildStart = {
      sequential: true,
      handler:
        typeof buildStart === "function" ? buildStart : buildStart.handler,
    };
  }
  return plugin;
}

export default defineConfig(({ mode }) => {
  if (mode === "client") {
    return {
      plugins: [tailwindcss(), scalaProject("bbsClient")],
      build: {
        rollupOptions: {
          input: ["./app/client.ts"],
          output: {
            entryFileNames: "static/client.js",
            chunkFileNames: "static/assets/[name]-[hash].js",
            assetFileNames: "static/assets/[name].[ext]",
          },
        },
        emptyOutDir: false,
      },
    };
  }

  // v6sはCloudflare Workers専用（DOがcloudflare:workers組込みモジュールに依存するため、
  // ローカル確認は常に `wrangler dev` で行う）。
  //
  // 【参照実装からの重要な引き継ぎ】`@hono/vite-build/cloudflare-workers` は本番ビルド用の
  // 仮想エントリを合成し、`import.meta.glob(['/app/server.ts', ...], { import: 'default', eager: true })`
  // で各エントリファイルの **default export のみ** を取り込む
  // （`node_modules/@hono/vite-build/dist/entry/index.mjs` 参照）。そのため `app/server.ts` の
  // `export { Board, Thread }`（named export）は仮想エントリのどこからも
  // 再exportされず、最終 `dist/index.js` に現れない（素朴に `buildCloudflareWorkers()` だけを
  // 呼ぶと、DOクラスを持たない `dist/index.js` が生成され、`wrangler.jsonc` の
  // `durable_objects.bindings` が指す `class_name` を解決できずローカル実行が壊れる）。
  // → `entryContentAfterHooks`（既定のフック配列にappendする。置き換えるとプリセット既定の
  // 他の挙動を失うため）で `app/server.ts` からの named re-export 文を仮想エントリへ追加する。
  // あわせて `cloudflare:workers`（Workersランタイム組込みモジュール。npmには存在しない）を
  // externalに指定してRollupにバンドルさせない。
  const entryContentAfterHooks = [
    ...(cloudflareWorkersDefaultOptions.entryContentAfterHooks ?? []),
    () =>
      `export { Board, Thread } from '/app/server.ts'`,
  ];

  return {
    resolve: {
      alias: { "scalajs:client.js": "scalajs-client:client.js" },
    },
    plugins: [
      honox({ islandComponents: { reactApiImportSource: "react" } }),
      buildCloudflareWorkers({
        external: ["cloudflare:workers"],
        entryContentAfterHooks,
      }),
      scalaProject("bbs"),
      scalaProject("bbsClient", "scalajs-client"),
    ],
  };
});
