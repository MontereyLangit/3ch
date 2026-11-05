package io._3ch.bbs.domain.posting

import munit.FunSuite

class PostingUserSuite extends FunSuite {
  private val alice = Some(PostingUser("aid1", "alice"))

  test("認証済で !id 指定なら </b>(@ID)<b> を返す") {
    assertEquals(PostingUser.nameSuffix(alice, showId = true, cap = false), " </b>(@alice)<b>")
  }

  test("未認証・キャップ・指定なしは空") {
    assertEquals(PostingUser.nameSuffix(None, showId = true, cap = false), "")
    assertEquals(PostingUser.nameSuffix(alice, showId = true, cap = true), "")
    assertEquals(PostingUser.nameSuffix(alice, showId = false, cap = false), "")
  }

  test("表示IDの & < > \" ' はエスケープする") {
    assertEquals(PostingUser.nameSuffix(Some(PostingUser("k", "<a&\"b'>")), showId = true, cap = false), " </b>(@&lt;a&amp;&quot;b&apos;&gt;)<b>")
  }
}
