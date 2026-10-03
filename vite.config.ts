import path from "node:path";
import { defineConfig } from "vite";
import honox from "honox/vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import tailwindcss from "@tailwindcss/vite";

// v6s ビルド骨格（docs/移植方針_step3_ScalaJS.md）。参照実装 C:/project/3ch/ch3s-migration の
// vite.config.ts をベースにする。参照実装には `sequentialBuildStart`（2つの scalaJSPlugin
// インスタンスが同時に `sbt` を起動して `ServerAlreadyBootingException` になる問題への対策として
// 定義された関数）が残っているが、実際にはどちらの config でも呼ばれておらず（grep で確認済み）、
// 現在有効な対策は「client/serverを単一sbtプロジェクト(bbs)に統合し、@JSExportTopLevelの
// moduleIDだけで振り分ける」方（build.sbt参照）。そちらだけを踏襲し、死んだコードは持ち込まない。

// sbtビルドはこのVite/HonoXプロジェクトと同じルート（ch3s直下）にある。
const scalaCwd = path.resolve(__dirname);

export default defineConfig(({ mode }) => {
  if (mode === "client") {
    return {
      plugins: [tailwindcss(), scalaJSPlugin({ cwd: scalaCwd, projectID: "bbs" })],
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
  // `export { V6PlaceholderDurableObject }`（named export）は仮想エントリのどこからも
  // 再exportされず、最終 `dist/index.js` に現れない（素朴に `buildCloudflareWorkers()` だけを
  // 呼ぶと、DOクラスを持たない `dist/index.js` が生成され、`wrangler.jsonc` の
  // `durable_objects.bindings` が指す `class_name` を解決できずローカル実行が壊れる）。
  // → `entryContentAfterHooks`（既定のフック配列にappendする。置き換えるとプリセット既定の
  // 他の挙動を失うため）で `app/server.ts` からの named re-export 文を仮想エントリへ追加する。
  // あわせて `cloudflare:workers`（Workersランタイム組込みモジュール。npmには存在しない）を
  // externalに指定してRollupにバンドルさせない。
  const entryContentAfterHooks = [
    ...(cloudflareWorkersDefaultOptions.entryContentAfterHooks ?? []),
    () => `export { V6PlaceholderDurableObject, Board, Thread } from '/app/server.ts'`,
  ];

  return {
    plugins: [
      honox(),
      buildCloudflareWorkers({
        external: ["cloudflare:workers"],
        entryContentAfterHooks,
      }),
      scalaJSPlugin({ cwd: scalaCwd, projectID: "bbs" }),
    ],
  };
});
