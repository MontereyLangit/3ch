package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ClientIdentity
import io._3ch.bbs.domain.posting.moderation.ThreadCreationRateLimit.{Reason, Settings}
import munit.FunSuite

class ThreadCreationHistorySuite extends FunSuite {
  private val t0 = 2000000000.0
  private val empty = ThreadCreationHistory(Vector.empty)

  private def settings(tatesugi: Int = 2, junban: Int = 0, tatesugiReset: Double = 3600, junbanReset: Double = 3600): Settings =
    Settings(tatesugi, tatesugiReset, junban, junbanReset)

  private def request(who: String, group: String = ""): ThreadCreationRequest =
    ThreadCreationRequest(ClientIdentity(who, s"ua-$who", if group.isEmpty then s"network-$who" else group), s"title-$who")

  private def reserve(history: ThreadCreationHistory, key: String, request: ThreadCreationRequest, settings: Settings, now: Double = t0): ThreadCreationHistory =
    history.reserve(key, request, settings, now, 8000).fold(rejection => fail(s"unexpected rejection: ${rejection.code}"), identity)

  private def rejection(history: ThreadCreationHistory, key: String, request: ThreadCreationRequest, settings: Settings, now: Double = t0, capacity: Int = 8000): Option[ThreadCreationRejection] =
    history.reserve(key, request, settings, now, capacity).left.toOption

  private def keys(history: ThreadCreationHistory): Vector[String] = history.records.map(_.key)

  test("拒否された予約は履歴の件数窓をずらさない") {
    val s = settings()
    val h = reserve(reserve(empty, "1", request("a"), s), "2", request("b"), s)
    assertEquals(rejection(h, "3", request("b"), s), Some(ThreadCreationRejection.Narrow(Reason.Ip)))
    assertEquals(rejection(h, "4", request("a"), s), Some(ThreadCreationRejection.Narrow(Reason.Ip)))
  }

  test("取消は予約を消し、古い確定済みを残す") {
    val s = settings(1)
    val h = reserve(empty, "1", request("a"), s).complete("1", t0)
    val cancelled = reserve(h, "2", request("b"), s).cancel("2", t0)
    assertEquals(keys(cancelled), Vector("1"))
    assert(rejection(cancelled, "3", request("a"), s).isDefined)
    assert(rejection(cancelled, "4", request("b"), s).isEmpty)
  }

  test("予約中の記録は確定済みの件数窓を進めない") {
    val s = settings(1)
    val h = reserve(empty, "1", request("a"), s).complete("1", t0)
    val pending = reserve(h, "2", request("b"), s)
    assert(rejection(pending, "3", request("a"), s).isDefined)
    assert(rejection(pending.cancel("2", t0), "4", request("a"), s).isDefined)
  }

  test("予約の120秒リースを過ぎると件数窓に落ちる") {
    val s = settings(0, 1)
    val h = reserve(empty, "1", request("a", "overseas"), s)
    val done = reserve(h, "2", request("b", "domestic"), s).complete("2", t0)
    assert(rejection(done, "3", request("c", "overseas"), s, t0 + 120).isDefined)
    assert(rejection(done, "4", request("d", "overseas"), s, t0 + 121).isEmpty)
  }

  test("無効な軸は予約のリースを延ばさない") {
    val s = settings(1, 0, tatesugiReset = 10, junbanReset = 3600)
    val h = reserve(empty, "1", request("a"), s)
    assertEquals(h.records.head.expiresAt, t0 + 10)
    val done = reserve(h, "2", request("b"), s).complete("2", t0)
    assert(rejection(done, "3", request("a"), s, t0 + 11).isEmpty)
  }

  test("リースは120秒を上限とし、窓は大きい方の件数になる") {
    val h = reserve(empty, "1", request("a"), settings(1, 3, 3600, 3600))
    assertEquals(h.records.head.expiresAt, t0 + 120)
    assertEquals(h.records.head.window, 3)
  }

  test("取消は確定済みを消さない") {
    val s = settings(1)
    val h = reserve(empty, "1", request("a"), s).complete("1", t0).cancel("1", t0)
    assertEquals(keys(h), Vector("1"))
    assert(rejection(h, "2", request("a"), s).isDefined)
  }

  test("容量に達すると他の理由より優先して拒否する") {
    val s = settings()
    val h = reserve(empty, "1", request("a"), s)
    assertEquals(rejection(h, "2", request("a"), s, capacity = 1), Some(ThreadCreationRejection.Capacity))
    assertEquals(rejection(h, "2", request("b"), s, capacity = 1), Some(ThreadCreationRejection.Capacity))
    assert(rejection(h, "2", request("b"), s, capacity = 2).isEmpty)
  }

  test("規制理由の優先順位は Ip, Title, UserAgent, NetworkGroup") {
    val s = settings(1, 1)
    val who = ThreadCreationRequest(ClientIdentity("ip-a", "ua-a", "network-a"), "title-a")
    def history(identity: ClientIdentity, title: String): ThreadCreationHistory =
      ThreadCreationHistory(Vector(ThreadCreationRecord("1", t0, title, identity, 1, t0, ThreadCreationStatus.Completed)))
    assertEquals(rejection(history(who.identity, who.title), "2", who, s), Some(ThreadCreationRejection.Narrow(Reason.Ip)))
    assertEquals(rejection(history(who.identity.copy(ip = "x"), who.title), "2", who, s), Some(ThreadCreationRejection.Narrow(Reason.Title)))
    assertEquals(rejection(history(who.identity.copy(ip = "x"), "x"), "2", who, s), Some(ThreadCreationRejection.Broad(Reason.UserAgent)))
    assertEquals(rejection(history(ClientIdentity("x", "x", "network-a"), "x"), "2", who, s), Some(ThreadCreationRejection.Broad(Reason.NetworkGroup)))
  }

  test("有効な予約は個別に判定する") {
    val s = settings(1, 0)
    val h = reserve(reserve(empty, "1", request("a"), s), "2", request("b"), s)
    assertEquals(rejection(h, "3", request("a"), s), Some(ThreadCreationRejection.Narrow(Reason.Ip)))
    assertEquals(rejection(h, "3", request("b"), s), Some(ThreadCreationRejection.Narrow(Reason.Ip)))
  }

  test("保持は有効な予約を残し、確定済みを最大窓の件数に切り詰める") {
    val s = settings(2)
    val h = (1 to 5).foldLeft(empty) { (history, number) =>
      reserve(history, number.toString, request(number.toString), s).complete(number.toString, t0)
    }
    assertEquals(keys(h), Vector("5", "4"))
    val pending = reserve(h, "6", request("6"), s)
    assertEquals(keys(pending), Vector("6", "5", "4"))
    assertEquals(keys(pending.retained(t0 + 121)), Vector("6", "5"))
  }

  test("完了は同じ keyの最初の記録を確定済みにする") {
    val s = settings(5)
    val h = reserve(empty, "1", request("a"), s).complete("1", t0)
    assertEquals(h.records.map(_.status), Vector(ThreadCreationStatus.Completed))
    assertEquals(h.complete("missing", t0), h)
  }

  test("拒否コードは往復できる") {
    val all = Seq(ThreadCreationRejection.Capacity) ++ Reason.values.toSeq.flatMap(r => Seq(ThreadCreationRejection.Narrow(r), ThreadCreationRejection.Broad(r)))
    all.foreach(value => assertEquals(ThreadCreationRejection.fromCode(value.code), Some(value)))
    assertEquals(all.map(_.code).distinct.size, all.size)
    assertEquals(ThreadCreationRejection.fromCode("write failed"), None)
    assertEquals(ThreadCreationRejection.Narrow(Reason.Ip).code, "thread-creation:narrow:ip")
    assertEquals(ThreadCreationRejection.Broad(Reason.NetworkGroup).code, "thread-creation:broad:network-group")
    assertEquals(ThreadCreationRejection.Capacity.code, "thread-creation:capacity")
  }
}
