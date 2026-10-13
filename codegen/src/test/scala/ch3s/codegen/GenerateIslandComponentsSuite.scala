package ch3s.codegen

import ch3s.common.routes.RouteTable
import munit.FunSuite
import scala.meta.*

class GenerateIslandComponentsSuite extends FunSuite {
  test("annotation-derived SSR bindings and client entries share exports and HonoX identifiers") {
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

  test("empty annotations clear all former server imports and bindings") {
    val source = GenerateIslandComponents.render(Nil)
    val tree = dialects.Scala3(source).parse[Source].get
    assertEquals(tree.collect { case obj: Defn.Object => obj.name.value }, List("IslandComponents"))
    assert(!source.contains("@JSImport"))
  }

  test("ambiguous multiple island files for one server method fail") {
    intercept[IllegalArgumentException] {
      GenerateIslandComponents.render(List(
        RouteTable.IslandEntry("first.tsx", "Widget", "client.js"),
        RouteTable.IslandEntry("second.tsx", "Widget", "client.js")
      ))
    }
  }
}
