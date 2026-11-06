package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.BoardSettings
import munit.FunSuite

class PostRateHistorySuite extends FunSuite {
  private val t0 = 2000000000.0
  private val empty = PostRateHistory(PostRateHistory.InitialMeta, Vector(PostRatePage(0, Vector.empty)))

  private def who(name: String): PostRateIdentity = PostRateIdentity(s"ip-$name")
  private def settings(timecount: Int = 18, timeclose: Int = 10, samba24: Int = 10, fr: Int = 0): BoardSettings =
    BoardSettings(timecount, timeclose, samba24, fr, PostRateKey.defaults)

  private def reserve(history: PostRateHistory, key: String, name: String, s: BoardSettings = settings(), now: Double = t0, enforce: Boolean = true): PostRateHistory = {
    val result = history.reserve(key, who(name), s, enforce, now)
    result.saved.getOrElse(fail(s"unexpected rejection: ${result.rejection.map(_.code)}"))
  }

  /** 保存したページだけを次の読み込みに使う DO の動きをまねる。全ページを保持する */
  private final class Store(var meta: PostRateMeta = PostRateHistory.InitialMeta, val pages: scala.collection.mutable.Map[Int, Vector[PostRateRecord]] = scala.collection.mutable.Map.empty) {
    def load(s: BoardSettings, now: Double): PostRateHistory = {
      var history = PostRateHistory(meta, Vector.empty)
      var next = history.nextPage(s, now)
      while (next.isDefined) {
        history = history.withOlder(PostRatePage(next.get, pages.getOrElse(next.get, Vector.empty)))
        next = history.nextPage(s, now)
      }
      history
    }
    def save(history: PostRateHistory): Unit = {
      meta = history.meta
      pages(history.pages.head.number) = history.pages.head.records
    }
  }

  test("予約中も 1 件に数え、確定と取消は同じ key の記録だけを変える") {
    val s = settings(timecount = 18, timeclose = 2, samba24 = 0)
    val h = reserve(reserve(empty, "1", "a", s), "2", "b", s)
    assertEquals(h.records.map(_.status), Vector(PostRateStatus.Pending, PostRateStatus.Pending))
    val completed = h.complete("1").get
    assertEquals(completed.records.map(r => r.key -> r.status), Vector("1" -> PostRateStatus.Completed, "2" -> PostRateStatus.Pending))
    val rejected = reserve(h, "3", "a", s).reserve("4", who("a"), s, enforce = true, t0)
    assertEquals(rejected.rejection, Some(PostRateRejection.Gontakure))
    assertEquals(rejected.saved, None)
    val cancelled = PostRateHistory(h.meta, Vector(completed)).cancel("1")
    assertEquals(cancelled, None, "確定済みは取り消さない")
    assertEquals(h.cancel("2").map(_.records.map(_.key)), Some(Vector("1")))
  }

  test("試行モードは拒否理由を返しつつ記録する") {
    val s = settings(samba24 = 10)
    val h = reserve(empty, "1", "a", s)
    val result = h.reserve("2", who("a"), s, enforce = false, t0 + 1)
    assertEquals(result.rejection, Some(PostRateRejection.Samba24(9)))
    assertEquals(result.saved.map(_.records.map(_.key)), Some(Vector("2", "1")))
  }

  test("期限切れの予約も確定済みと同じく件数に数える") {
    val s = settings(timecount = 18, timeclose = 1, samba24 = 0)
    val h = reserve(empty, "1", "a", s)
    assert(!h.records.head.isActivePending(t0 + 121))
    assertEquals(h.reserve("2", who("a"), s, enforce = true, t0 + 121).rejection, Some(PostRateRejection.Gontakure))
  }

  test("64 件でページを分け、新しいページと範囲だけを書く") {
    val s = settings(timecount = 100, timeclose = 0, samba24 = 0)
    val store = new Store()
    (1 to 65).foreach { n =>
      store.save(reserve(store.load(s, t0), n.toString, n.toString, s))
    }
    assertEquals(store.meta, PostRateMeta(1, 0))
    assertEquals(store.pages(0).size, 64)
    assertEquals(store.pages(1).map(_.key), Vector("65"))
    assertEquals(store.load(s, t0).records.map(_.key).take(3), Vector("65", "64", "63"))
  }

  test("判定に要るページだけを読む") {
    val store = new Store()
    val s = settings(timecount = 18, timeclose = 0, samba24 = 10)
    (1 to 130).foreach(n => store.save(reserve(store.load(s, t0 - 1000), n.toString, n.toString, s, t0 - 1000)))
    assertEquals(store.meta.newest, 2)
    assertEquals(store.load(s, t0).pages.map(_.number), Vector(2, 1), "最新ページは 2 件なので 1 つ前まで読む")
    assertEquals(store.load(settings(timecount = 1, samba24 = 0), t0).pages.map(_.number), Vector(2))
    assertEquals(store.load(settings(timecount = 1, samba24 = 2000), t0).pages.map(_.number), Vector(2, 1, 0), "Samba24 の秒数以内を読み切る")
    assertEquals(store.load(settings(timecount = 1, samba24 = 0, fr = 2000), t0).pages.map(_.number), Vector(2, 1, 0), "広域連投規制の秒数以内も読み切る")
  }

  test("保持規則で古いページを範囲から外し、上限を超えても拒否しない") {
    val s = settings(timecount = 18, timeclose = 0, samba24 = 10)
    val store = new Store()
    (1 to 64 * 3).foreach(n => store.save(reserve(store.load(s, t0 + n * 200), n.toString, n.toString, s, t0 + n * 200)))
    assertEquals(store.meta, PostRateMeta(2, 2), "直近 18 件を含むページより古いページは外す")
    val crowded = new Store()
    (1 to 64 * 17 + 1).foreach(n => crowded.save(reserve(crowded.load(settings(timecount = 1000, timeclose = 0, samba24 = 0), t0), n.toString, n.toString, settings(timecount = 1000, timeclose = 0, samba24 = 0))))
    assertEquals(crowded.meta, PostRateMeta(17, 2), "16 ページを超えたら古いページを外す")
    assertEquals(crowded.load(settings(timecount = 1000, timeclose = 0, samba24 = 0), t0).records.size, 64 * 15 + 1)
  }

  test("保持規則は Samba24 と広域連投規制の大きい方の秒数以内を残す") {
    val page0 = PostRatePage(0, Vector.tabulate(64)(n => PostRateRecord(s"c$n", t0 - 300, who("a"), PostRateStatus.Completed)))
    def kept(s: BoardSettings): PostRateMeta = reserve(PostRateHistory(PostRateMeta(0, 0), Vector(page0)), "new", "b", s).meta
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 0, fr = 300)), PostRateMeta(1, 0))
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 300, fr = 1)), PostRateMeta(1, 0))
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 10, fr = 299)), PostRateMeta(1, 1))
  }

  test("保持規則は期限内の予約を含むページを残す") {
    val s = settings(timecount = 1, timeclose = 0, samba24 = 0)
    val page0 = PostRatePage(0, Vector(PostRateRecord("old", t0 - 10, who("a"), PostRateStatus.Pending)) ++
      Vector.tabulate(63)(n => PostRateRecord(s"c$n", t0 - 10, who("a"), PostRateStatus.Completed)))
    val h = PostRateHistory(PostRateMeta(0, 0), Vector(page0))
    val kept = reserve(h, "new", "b", s)
    assertEquals(kept.meta, PostRateMeta(1, 0))
    val expired = reserve(PostRateHistory(PostRateMeta(0, 0), Vector(page0)), "new", "b", s, t0 + 200)
    assertEquals(expired.meta, PostRateMeta(1, 1))
  }

  test("リングバッファは番号が進んでも範囲内のページだけを読む") {
    val s = settings(timecount = 1, timeclose = 0, samba24 = 0)
    val history = PostRateHistory(PostRateMeta(40, 39), Vector.empty)
    assertEquals(history.nextPage(s, t0), Some(40))
    val loaded = history.withOlder(PostRatePage(40, Vector.empty))
    assertEquals(loaded.nextPage(s, t0), Some(39))
    assertEquals(loaded.withOlder(PostRatePage(39, Vector.empty)).nextPage(s, t0), None)
  }
}
