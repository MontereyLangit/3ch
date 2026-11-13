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

  /** 保存したページだけを次の読み込みに使う DO の動きをまねる。全ページを保持し、completeOnSave なら保存した予約を直ちに確定する */
  private final class Store(var meta: PostRateMeta = PostRateHistory.InitialMeta, val pages: scala.collection.mutable.Map[Int, Vector[PostRateRecord]] = scala.collection.mutable.Map.empty,
                            completeOnSave: Boolean = true) {
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
      val records = history.pages.head.records
      pages(history.pages.head.number) = if (completeOnSave) records.map(_.copy(status = PostRateStatus.Completed)) else records
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
    val page1 = PostRatePage(1, Vector(PostRateRecord("latest", t0, who("a"), PostRateStatus.Completed)))
    def kept(s: BoardSettings): PostRateMeta = reserve(PostRateHistory(PostRateMeta(1, 0), Vector(page1, page0)), "new", "b", s).meta
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 0, fr = 300)), PostRateMeta(1, 0))
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 300, fr = 1)), PostRateMeta(1, 0))
    assertEquals(kept(settings(timecount = 1, timeclose = 0, samba24 = 10, fr = 299)), PostRateMeta(1, 1))
  }

  test("保持規則は期限内の予約を含むページを残す") {
    val s = settings(timecount = 1, timeclose = 0, samba24 = 0)
    val page0 = PostRatePage(0, Vector(PostRateRecord("old", t0 - 10, who("a"), PostRateStatus.Pending)) ++
      Vector.tabulate(63)(n => PostRateRecord(s"c$n", t0 - 10, who("a"), PostRateStatus.Completed)))
    val page1 = PostRatePage(1, Vector(PostRateRecord("latest", t0, who("a"), PostRateStatus.Completed)))
    val h = PostRateHistory(PostRateMeta(1, 0), Vector(page1, page0))
    val kept = reserve(h, "new", "b", s)
    assertEquals(kept.meta, PostRateMeta(1, 0))
    val expired = reserve(h, "new", "b", s, t0 + 200)
    assertEquals(expired.meta, PostRateMeta(1, 1))
  }

  /** 前のページの末尾に a が 1 件、最新ページに 17 件（a が 9 件）の確定済みの履歴を保存した板 */
  private def seeded(): Store = {
    val store = new Store(PostRateMeta(1, 0), completeOnSave = false)
    def done(key: String, name: String): PostRateRecord = PostRateRecord(key, t0 - 1000, who(name), PostRateStatus.Completed)
    store.pages(0) = Vector(done("p0", "a"))
    store.pages(1) = (1 to 17).map(n => done(s"n$n", if (n <= 9) "a" else s"x$n")).toVector
    store
  }

  test("予約を取り消すと保持の範囲も履歴も予約前と同じに戻り、同じ投稿者の次の投稿を拒否できる") {
    val s = settings(timecount = 18, timeclose = 10, samba24 = 0)
    val store = seeded()
    val before = (store.meta, store.pages.toMap)
    val reserved = reserve(store.load(s, t0), "c1", "c", s)
    store.save(reserved)
    assertEquals(store.pages(1).size, 18)
    assertEquals(store.meta, PostRateMeta(1, 0), "予約で前のページを範囲から外さない")
    val cancelled = store.load(s, t0).cancel("c1").getOrElse(fail("予約が見つからない"))
    store.pages(cancelled.number) = cancelled.records
    assertEquals((store.meta, store.pages.toMap), before)
    val next = store.load(s, t0)
    assertEquals(next.records.size, 18, "取消後も前のページの a を数える")
    assertEquals(next.reserve("a1", who("a"), s, enforce = true, t0).rejection, Some(PostRateRejection.Gontakure))
  }

  test("確定した予約は次の予約で保持に数えられ、押し出された古いページが範囲から外れる") {
    val s = settings(timecount = 18, timeclose = 0, samba24 = 0)
    val store = seeded()
    store.save(reserve(store.load(s, t0), "c1", "c", s))
    store.pages(1) = store.load(s, t0).complete("c1").get.records
    store.save(reserve(store.load(s, t0), "c2", "d", s))
    assertEquals(store.meta, PostRateMeta(1, 1), "確定済みが 18 件を超えたので前のページは要らない")
  }

  test("保持の範囲は予約中を数えず、読み込みも確定済みの件数で決める") {
    val s = settings(timecount = 2, timeclose = 0, samba24 = 0)
    val page0 = PostRatePage(0, Vector(PostRateRecord("c", t0 - 1000, who("a"), PostRateStatus.Completed), PostRateRecord("c2", t0 - 1000, who("a"), PostRateStatus.Completed)))
    val page1 = PostRatePage(1, Vector(PostRateRecord("p1", t0 - 1000, who("b"), PostRateStatus.Pending), PostRateRecord("p2", t0 - 1000, who("b"), PostRateStatus.Pending)))
    val history = PostRateHistory(PostRateMeta(1, 0), Vector(page1))
    assertEquals(history.nextPage(s, t0), Some(0), "予約中が 2 件あっても確定済みが足りなければ読み足す")
    assertEquals(history.withOlder(page0).nextPage(s, t0), None)
    assertEquals(history.withOlder(page0).retained(s, t0).meta.oldest, 0)
  }

  test("広域連投規制を判定しない投稿でも履歴は fr の秒数まで保持する") {
    val page0 = PostRatePage(0, Vector.tabulate(64)(n => PostRateRecord(s"c$n", t0 - 300, who("a"), PostRateStatus.Completed)))
    val history = PostRateHistory(PostRateMeta(0, 0), Vector(page0))
    val skipping = settings(timecount = 1, timeclose = 0, samba24 = 0, fr = 3600).copy(checksFr = false)
    assertEquals(reserve(history, "new", "b", skipping).meta, PostRateMeta(1, 0))
    assertEquals(PostRateHistory(PostRateMeta(1, 0), Vector(PostRatePage(1, Vector.empty))).nextPage(skipping, t0), Some(0), "読み込みも fr の秒数を満たすまで続ける")
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
