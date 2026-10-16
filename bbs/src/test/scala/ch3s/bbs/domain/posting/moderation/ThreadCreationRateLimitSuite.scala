package ch3s.bbs.domain.posting.moderation

import ch3s.bbs.domain.posting.ClientIdentity
import munit.FunSuite

class ThreadCreationRateLimitSuite extends FunSuite {
  import ThreadCreationRateLimit.*

  private val settings = Settings(2, 100, 3, 200)
  private val identity = ClientIdentity("192.0.2.1", "browser", "network")
  private val other = ClientIdentity("192.0.2.2", "other browser", "other network")
  private val now = 1000.0

  test("各識別軸を独立に規制する") {
    val cases = Seq(
      Entry(now, "other", other.copy(ip = identity.ip)) -> Reason.Ip,
      Entry(now, "title", other) -> Reason.Title,
      Entry(now, "other", other.copy(userAgent = identity.userAgent)) -> Reason.UserAgent,
      Entry(now, "other", other.copy(networkGroup = identity.networkGroup)) -> Reason.NetworkGroup
    )
    cases.foreach { case (entry, reason) =>
      assertEquals(check(settings, Seq(entry), identity, "title", now), Some(reason))
    }
    assertEquals(check(settings, Seq(Entry(now, "other", other)), identity, "title", now), None)
  }

  test("複数の履歴に別々の理由で一致したら、新旧によらず宣言順で最優先の理由を返す") {
    val titleMatch = Entry(now, "title", other)
    val ipMatch = Entry(now - 1, "other", other.copy(ip = identity.ip))
    val uaMatch = Entry(now, "other", other.copy(userAgent = identity.userAgent))
    val groupMatch = Entry(now - 1, "other", other.copy(networkGroup = identity.networkGroup))
    assertEquals(check(settings, Seq(titleMatch, ipMatch), identity, "title", now), Some(Reason.Ip))
    assertEquals(check(settings, Seq(groupMatch, uaMatch), identity, "title", now), Some(Reason.UserAgent))
  }

  test("通常と広域で独立した件数窓の末尾まで判定する") {
    val unrelated = Entry(now, "other", other)
    val ipMatch = Entry(now, "other", other.copy(ip = identity.ip))
    val groupMatch = Entry(now, "other", other.copy(networkGroup = identity.networkGroup))
    assertEquals(check(settings, Seq(unrelated, ipMatch), identity, "title", now), Some(Reason.Ip))
    assertEquals(check(settings, Seq(unrelated, unrelated, ipMatch), identity, "title", now), None)
    assertEquals(check(settings, Seq(unrelated, unrelated, groupMatch), identity, "title", now), Some(Reason.NetworkGroup))
    assertEquals(check(settings, Seq(unrelated, unrelated, unrelated, groupMatch), identity, "title", now), None)
  }

  test("reset秒ちょうどの履歴は有効、それより古い履歴は無効") {
    val normal = Entry(now - 100, "title", other)
    val wide = Entry(now - 200, "other", other.copy(userAgent = identity.userAgent))
    assertEquals(check(settings, Seq(normal), identity, "title", now), Some(Reason.Title))
    assertEquals(check(settings, Seq(normal.copy(timestampSeconds = now - 100.001)), identity, "title", now), None)
    assertEquals(check(settings, Seq(wide), identity, "title", now), Some(Reason.UserAgent))
    assertEquals(check(settings, Seq(wide.copy(timestampSeconds = now - 200.001)), identity, "title", now), None)
  }

  test("件数0は該当規制を無効にする") {
    val entry = Entry(now, "title", identity)
    assertEquals(check(Settings(0, 100, 0, 200), Seq(entry), identity, "title", now), None)
    assertEquals(check(settings.copy(threadTatesugi = 0), Seq(entry), identity, "title", now), Some(Reason.UserAgent))
    assertEquals(check(settings.copy(threadJunban = 0), Seq(entry), identity, "title", now), Some(Reason.Ip))
  }

  test("有効期間で絞る前に件数窓を適用する") {
    val expired = Entry(now - 101, "other", other)
    val matching = Entry(now, "title", other)
    val normalOnly = settings.copy(threadTatesugi = 1, threadJunban = 0)
    assertEquals(check(normalOnly, Seq(expired, matching), identity, "title", now), None)
  }

  test("reset秒0は現在時刻より古い履歴を無効にする") {
    val entry = Entry(now, "title", other)
    val immediate = settings.copy(threadTatesugiResetSeconds = 0, threadJunban = 0)
    assertEquals(check(immediate, Seq(entry), identity, "title", now), Some(Reason.Title))
    assertEquals(check(immediate, Seq(entry.copy(timestampSeconds = now - 0.001)), identity, "title", now), None)
  }

  test("空の識別情報は同一投稿者と判定しない") {
    assertEquals(check(settings, Seq(Entry(now, "other", ClientIdentity())), ClientIdentity(), "title", now), None)
  }
}
