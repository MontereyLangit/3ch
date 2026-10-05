package ch3s.codegen

class GenerateDurableObjectTypesSuite extends munit.FunSuite {
  private def render(source: String): String =
    GenerateDurableObjectTypes.render(Seq("Example.scala" -> source))

  test("export names and multiline constructor declarations follow the Scala source") {
    val result = render("""
      // @JSExportTopLevel("NotAClass", "server")
      @JSExportTopLevel(
        "PublicBoard", "server"
      )
      class InternalBoard(
        val state: DurableObjectState,
        env: js.Any,
        generation: Int
      ) extends DurableObject(state, env) {
        val example = "@JSExportTopLevel(\"NotAnExport\", \"server\")"
      }
      @JSExportTopLevel("Thread", "server")
      class Thread(state: DurableObjectState, env: WorkerEnv) extends DurableObject(state, env)
    """)
    assert(result.contains("export class PublicBoard"))
    assert(result.contains("constructor(state: object, env: unknown, generation: number);"))
    assert(result.contains("constructor(state: object, env: object);"))
    assert(!result.contains("NotAClass"))
    assert(!result.contains("NotAnExport"))
    assert(!result.contains("InternalBoard"))
  }

  test("other modules and non-exported classes do not leak into the declarations") {
    val result = render("""
      class Helper
      @JSExportTopLevel("Client", "client")
      class Client
      object Main { @JSExportTopLevel("method", "server") def method(): Int = 1 }
    """)
    assert(!result.contains("export class"))
  }

  test("regeneration drops removed classes and is independent of file ordering") {
    def source(name: String): (String, String) = name ->
      s"""@JSExportTopLevel("$name", "server")
      class $name(state: DurableObjectState, env: js.Any) extends DurableObject(state, env)"""
    val sources = Seq(source("Z"), source("A"))
    assertEquals(GenerateDurableObjectTypes.render(sources), GenerateDurableObjectTypes.render(sources.reverse))
    assert(!GenerateDurableObjectTypes.render(sources.tail).contains("class Z"))
  }

  test("unsupported parameter types fail instead of silently weakening the declaration") {
    intercept[IllegalArgumentException] {
      render("""@JSExportTopLevel("Board", "server")
        class Board(state: DurableObjectState, env: NewEnvironment) extends DurableObject(state, env)""")
    }
  }

  test("duplicate exports and nonliteral names are rejected") {
    val source = """@JSExportTopLevel("Board", "server")
      class Board(state: DurableObjectState, env: js.Any) extends DurableObject(state, env)"""
    intercept[IllegalArgumentException] {
      GenerateDurableObjectTypes.render(Seq("a.scala" -> source, "b.scala" -> source))
    }
    intercept[IllegalArgumentException] {
      render(source.replace("\"Board\"", "exportName"))
    }
  }
}
