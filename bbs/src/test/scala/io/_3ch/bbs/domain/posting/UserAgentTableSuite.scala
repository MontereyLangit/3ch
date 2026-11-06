package io._3ch.bbs.domain.posting

import io._3ch.common.domain.identity.UserAgentProfile
import munit.FunSuite

class UserAgentTableSuite extends FunSuite {
  private def rule(pattern: String, label: String, flags: String = ""): UserAgentRule = UserAgentRule.create(pattern, flags, label).get

  test("各表の最初に一致した行の label を使い、$n を捕捉した文字列にする") {
    val table = UserAgentTable(
      Seq(rule("iPhone OS (\\d+)_", "iOS $1"), rule("Windows NT 10", "Windows")),
      Seq(rule("Version/(\\d+)[.\\d]* .*Safari/", "Safari $1"), rule("Safari/", "Safari")),
      Seq(rule("iphone", "スマートフォン iPhone", "i")))
    val ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1"
    assertEquals(table.profile(ua), UserAgentProfile("iOS 18", "Safari 18", "スマートフォン iPhone"))
  }

  test("一致しない表と空の表は空、捕捉の無い $n と label の $ 記号はそのまま扱う") {
    assertEquals(UserAgentTable.empty.profile("Mozilla/5.0"), UserAgentProfile())
    val table = UserAgentTable(Seq(rule("Monazilla/1\\.00 (\\w+)", "$1 $2 $$")), Nil, Nil)
    assertEquals(table.profile("Monazilla/1.00 JaneStyle/4.23"), UserAgentProfile("JaneStyle  $$"))
    assertEquals(table.profile("Mozilla/5.0"), UserAgentProfile())
  }

  test("読めない正規表現と状態を持つフラグの行は作らない") {
    assertEquals(UserAgentRule.create("(", "", "x"), None)
    assertEquals(UserAgentRule.create("a", "g", "x"), None)
    assertEquals(UserAgentRule.create("a", "y", "x"), None)
    assertEquals(UserAgentRule.create("a", "ii", "x"), None)
    assert(UserAgentRule.create("a", "iu", "x").isDefined)
  }
}
