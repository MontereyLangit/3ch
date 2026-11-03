import path from "node:path";
import { defineConfig, type Plugin } from "vite";
import honox from "honox/vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import tailwindcss from "@tailwindcss/vite";

const scalaCwd = path.resolve(import.meta.dirname);

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

  // 既定のフック配列にDurable Objectの再exportをappend
  // （cloudflare:workersはexternalに指定しRollupにバンドルさせない）
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
