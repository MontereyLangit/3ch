package ch3s.codegen

import ch3s.common.routes.RouteTable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** 
 * RouteTable からルートを、DO の Scala 宣言から TS 型を生成する。
 * リポジトリルートで `sbt codegen/run` を実行する。
 */
object GenerateAppFiles {

  def main(args: Array[String]): Unit = {
    val projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    val appRoot = projectRoot.resolve("app")
    val durableRoot = projectRoot.resolve("bbs/src/main/scala/ch3s/bbs/durable")
    val files = Files.walk(durableRoot)
    val sources = try {
      files.iterator().asScala.filter(_.toString.endsWith(".scala")).toList.sortBy(_.toString).map { path =>
        path.toString -> Files.readString(path, StandardCharsets.UTF_8)
      }
    } finally {
      files.close()
    }
    writeFile(appRoot.resolve("scalajs-durable-objects.generated.d.ts"), GenerateDurableObjectTypes.render(sources))

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
