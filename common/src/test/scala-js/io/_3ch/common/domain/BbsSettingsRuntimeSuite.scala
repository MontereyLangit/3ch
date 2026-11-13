package io._3ch.common.domain

import scala.collection.immutable.ListMap
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** スレ設定の保存形式のキー順が JavaScript のオブジェクトと一致するか */
class BbsSettingsRuntimeSuite extends munit.FunSuite {
  test("toThreadSettings のキー順は Object.keys と同じ") {
    val keys = List("B", "10", "01", "2", "4294967295", "4294967294", "-1", "0", "1.0", "1e3", " 3", "BBS_TITLE", "007", "9999999999")
    val settings = BbsSettings(ListMap.from(keys.map(k => k -> "v")))
    val dictionary = settings.value.toJSDictionary
    val expected = js.Object.keys(dictionary.asInstanceOf[js.Object]).map(k => k + "=v<>").mkString
    assertEquals(settings.toThreadSettings, expected)
  }
}
