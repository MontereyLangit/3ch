package io._3ch.codegen

import io._3ch.common.routes.RouteTable
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite

class GenerateAppFilesSuite extends FunSuite {
  private def withApp(test: Path => Unit): Unit = {
    val root = Files.createTempDirectory("ch3s-codegen-test-")
    try { test(root) }
    finally {
      val paths = Files.walk(root)
      try { paths.iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(Files.delete) }
      finally { paths.close() }
    }
  }

  private val generated = RouteTable.renderRouteFile(
    RouteTable.RouteEntry("probe.ts", RouteTable.RouteKind.PassThrough("probe"), Nil)
  )

  test("再生成は古い生成ファイルを削除し、手書きルートは残す") {
    withApp { root =>
      val routes = Files.createDirectories(root.resolve("routes"))
      val stale = routes.resolve("stale.ts")
      val manual = routes.resolve("index.ts")
      val fresh = routes.resolve("fresh.ts")
      Files.writeString(stale, generated)
      Files.writeString(manual, "export default manual;\n")
      GenerateAppFiles.writeOutputs(root, List(fresh -> generated))
      assert(!Files.exists(stale))
      assertEquals(Files.readString(manual), "export default manual;\n")
      assertEquals(Files.readString(fresh), generated)
    }
  }

  test("手書きファイルとの衝突は削除・書き込みの前に失敗する") {
    withApp { root =>
      val routes = Files.createDirectories(root.resolve("routes"))
      val stale = routes.resolve("stale.ts")
      val manual = routes.resolve("index.ts")
      Files.writeString(stale, generated)
      Files.writeString(manual, "handwritten")
      intercept[IllegalArgumentException](GenerateAppFiles.writeOutputs(root, List(manual -> generated)))
      assertEquals(Files.readString(manual), "handwritten")
      assert(Files.exists(stale))
    }
  }

  test("パッケージ名変更前の生成ファイルも置換・削除できる") {
    withApp { root =>
      val routes = Files.createDirectories(root.resolve("routes"))
      val stale = routes.resolve("stale.ts")
      val current = routes.resolve("current.ts")
      val legacy = generated.replace("io._3ch.codegen.GenerateAppFiles", "ch3s.codegen.GenerateAppFiles")
      assertNotEquals(legacy, generated)
      Files.writeString(stale, legacy)
      Files.writeString(current, legacy)
      GenerateAppFiles.writeOutputs(root, List(current -> generated))
      assert(!Files.exists(stale))
      assertEquals(Files.readString(current), generated)
    }
  }

  test("Scala islandの配線は手書きファイルを残し、以前の生成エントリを置き換える") {
    withApp { root =>
      val scalaFile = root.resolve("IslandComponents.scala")
      val content = GenerateIslandComponents.render(Nil)
      Files.writeString(scalaFile, "object Handwritten {}\n")
      intercept[IllegalArgumentException](GenerateAppFiles.writeOutputs(root, List(scalaFile -> content)))
      assertEquals(Files.readString(scalaFile), "object Handwritten {}\n")
      Files.writeString(scalaFile, GenerateIslandComponents.render(List(
        RouteTable.IslandEntry("old.tsx", "OldWidget", "client.js")
      )))
      GenerateAppFiles.writeOutputs(root, List(scalaFile -> content))
      assertEquals(Files.readString(scalaFile), content)
    }
  }
}
