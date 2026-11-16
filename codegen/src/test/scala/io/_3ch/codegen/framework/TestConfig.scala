package io._3ch.codegen.framework

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, LinkOption, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** framework のテスト用設定。3ch の値を使わない */
object TestConfig {
  def apply(root: Path = Paths.get("unused")): CodegenConfig = CodegenConfig(
    projectRoot = root,
    serverSources = List("server/App.scala"),
    clientSources = List("client/App.scala"),
    serverModule = "server",
    clientModule = "client",
    routeDir = "app/routes",
    islandDir = "app/islands",
    islandIdPrefix = "/app/islands/",
    scalaOutput = None,
    scalaPackage = "example.generated",
    scalaObject = "Islands",
    islandBridge = "example.bridge.Island",
    annotationPackage = "example.annotations",
    virtualModulePrefix = "scalajs:",
    banner = List("// generated-by-test", "// do not edit"),
    generatedMarker = "// generated-by-test"
  )

  def withTempDir[A](test: Path => A): A = {
    val root = Files.createTempDirectory("codegen-framework-test-")
    try { test(root) }
    finally { delete(root) }
  }

  /** リンクとジャンクションは辿らずにリンク自体を消す */
  private def delete(path: Path): Unit = {
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    if (attributes.isDirectory && !attributes.isOther) Using.resource(Files.list(path))(_.iterator().asScala.toList).foreach(delete)
    path.toFile.setWritable(true)
    Files.delete(path)
  }
}
