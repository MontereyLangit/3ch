package io._3ch.admin

import io._3ch.admin.model.BoardSettingsLayout
import io._3ch.common.admin.BoardSettingsForm
import munit.FunSuite

class BoardSettingsLayoutSuite extends FunSuite {
  test("画面はフォームの全項目をちょうど一度ずつ出す") {
    val keys = BoardSettingsLayout.keys
    assertEquals(keys.distinct, keys)
    assertEquals(keys.toSet, BoardSettingsForm.keys)
  }

  test("連投規制の照合キーは空ならすべて、知らないキーは無視し、決まった順で書く") {
    assertEquals(BoardSettingsLayout.selectedKeys(""), Set("ip", "shiki", "aid"))
    assertEquals(BoardSettingsLayout.selectedKeys(" aid , id ,ip"), Set("ip", "aid"))
    assertEquals(BoardSettingsLayout.keysValue(Set("aid", "ip")), "ip,aid")
  }
}
