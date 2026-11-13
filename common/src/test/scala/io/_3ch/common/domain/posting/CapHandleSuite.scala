package io._3ch.common.domain.posting

class CapHandleSuite extends munit.FunSuite {
  test("キャップは名前の有無で区切りを変え、末尾の星で強キャップを判定する") {
    val weak = CapHandle.fromStored("管理人").get
    assert(!weak.strong)
    assertEquals(weak.displayName("投稿者"), "投稿者＠管理人 ★")
    assertEquals(weak.displayName(""), "管理人 ★")
    val strong = CapHandle.fromStored("管理人★").get
    assert(strong.strong)
    assertEquals(strong.displayName(""), "管理人 ★")
    assertEquals(CapHandle.fromStored(""), None)
  }

  test("強キャップは最初の星から改行までだけ削除する") {
    assertEquals(CapHandle.fromStored("a★b★"), Some(CapHandle("a", strong = true)))
    for (newline <- List("\n", "\r", "\u2028", "\u2029")) {
      assertEquals(CapHandle.fromStored("a★b" + newline + "c★"), Some(CapHandle("a" + newline + "c★", strong = true)))
      assertEquals(CapHandle.fromStored("a★" + newline), Some(CapHandle("a★" + newline, strong = false)))
    }
    assertEquals(CapHandle.fromStored("★"), Some(CapHandle("", strong = true)))
  }

  test("メールの最初の # に続く一項目だけを読む") {
    assertEquals(CapHandle.password("sage#pass#ignored"), Some("pass"))
    assertEquals(CapHandle.password("#!legacy"), Some("!legacy"))
    assertEquals(CapHandle.password("sage##pass"), None)
    assertEquals(CapHandle.password("sage#"), None)
    assertEquals(CapHandle.password("sage"), None)
  }
}
