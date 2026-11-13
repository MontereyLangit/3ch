package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateFlow.{ThreadJudgement, Verdict}
import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, Entry, ThreadSettings}
import munit.FunSuite

class PostRateFlowSuite extends FunSuite {
  private val t0 = 2000000000.0
  private val keys = PostRateKey.defaults
  private val me = PostRateIdentity("ip-a", "shiki-a")

  private def check(level: PostRateLevel): PostRateCheck =
    PostRateCheck(level, me, BoardSettings(18, 10, 0, 0, keys), ThreadSettings(2, 3600, keys))
  private val enforce = check(PostRateLevel.Enforce(Some(1)))
  private val log = check(PostRateLevel.Log)
  private val crowded = ThreadRecentPosts(Vector(Entry(t0 - 1, me), Entry(t0 - 2, me)))
  private val dat = "n<><>d<> 同じ <>t\n"
  private val line = "n<><>d<> 同じ <>"
  private val other = "n<><>d<> 別 <>"

  test("スレ単位の判定は返信だけで、規制モードは拒否理由を、試行モードは記録だけの理由を返す") {
    assertEquals(PostRateFlow.judgeThread(enforce, reply = true, crowded, t0), ThreadJudgement(Some(PostRateRejection.ThreadRentou), None))
    assertEquals(PostRateFlow.judgeThread(log, reply = true, crowded, t0), ThreadJudgement(None, Some(PostRateRejection.ThreadRentou)))
    assertEquals(PostRateFlow.judgeThread(enforce, reply = false, crowded, t0), ThreadJudgement(None, None))
    assertEquals(PostRateFlow.judgeThread(log, reply = false, crowded, t0), ThreadJudgement(None, None))
    assertEquals(PostRateFlow.judgeThread(enforce, reply = true, ThreadRecentPosts.empty, t0), ThreadJudgement(None, None))
  }

  test("板の拒否コードは規制モードだけ投稿を拒否する") {
    assertEquals(PostRateFlow.boardRefusal(enforce, "post-rate:board:gontakure"), Some("post-rate:board:gontakure"))
    assertEquals(PostRateFlow.boardRefusal(enforce, ""), None)
    assertEquals(PostRateFlow.boardRefusal(log, "post-rate:board:gontakure"), None)
  }

  test("規制モードのコピペは吸い込み、試行モードは保存して記録する。>>1 とスレ立ては見ない") {
    val none = ThreadJudgement(None, None)
    assertEquals(PostRateFlow.conclude(enforce, reply = true, none, "", dat, line), Verdict.Swallow)
    assertEquals(PostRateFlow.conclude(enforce, reply = true, none, "", dat, other), Verdict.Store(Nil))
    assertEquals(PostRateFlow.conclude(enforce, reply = false, none, "", dat, line), Verdict.Store(Nil))
    assertEquals(PostRateFlow.conclude(log, reply = true, none, "", dat, line), Verdict.Store(Seq(PostRateFinding.Copipe)))
  }

  test("試行モードの記録はスレ・板・コピペの順に集め、知らない板のコードは捨てる") {
    val judged = ThreadJudgement(None, Some(PostRateRejection.ThreadRentou))
    assertEquals(PostRateFlow.conclude(log, reply = true, judged, "post-rate:board:samba24:5", dat, line), Verdict.Store(Seq(
      PostRateFinding.Rejected(PostRateRejection.ThreadRentou), PostRateFinding.Rejected(PostRateRejection.Samba24(5)), PostRateFinding.Copipe)))
    assertEquals(PostRateFlow.conclude(log, reply = true, ThreadJudgement(None, None), "unknown", dat, other), Verdict.Store(Nil))
  }

  test("履歴には判定した投稿者を短縮して足す") {
    val recent = PostRateFlow.recorded(enforce.copy(identity = PostRateIdentity("a" * 64)), ThreadRecentPosts.empty, t0)
    assertEquals(recent.posts, Vector(Entry(t0, PostRateIdentity("a" * 12))))
  }
}
