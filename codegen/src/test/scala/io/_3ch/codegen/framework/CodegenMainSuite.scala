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
      assert(missing.exists(_.message == "missing option --route-dir"), missing)
      assert(missing.exists(_.message == "missing option --server-sources"), missing)
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
