import { defineConfig } from "vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import tailwindcss from "@tailwindcss/vite";
import hx4s from "hx4s-vite";

// client: dist/static/client.js・dist/static/assets/*（Workers Static Assets で配信）、workers: dist/index.js
export default defineConfig(({ mode }) => {
  const plugins = hx4s({
    mode,
    sbt: { cwd: import.meta.dirname },
    server: { projectID: "bbs" },
    client: { projectID: "bbsClient" },
  });
  if (mode === "client") {
    return { plugins: [tailwindcss(), ...plugins] };
  }

  // 既定のフック配列にDurable Objectの再exportをappend
  // （cloudflare:workersはexternalに指定しRollupにバンドルさせない）
  const entryContentAfterHooks = [
    ...(cloudflareWorkersDefaultOptions.entryContentAfterHooks ?? []),
    () =>
      `export { Board, Thread, Account, AccountIndex, AuthRate } from '/app/server.ts'`,
  ];

  return {
    plugins: [
      ...plugins,
      buildCloudflareWorkers({
        external: ["cloudflare:workers"],
        entryContentAfterHooks,
      }),
    ],
  };
});
