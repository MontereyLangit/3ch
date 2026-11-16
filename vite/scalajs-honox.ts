import { type BuildEnvironmentOptions, type Plugin, type PluginOption } from "vite";
import honox from "honox/vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";

/** Scala.js × HonoX のビルド設定。プロジェクト固有の値はすべて引数で受け取る */
export interface ScalaHonoxOptions {
  /** sbt を実行するディレクトリ */
  cwd: string;
  /** Worker 側（@route を持つ）sbt プロジェクトの ID */
  serverProjectID: string;
  /** ブラウザ側（@islands を持つ）sbt プロジェクトの ID */
  clientProjectID: string;
  /** client のリンク成果物の @JSExportTopLevel moduleID。既定は "client" */
  clientModule?: string;
  /** SSR で client のリンク成果物を指す仮想モジュールの接頭辞。既定は "scalajs-client" */
  clientUriPrefix?: string;
}

/** sbt の Scala.js プロジェクトをリンクするプラグイン。複数のプロジェクトを順にリンクさせる */
export function scalaProject(cwd: string, projectID: string, uriPrefix?: string): Plugin {
  const plugin = scalaJSPlugin({ cwd, projectID, uriPrefix });
  const buildStart = plugin.buildStart;
  if (buildStart) {
    plugin.buildStart = {
      sequential: true,
      handler: typeof buildStart === "function" ? buildStart : buildStart.handler,
    };
  }
  return plugin;
}

/** Worker（SSR）ビルド用。HonoX の island 変換と、server・client 両方の Scala.js リンクを返す */
export function scalaHonoxServer(options: ScalaHonoxOptions): {
  honoxPlugins: PluginOption[];
  scalaPlugins: Plugin[];
  alias: Record<string, string>;
} {
  const clientModule = `${options.clientModule ?? "client"}.js`;
  const clientUriPrefix = options.clientUriPrefix ?? "scalajs-client";
  return {
    honoxPlugins: [honox({ islandComponents: { reactApiImportSource: "react" } })],
    scalaPlugins: [
      scalaProject(options.cwd, options.serverProjectID),
      scalaProject(options.cwd, options.clientProjectID, clientUriPrefix),
    ],
    alias: { [`scalajs:${clientModule}`]: `${clientUriPrefix}:${clientModule}` },
  };
}

/** ブラウザ向けビルド用。client の Scala.js リンクと、static/ 配下への出力設定を返す */
export function scalaHonoxClient(options: ScalaHonoxOptions, input: string[]): {
  scalaPlugins: Plugin[];
  build: BuildEnvironmentOptions;
} {
  return {
    scalaPlugins: [scalaProject(options.cwd, options.clientProjectID)],
    build: {
      rollupOptions: {
        input,
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
