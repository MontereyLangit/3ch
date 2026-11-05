package io._3ch.codegen

import io._3ch.common.routes.RouteTable
import munit.FunSuite

class AnnotatedAppEntriesSuite extends FunSuite {
  private def parse(members: String, module: String = "server") =
    AnnotatedAppEntries.parse("Main.scala", s"object Main { $members }", module)

  test("exportがハンドラ名を決め、同一ファイルのメソッドはまとめられる") {
    val entries = parse("""
      @route("probe.ts") @JSExportTopLevel("readProbe", "server") def read(c: Any) = c
      @route(filePath = "probe.ts", method = "POST")
      @JSExportTopLevel(name = "writeProbe", moduleID = "server") def write(c: Any) = c
      @JSExportTopLevel("unrouted", "server") def helper() = 1
    """)
    assertEquals(entries.routes.size, 1)
    val rendered = RouteTable.renderRouteFile(entries.routes.head)
    assert(rendered.contains("export default createRoute((c) => readProbe(c));"))
    assert(rendered.contains("export const POST = createRoute((c) => writeProbe(c));"))
    assert(!rendered.contains("unrouted"))
  }

  test("islandは隣接する宣言からexportとソースモジュールを導出する") {
    val entries = parse("""@islands("thread/view.tsx") @JSExportTopLevel("ThreadView", "client") val view = 1""", "client")
    assertEquals(entries.islands, List(RouteTable.IslandEntry("thread/view.tsx", "ThreadView", "client.js")))
  }

  test("メタデータはリテラル、モジュールは正しいものが必須") {
    List(
      """@route(path) @JSExportTopLevel("x", "server") def x() = 1""",
      """@route("x.ts") def x() = 1""",
      """@route("x.ts") @JSExportTopLevel("x", "client") def x() = 1""",
      """@route("x.ts") @JSExportTopLevel(name, "server") def x() = 1""",
      """@route("x.ts", "BREW") @JSExportTopLevel("x", "server") def x() = 1""",
      """@islands("x.tsx") @JSExportTopLevel("x", "server") val x = 1"""
    ).foreach(source => intercept[IllegalArgumentException](parse(source)))
  }

  test("危険なパスは生成ディレクトリの外へ出られない") {
    List("../x.ts", "/x.ts", "C:/x.ts", "a//x.ts", "a/./x.ts", "x.tsx", "a\\x.ts", "x\".ts").foreach { path =>
      val literal = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      intercept[IllegalArgumentException](parse(s"@route($literal) @JSExportTopLevel(\"x\", \"server\") def x() = 1"))
    }
  }

  test("ルートメソッドとislandファイル名の重複は拒否される") {
    intercept[IllegalArgumentException](parse("""
      @route("x.ts") @JSExportTopLevel("x", "server") def x() = 1
      @route("X.ts", "POST") @JSExportTopLevel("y", "server") def y() = 1
    """))
    intercept[IllegalArgumentException](parse("""
      @route("x.ts") @JSExportTopLevel("x", "server") def x() = 1
      @route("x.ts", "GET") @JSExportTopLevel("y", "server") def y() = 1
    """))
    intercept[IllegalArgumentException](parse("""
      @islands("x.tsx") @JSExportTopLevel("X", "client") val x = 1
      @islands("X.tsx") @JSExportTopLevel("Y", "client") val y = 1
    """, "client"))
  }

  test("islandのファイル名はHonoXが検出できるものでなければならない") {
    List("_hidden.tsx", "thread.view.tsx", "[id].tsx").foreach { path =>
      intercept[IllegalArgumentException](parse(s"@islands(\"$path\") @JSExportTopLevel(\"X\", \"client\") val x = 1", "client"))
    }
  }
}
