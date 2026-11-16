package io._3ch.codegen.framework

import java.nio.file.{Files, Path, Paths}
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
    finally {
      Using.resource(Files.walk(root)) { paths => paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete) }
    }
  }
}
