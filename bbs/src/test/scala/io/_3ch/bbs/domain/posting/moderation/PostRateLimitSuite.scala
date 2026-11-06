package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, Entry, ThreadSettings}
import munit.FunSuite

class PostRateLimitSuite extends FunSuite {
  private val t0 = 2000000000.0
  private val keys = PostRateKey.defaults

  private def who(name: String): PostRateIdentity = PostRateIdentity(s"ip-$name", s"shiki-$name")
  private def entry(name: String, at: Double = t0): Entry = Entry(at, who(name))
  private def board(timecount: Int = 18, timeclose: Int = 10, samba24: Int = 0, keys: Set[PostRateKey] = keys): BoardSettings =
    BoardSettings(timecount, timeclose, samba24, keys)

  test("ごんたくれは直近 timecount 件のうち同一投稿者が timeclose 件以上で拒否する") {
    val history = Seq(entry("a"), entry("b"), entry("a"), entry("c"))
    assertEquals(PostRateLimit.checkBoard(board(4, 2), history, who("a"), t0), Some(PostRateRejection.Gontakure))
    assertEquals(PostRateLimit.checkBoard(board(4, 3), history, who("a"), t0), None)
    assertEquals(PostRateLimit.checkBoard(board(2, 2), history, who("a"), t0), None, "件数窓の外は数えない")
  }

  test("ごんたくれは 24 時間ちょうどまでを数え、0 で無効") {
    val history = Seq(entry("a", t0 - 86400), entry("a", t0 - 86401))
    assertEquals(PostRateLimit.checkBoard(board(18, 1), history.drop(1), who("a"), t0), None)
    assertEquals(PostRateLimit.checkBoard(board(18, 1), history.take(1), who("a"), t0), Some(PostRateRejection.Gontakure))
    assertEquals(PostRateLimit.checkBoard(board(0, 1), history, who("a"), t0), None)
    assertEquals(PostRateLimit.checkBoard(board(18, 0), history, who("a"), t0), None)
  }

  test("Samba24 は秒数以内の同一投稿者で拒否し、残り秒数を切り上げて返す") {
    val history = Seq(entry("a", t0 - 3.5), entry("a", t0 - 8))
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10), history, who("a"), t0), Some(PostRateRejection.Samba24(7)))
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10), Seq(entry("a", t0 - 10)), who("a"), t0), Some(PostRateRejection.Samba24(1)), "ちょうど境界も対象")
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10), Seq(entry("a", t0 - 10.5)), who("a"), t0), None)
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 0), history, who("a"), t0), None)
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10), history, who("b"), t0), None)
  }

  test("ごんたくれを Samba24 より先に判定する") {
    assertEquals(PostRateLimit.checkBoard(board(18, 1, 10), Seq(entry("a")), who("a"), t0), Some(PostRateRejection.Gontakure))
  }

  test("照合キーは選んだもののどれかが一致すれば同一で、空の値は照合しない") {
    val sameIp = Seq(Entry(t0, PostRateIdentity("ip", "other")))
    val identity = PostRateIdentity("ip", "mine")
    assert(PostRateLimit.checkBoard(board(samba24 = 10), sameIp, identity, t0).isDefined)
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10, keys = Set(PostRateKey.Shiki)), sameIp, identity, t0), None)
    assertEquals(PostRateLimit.checkBoard(board(samba24 = 10), Seq(Entry(t0, PostRateIdentity())), PostRateIdentity(), t0), None)
    assert(PostRateLimit.checkBoard(board(samba24 = 10, keys = Set(PostRateKey.Account)), Seq(Entry(t0, PostRateIdentity(aid = "x"))), PostRateIdentity(aid = "x"), t0).isDefined)
  }

  test("照合キーの設定は id と知らない値を無視し、何も残らなければ既定") {
    assertEquals(PostRateKey.parse("ip, aid"), Set(PostRateKey.Ip, PostRateKey.Account))
    assertEquals(PostRateKey.parse("id,shiki"), Set(PostRateKey.Shiki))
    assertEquals(PostRateKey.parse("id"), PostRateKey.defaults)
    assertEquals(PostRateKey.parse(""), PostRateKey.defaults)
  }

  test("スレ単位は RESET 秒以内の同一投稿者が件数以上で拒否し、0 で無効") {
    val settings = ThreadSettings(2, 3600, keys)
    val recent = Seq(entry("a"), entry("b"), entry("a", t0 - 3600), entry("a", t0 - 3601))
    assertEquals(PostRateLimit.checkThread(settings, recent, who("a"), t0), Some(PostRateRejection.ThreadRentou))
    assertEquals(PostRateLimit.checkThread(settings, recent.take(2), who("a"), t0), None)
    assertEquals(PostRateLimit.checkThread(settings.copy(count = 0), recent, who("a"), t0), None)
    assertEquals(PostRateLimit.checkThread(settings.copy(resetSeconds = 0), recent, who("a"), t0), None, "RESET 0 は同じ秒だけ")
  }

  test("コピペは dat の末尾 2 行の本文フィールドと完全一致を見る") {
    val dat = "n<><>d<> 1行目 <>題\nn<><>d<> 二 <>\nn<><>d<> 三 <>\n"
    assert(PostRateLimit.copipe(dat, "x<>m<>e<> 三 <>"))
    assert(PostRateLimit.copipe(dat, "x<>m<>e<> 二 <>"))
    assert(!PostRateLimit.copipe(dat, "x<>m<>e<> 1行目 <>"), "3 行前は見ない")
    assert(!PostRateLimit.copipe(dat, "x<>m<>e<> 三  <>"), "前後の空白も含めて比べる")
    assert(!PostRateLimit.copipe("", "x<>m<>e<> 三 <>"))
  }

  test("コピペの照合からあぼーん行を除く") {
    val dat = "n<><>d<> 一 <>題\nあぼーん<>あぼーん<>あぼーん<>あぼーん<>あぼーん\n"
    assert(!PostRateLimit.copipe(dat, "x<>m<>e<>あぼーん<>"))
    assert(PostRateLimit.copipe(dat, "x<>m<>e<> 一 <>"))
    assert(PostRateLimit.copipe("n<>あぼーん<>あぼーん<>あぼーん<>\n", "x<>m<>e<>あぼーん<>"), "4 つともあぼーんでなければ通常の行")
  }

  test("拒否コードは文字列と往復する") {
    val all = Seq(PostRateRejection.Gontakure, PostRateRejection.Samba24(7), PostRateRejection.ThreadRentou)
    assertEquals(all.map(_.code), Seq("post-rate:board:gontakure", "post-rate:board:samba24:7", "post-rate:thread:rentou"))
    all.foreach(rejection => assertEquals(PostRateRejection.fromCode(rejection.code), Some(rejection)))
    assertEquals(PostRateRejection.fromCode("post-rate:board:samba24:x"), None)
    assertEquals(PostRateFinding.fromCode("post-rate:thread:copipe"), Some(PostRateFinding.Copipe))
    assertEquals(PostRateFinding.fromCode("post-rate:thread:rentou"), Some(PostRateFinding.Rejected(PostRateRejection.ThreadRentou)))
    assertEquals(PostRateFinding.fromCode("thread-write:full"), None)
  }

  test("スレの直近の投稿は RESET 秒より古いものと上限を超えた分を捨てる") {
    val settings = ThreadSettings(40, 10, keys)
    val recent = ThreadRecentPosts(Vector(entry("a", t0 - 5), entry("b", t0 - 11)))
    assertEquals(recent.appended(entry("c"), settings, t0).posts, Vector(entry("c"), entry("a", t0 - 5)))
    val full = ThreadRecentPosts(Vector.fill(ThreadRecentPosts.MaxPosts)(entry("a")))
    assertEquals(full.appended(entry("b"), settings, t0).posts.size, ThreadRecentPosts.MaxPosts)
    assertEquals(full.appended(entry("b"), settings, t0).posts.head, entry("b"))
  }
}
