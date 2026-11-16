package io._3ch.codegen.framework

import io._3ch.codegen.framework.TestConfig.withTempDir
import munit.FunSuite

import java.nio.file.{Files, Path}

class CodegenMainSuite extends FunSuite {
  private def args(root: Path, extra: String*): List[String] = List(
    "--project-root", root.toString,
    "--server-sources", "server/App.scala",
    "--client-sources", "client/App.scala",
    "--route-dir", "app/routes",
    "--island-dir", "app/islands",
    "--island-id-prefix", "/app/islands/",
    "--scala-package", "example.generated",
    "--scala-object", "Islands",
    "--island-bridge", "example.bridge.Island",
    "--annotation-package", "example.annotations",
    "--banner", "// generated-by-test",
    "--banner", "// do not edit",
    "--generated-marker", "// generated-by-test"
  ) ++ extra

  test("--key value の引数から設定を組み立て、省いた moduleID と仮想モジュール接頭辞は既定値にする") {
    withTempDir { root =>
      val parsed = CodegenMain.parse("island-components" :: args(root, "--scala-output", "out/Islands.scala"))
      assertEquals(parsed, Right(CodegenMain.Command.IslandComponents -> TestConfig(root.toAbsolutePath.normalize())
        .copy(scalaOutput = Some(java.nio.file.Paths.get("out/Islands.scala")))))
    }
  }

  test("ソースはカンマ区切りでも繰り返しでも指定できる") {
    withTempDir { root =>
      val parsed = CodegenMain.parse("app" :: args(root, "--server-sources", "a.scala, b.scala", "--server-sources", "c.scala"))
      assertEquals(parsed.map(_._2.serverSources), Right(List("server/App.scala", "a.scala", "b.scala", "c.scala")))
    }
  }

  test("不明なキー・値の欠落・必須キーの欠落・重複はまとめて誤りにする") {
    withTempDir { root =>
      val found = CodegenMain.parse(List("build", "--unknown", "x", "--route-dir")).left.getOrElse(fail("expected errors"))
      assertEquals(found.map(_.message), List("expected command app or island-components, found build", "unknown option --unknown",
        "missing value for --route-dir"))
      val missing = CodegenMain.parse(List("app", "--scala-object", "A", "--scala-object", "B")).left.getOrElse(fail("expected errors"))
      assert(missing.exists(_.message == "option --scala-object is given more than once"), missing)
      assert(missing.exists(_.message == "missing option --scala-package"), missing)
      assert(missing.exists(_.message == "missing option --server-sources"), missing)
      assert(!missing.exists(_.message == "missing option --route-dir"), "route-dir には既定値がある")
    }
  }

  test("省いたキーは CodegenConfig.default の HonoX の既定にする") {
    withTempDir { root =>
      val minimal = List("app", "--project-root", root.toString, "--server-sources", "server/App.scala", "--client-sources", "client/App.scala",
        "--scala-package", "example.generated", "--island-bridge", "example.bridge.Island", "--annotation-package", "example.annotations")
      val expected = CodegenConfig.default(root.toAbsolutePath.normalize(), List("server/App.scala"), List("client/App.scala"),
        "example.generated", "example.bridge.Island", "example.annotations")
      assertEquals(CodegenMain.parse(minimal), Right(CodegenMain.Command.App -> expected))
      assertEquals((expected.routeDir, expected.islandDir, expected.islandIdPrefix, expected.serverModule, expected.clientModule,
        expected.virtualModulePrefix, expected.scalaObject), ("app/routes", "app/islands", "/app/islands/", "server", "client", "scalajs:", "IslandComponents"))
      assert(expected.banner.head.startsWith(expected.generatedMarker))
    }
  }

  test("空のマーカーやバナーの 1 行目がマーカーで始まらない設定は、引数の解釈でも生成でも拒否する") {
    withTempDir { root =>
      val base = args(root)
      val withEmptyMarker = base.updated(base.lastIndexOf("--generated-marker") + 1, "")
      val emptyMarker = CodegenMain.parse("app" :: withEmptyMarker).left.getOrElse(fail("expected errors"))
      assert(emptyMarker.exists(_.message.contains("generatedMarker must not be empty")), emptyMarker)
      val mismatch = TestConfig(root).copy(banner = List("// other banner"))
      assert(Codegen.validate(mismatch).left.exists(_.exists(_.message.contains("must start with generatedMarker"))))
      Files.createDirectories(root.resolve("app/routes"))
      Files.writeString(root.resolve("app/routes/manual.ts"), "// handwritten\nexport default 1;\n")
      assert(Codegen.appFiles(TestConfig(root).copy(generatedMarker = "", banner = List("// x"))).isLeft)
      assertEquals(Files.readString(root.resolve("app/routes/manual.ts")), "// handwritten\nexport default 1;\n")
    }
  }

  test("汎用の設定だけで route / island と SSR 用 API を生成し、2 回目は書き直さない") {
    withTempDir { root =>
      Files.createDirectories(root.resolve("server"))
      Files.createDirectories(root.resolve("client"))
      Files.writeString(root.resolve("server/App.scala"),
        """object Handlers { @route("hello.ts") @route("hello.ts", "POST") @JSExportTopLevel("hello", "server") def hello(c: Any) = c }""")
      Files.writeString(root.resolve("client/App.scala"),
        """object Widgets { @islands[example.WidgetProps]("widget.tsx") @JSExportTopLevel("WidgetJs", "client") val widget = 1 }""")
      val (_, config) = CodegenMain.parse("app" :: args(root, "--scala-output", "out/Islands.scala")).toOption.get
      assertEquals(Codegen.appFiles(config).map(s => (s.written.size, s.unchanged.size)), Right((2, 0)))
      assertEquals(Codegen.appFiles(config).map(s => (s.written.size, s.unchanged.size)), Right((0, 2)))
      assert(Files.readString(root.resolve("app/routes/hello.ts")).contains("export const POST = createRoute((c) => hello(c));"))
      assert(Files.readString(root.resolve("app/islands/widget.tsx")).contains("export default WidgetJs;"))
      assertEquals(Codegen.islandComponents(config).map(_.written.size), Right(1))
      assert(Files.readString(root.resolve("out/Islands.scala")).contains("def `WidgetJs`(props: _root_.example.WidgetProps)"))
    }
  }
}
