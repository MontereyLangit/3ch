import path from "node:path";
import { defineConfig } from "vite";
import honox from "honox/vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import tailwindcss from "@tailwindcss/vite";

const scalaCwd = path.resolve(__dirname);

export default defineConfig(({ mode }) => {
  if (mode === "client") {
    return {
      plugins: [
        tailwindcss(),
        scalaJSPlugin({ cwd: scalaCwd, projectID: "bbsClient" }),
      ],
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

  // 既定のフック配列にDurable Objectの再exportをappend
  // （cloudflare:workersはexternalに指定しRollupにバンドルさせない）
  const entryContentAfterHooks = [
    ...(cloudflareWorkersDefaultOptions.entryContentAfterHooks ?? []),
    () =>
      `export { V6PlaceholderDurableObject, Board, Thread } from '/app/server.ts'`,
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
