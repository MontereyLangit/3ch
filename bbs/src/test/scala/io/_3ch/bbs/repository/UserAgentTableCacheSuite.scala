package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.UserAgentTable
import io._3ch.common.domain.identity.UserAgentProfile
import munit.FunSuite

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class UserAgentTableCacheSuite extends FunSuite {
  private def table(label: String): UserAgentTable = UserAgentRuleFixture.device(label)

  private final class Clock { var millis: Double = 1000 }

  test("ttl の間は読み直さず、過ぎたら読み直す") {
    val clock = new Clock
    val cache = new UserAgentTableCache(60000, () => clock.millis)
    var fetches = 0
    def fetch(): Future[UserAgentTable] = { fetches += 1; Future.successful(table(s"v$fetches")) }
    for {
      first <- cache.get(fetch)
      _ = clock.millis += 59999
      second <- cache.get(fetch)
      _ = clock.millis += 1
      third <- cache.get(fetch)
    } yield {
      assertEquals(first.profile("x"), UserAgentProfile(device = "v1"))
      assertEquals(second.profile("x"), UserAgentProfile(device = "v1"))
      assertEquals(third.profile("x"), UserAgentProfile(device = "v2"))
      assertEquals(fetches, 2)
    }
  }

  test("読み込みに失敗したら最後に読めた表を使い、次の確認は ttl 後にする") {
    val clock = new Clock
    val cache = new UserAgentTableCache(60000, () => clock.millis)
    var fetches = 0
    var failing = false
    def fetch(): Future[UserAgentTable] = { fetches += 1; if (failing) Future.failed(new IllegalStateException("r2")) else Future.successful(table("good")) }
    for {
      _ <- cache.get(fetch)
      _ = { clock.millis += 60000; failing = true }
      kept <- cache.get(fetch)
      again <- cache.get(fetch)
      _ = { clock.millis += 60000; failing = false }
      recovered <- cache.get(fetch)
    } yield {
      assertEquals(kept.profile("x"), UserAgentProfile(device = "good"))
      assertEquals(again.profile("x"), UserAgentProfile(device = "good"))
      assertEquals(fetches, 3)
      assertEquals(recovered.profile("x"), UserAgentProfile(device = "good"))
    }
  }

  test("一度も読めていなければ空の表を返し、表は保持せず次の投稿で読み直す") {
    val cache = new UserAgentTableCache(60000, () => 0)
    var fetches = 0
    def fetch(): Future[UserAgentTable] = { fetches += 1; if (fetches == 1) Future.failed(new IllegalStateException("r2")) else Future.successful(table("late")) }
    for {
      empty <- cache.get(fetch)
      loaded <- cache.get(fetch)
    } yield {
      assertEquals(empty.profile("x"), UserAgentProfile())
      assertEquals(loaded.profile("x"), UserAgentProfile(device = "late"))
    }
  }

  test("fetch が同期的に例外を投げても失敗として扱う") {
    val cache = new UserAgentTableCache(60000, () => 0)
    cache.get(() => throw new IllegalStateException("sync")).map(result => assertEquals(result.profile("x"), UserAgentProfile()))
  }
}

private object UserAgentRuleFixture {
  def device(label: String): UserAgentTable = UserAgentTableRepository.parse(s"""{"device": [{"match": ".", "label": "$label"}]}""")
}
