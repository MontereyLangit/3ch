package ch3s.codegen

import ch3s.common.routes.RouteTable
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

  test("regeneration removes stale owned files and preserves handwritten routes") {
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

  test("handwritten collisions fail before cleanup or writes") {
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

  test("Scala island bindings preserve handwritten files and replace former generated entries") {
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
