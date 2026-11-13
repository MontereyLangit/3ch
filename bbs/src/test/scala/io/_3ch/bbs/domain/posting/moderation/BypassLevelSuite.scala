package io._3ch.bbs.domain.posting.moderation

import io._3ch.common.domain.account.{AccountRole, Roles}
import munit.FunSuite

class BypassLevelSuite extends FunSuite {
  private def roles(values: AccountRole*): Roles = Roles(values.toSet)

  private val posters: Seq[(String, BypassPoster)] = Seq(
    "未ログイン" -> BypassPoster(),
    "認証済 Lv9" -> BypassPoster(authenticated = true, lv = 9),
    "認証済 Lv10" -> BypassPoster(authenticated = true, lv = 10),
    "旧キャップ" -> BypassPoster(cap = true),
    "cap 権限の名無し" -> BypassPoster(roles(AccountRole.Cap), authenticated = true, lv = 1),
    "cap 権限のキャップ投稿" -> BypassPoster(roles(AccountRole.Cap), cap = true, authenticated = true, lv = 1),
    "regulate の名無し" -> BypassPoster(roles(AccountRole.Regulate), authenticated = true, lv = 1),
    "delete の名無し" -> BypassPoster(roles(AccountRole.Delete), authenticated = true, lv = 1),
    "admin の名無し" -> BypassPoster(roles(AccountRole.Admin), authenticated = true, lv = 1)
  )

  private val expected: Map[BypassLevel, Set[String]] = Map(
    BypassLevel.Admin -> Set("admin の名無し"),
    BypassLevel.Staff -> Set("regulate の名無し", "delete の名無し", "admin の名無し"),
    BypassLevel.Privileged -> Set("旧キャップ", "cap 権限のキャップ投稿", "regulate の名無し", "delete の名無し", "admin の名無し"),
    BypassLevel.L10 -> Set("認証済 Lv10", "旧キャップ", "cap 権限のキャップ投稿", "regulate の名無し", "delete の名無し", "admin の名無し"),
    BypassLevel.Login -> Set("認証済 Lv9", "認証済 Lv10", "旧キャップ", "cap 権限の名無し", "cap 権限のキャップ投稿", "regulate の名無し", "delete の名無し", "admin の名無し")
  )

  test("全段階と投稿者の組み合わせで免除する人を決める") {
    BypassLevel.values.foreach { level =>
      val exempted = posters.collect { case (label, poster) if level.exempts(poster) => label }.toSet
      assertEquals(exempted, expected(level), level.value)
    }
  }

  test("上の段階で免除される人は下の段階でも免除される") {
    val ordered = Seq(BypassLevel.Admin, BypassLevel.Staff, BypassLevel.Privileged, BypassLevel.L10)
    ordered.zip(ordered.tail).foreach { case (upper, lower) =>
      posters.foreach { case (label, poster) => assert(!upper.exempts(poster) || lower.exempts(poster), s"$label ${upper.value} ${lower.value}") }
    }
    posters.foreach { case (label, poster) => assert(!BypassLevel.Privileged.exempts(poster) || BypassLevel.Login.exempts(poster), label) }
  }

  test("実効 Lv は認証済のときだけ見る") {
    assert(!BypassLevel.L10.exempts(BypassPoster(lv = 10)))
  }

  test("設定の値を前後の空白を除いて読み、知らない値は None") {
    assertEquals(BypassLevel.values.toSeq.map(level => BypassLevel.parse(s" ${level.value} ")), BypassLevel.values.toSeq.map(Some(_)))
    assertEquals(BypassLevel.parse("Admin"), None)
    assertEquals(BypassLevel.parse(""), None)
    assertEquals(BypassLevel.Default, BypassLevel.Privileged)
  }
}
