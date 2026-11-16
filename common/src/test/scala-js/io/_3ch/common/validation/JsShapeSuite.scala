package io._3ch.common.validation

import io._3ch.common.validation.JsShape.{optional, required}
import munit.FunSuite

import scala.scalajs.js

class JsShapeSuite extends FunSuite {
  private trait Item extends js.Object {
    val name: String
  }
  private trait Probe extends js.Object {
    val label: String
    val count: Double
    val enabled: js.UndefOr[Boolean]
    val items: js.Array[Item]
  }
  private val fields = List(
    required("label", JsKind.Str), required("count", JsKind.Num), optional("enabled", JsKind.Bool),
    required("items", JsKind.Arr(JsKind.Obj(List(required("name", JsKind.Str)))))
  )
  private def parse(json: String): js.Any = js.JSON.parse(json)

  test("すべてのキーがそろえば型付きの値として読める。optional のキーは省ける") {
    val probe = JsShape.validate[Probe](parse("""{"label":"a","count":2,"items":[{"name":"x"}]}"""), fields).toOption.get
    assertEquals(probe.label, "a")
    assertEquals(probe.count, 2.0)
    assert(probe.enabled.isEmpty)
    assertEquals(probe.items.map(_.name).toList, List("x"))
  }

  test("欠けたキー・型違い・入れ子の誤りをまとめてパス付きで返す") {
    val result = JsShape.validate[Probe](parse("""{"count":"2","enabled":1,"items":[{"name":3},{}]}"""), fields)
    assertEquals(result, Left(List(
      "props.label: missing", "props.count: expected number, got string", "props.enabled: expected boolean, got number",
      "props.items[0].name: expected string, got number", "props.items[1].name: missing"
    ).mkString("; ")))
  }

  test("null・配列・プリミティブはオブジェクトとして扱わない") {
    List("null", "[]", "1", "\"x\"").foreach { json =>
      assert(JsShape.validate[Probe](parse(json), fields).isLeft, json)
    }
    assert(JsShape.validate[Probe](parse("""{"label":"a","count":1,"items":{}}"""), fields).isLeft)
  }

  test("read は失敗を console.error に出して None を返し、例外にしない") {
    val console = js.Dynamic.global.console
    val original = console.error
    val messages = js.Array[String]()
    console.error = ((message: String) => { messages.push(message); () }): js.Function1[String, Unit]
    try {
      assertEquals(JsShape.read[Probe]("ProbeJs", parse("{}"), fields), None)
      assertEquals(messages.toList.size, 1)
      assert(messages(0).startsWith("[ProbeJs] invalid props: props.label: missing"), messages(0))
    } finally {
      console.error = original
    }
  }
}
