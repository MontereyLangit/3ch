package ch3s.codegen

import ch3s.common.routes.RouteTable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
 * `RouteTable`（common, cross-build）を元に `app/routes/配下` を機械生成するsbtタスクの本体。
 * 参照実装 ch3s-migration の `ch3s.codegen.GenerateAppFiles` を踏襲する。
 *
 * JVM専用プロジェクト（`codegen`）に置く理由は、`bbs`/`bbs-client` がScala.js専用
 * （Workers向けESModule出力）でファイルI/Oを素直に書けないため。
 *
 * 起動方法: `sbt codegen/run`（`package.json` の `npm run v6:generate` から呼ばれる）。
 * 実行時のカレントディレクトリ（`user.dir`）がこのリポジトリルート（`ch3s/`）であることを前提にする。
 */
object GenerateAppFiles {

  def main(args: Array[String]): Unit = {
    val projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    val appRoot = projectRoot.resolve("app")

    val routeFiles = RouteTable.routes.map { entry =>
      val path = resolveUnderApp(appRoot, "routes", entry.filePath)
      writeFile(path, RouteTable.renderRouteFile(entry))
      path
    }

    println(s"[codegen] RouteTableから ${routeFiles.size} 件のルートファイルを生成した")
  }

  /** `"_v6/health.ts"` のような `/` 区切りの相対パスを `appRoot/subDir/...` へ安全に解決する。 */
  private def resolveUnderApp(appRoot: Path, subDir: String, relativeSlashPath: String): Path = {
    relativeSlashPath.split("/").foldLeft(appRoot.resolve(subDir))((acc, segment) => acc.resolve(segment))
  }

  private def writeFile(path: Path, content: String): Unit = {
    Files.createDirectories(path.getParent)
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    println(s"[codegen] wrote ${path.toString}")
  }
}
