package ch3s.codegen

import ch3s.common.routes.RouteTable
import munit.FunSuite
import scala.meta.*

class GenerateIslandComponentsSuite extends FunSuite {
  test("アノテーション由来のSSR配線とクライアントエントリはexportとHonoX識別子を共有する") {
    val entries = AnnotatedAppEntries.parse("Main.scala", """
      object Main {
        @islands("thread/counter.tsx")
        @JSExportTopLevel("CounterJs", "client")
        val counter = component

        @islands("panel.tsx")
        @JSExportTopLevel("match", "client")
        val panel = component
      }
    """, "client").islands
    val source = GenerateIslandComponents.render(entries)
    val tree = dialects.Scala3(source).parse[Source].get
    val api = tree.collect { case obj: Defn.Object if obj.name.value == "IslandComponents" => obj }.head
    val methods = api.templ.body.stats.collect { case method: Defn.Def => method }
    assertEquals(methods.map(_.name.value), entries.map(_.exportedName))
    entries.zip(methods).foreach { case (entry, method) =>
      val identifier = method.body.collect { case Lit.String(value) => value }
      assertEquals(identifier, List(s"/app/islands/${entry.filePath}"))
      assert(source.contains(s"@JSImport(\"scalajs:${entry.sourceModule}\", \"${entry.exportedName}\")"))
      assert(RouteTable.renderIslandFile(entry).contains(s"export default ${entry.exportedName};"))
    }
    assert(!source.contains("@JSImport(\"/app/islands"))
  }

  test("アノテーションが空なら以前のサーバimportと配線をすべて消す") {
    val source = GenerateIslandComponents.render(Nil)
    val tree = dialects.Scala3(source).parse[Source].get
    assertEquals(tree.collect { case obj: Defn.Object => obj.name.value }, List("IslandComponents"))
    assert(!source.contains("@JSImport"))
  }

  test("1つのサーバメソッドに複数のislandファイルが曖昧に対応すると失敗する") {
    intercept[IllegalArgumentException] {
      GenerateIslandComponents.render(List(
        RouteTable.IslandEntry("first.tsx", "Widget", "client.js"),
        RouteTable.IslandEntry("second.tsx", "Widget", "client.js")
      ))
    }
  }
}
