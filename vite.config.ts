import path from "node:path";
import { defineConfig } from "vite";
import buildCloudflareWorkers, {
  defaultOptions as cloudflareWorkersDefaultOptions,
} from "@hono/vite-build/cloudflare-workers";
import tailwindcss from "@tailwindcss/vite";
import { scalaHonoxClient, scalaHonoxServer, type ScalaHonoxOptions } from "./vite/scalajs-honox";

const scala: ScalaHonoxOptions = {
  cwd: path.resolve(import.meta.dirname),
  serverProjectID: "bbs",
  clientProjectID: "bbsClient",
};

export default defineConfig(({ mode }) => {
  if (mode === "client") {
    const client = scalaHonoxClient(scala, ["./app/client.ts"]);
    return {
      plugins: [tailwindcss(), ...client.scalaPlugins],
      build: client.build,
    };
  }

  // 既定のフック配列にDurable Objectの再exportをappend
  // （cloudflare:workersはexternalに指定しRollupにバンドルさせない）
  const entryContentAfterHooks = [
    ...(cloudflareWorkersDefaultOptions.entryContentAfterHooks ?? []),
    () =>
      `export { Board, Thread, Account, AccountIndex, AuthRate } from '/app/server.ts'`,
  ];

  const server = scalaHonoxServer(scala);
  return {
    resolve: { alias: server.alias },
    plugins: [
      ...server.honoxPlugins,
      buildCloudflareWorkers({
        external: ["cloudflare:workers"],
        entryContentAfterHooks,
      }),
      ...server.scalaPlugins,
    ],
  };
});
