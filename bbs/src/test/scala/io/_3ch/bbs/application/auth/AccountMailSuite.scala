package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountEmailStore, AuthenticationKeys, EmailVerified, MailSender, OutgoingMail, PasswordHashing, PendingCheck, SignInResult}
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.hash.{AuthHmac, PasswordHasher}
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AccountMailSuite extends FunSuite {
  private val now = 2000000000d
  private val secret = "test-mail-secret-0123456789abcdefgh"
  private val keyIp = "a" * 64
  private val keys = new AuthenticationKeys {
    def aid(): String = AuthHmac.randomAid()
    def uid(): String = AuthHmac.randomUid()
    def token(): String = AuthHmac.randomBase32(20)
    def tokenId(): String = AuthHmac.randomBase32(16)
    def linkSecret(): String = AuthHmac.randomUrlSafe(43)
    def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
  }
  private val hashing = new PasswordHashing {
    def create(password: Password, now: Double): Future[Option[PasswordCredential]] = PasswordHasher.create(password, now).map(_.toOption)
  }

  /** 実 DO を呼び、指定した操作だけ失敗や応答の喪失を起こす */
  private final class Flaky(repository: AccountRepository) extends AccountEmailStore {
    var verifyFails: Option[Boolean] = None
    var removeFails = false
    def rate(key: String, kind: String, now: Double) = repository.rate(key, kind, now)
    def lookup(kind: String, hash: String) = repository.lookup(kind, hash)
    def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String]) = repository.reserve(kind, hash, aid, now, tokenId)
    def confirm(kind: String, hash: String, aid: String, now: Double) = repository.confirm(kind, hash, aid, now)
    def remove(kind: String, hash: String, aid: String) =
      if (removeFails && kind == "email") Future.failed(new IllegalStateException("remove down")) else repository.remove(kind, hash, aid)
    def releaseReservation(kind: String, hash: String, aid: String) = repository.releaseReservation(kind, hash, aid)
    def holds(aid: String, kind: String, value: String) = repository.holds(aid, kind, value)
    def issuePending(aid: String, kind: PendingKind, hash: String, email: Option[String], now: Double) = repository.issuePending(aid, kind, hash, email, now)
    def checkPending(aid: String, kind: PendingKind, hash: String, now: Double): Future[PendingCheck] = repository.checkPending(aid, kind, hash, now)
    def verifyEmail(aid: String, hash: String, now: Double): Future[EmailVerified] = verifyFails match {
      case Some(applied) => (if (applied) repository.verifyEmail(aid, hash, now) else Future.unit).flatMap(_ => Future.failed(new IllegalStateException("verify lost")))
      case None => repository.verifyEmail(aid, hash, now)
    }
    def resetPassword(aid: String, hash: String, credential: PasswordCredential, now: Double) = repository.resetPassword(aid, hash, credential, now)
    def verifyPassword(aid: String, password: String, keyIpHash: String, now: Double): Future[SignInResult] = repository.verifyPassword(aid, password, keyIpHash, now)
    def setPassword(aid: String, credential: PasswordCredential, now: Double) = repository.setPassword(aid, credential, now)
  }

  private final class Setup {
    val kit = DurableTestKit.newEnv()
    val repository = new AccountRepository(kit.worker)
    val flaky = new Flaky(repository)
    val outbox = mutable.ArrayBuffer.empty[OutgoingMail]
    val sender: MailSender = mail => { outbox += mail; Future.unit }
    val mail = new AccountMail(flaky, keys, sender, hashing)
    val registration = new PasswordAuthentication(repository, keys, hashing, "v1")

    def register(id: String): Future[AccountDescription] =
      registration.register(LoginId.parse(id).toOption.get, Password.parse("correct horse").toOption.get, "", keyIp + id, now)

    /** 確認メールの送信まで行い、リンクを返す */
    def request(account: AccountDescription, address: String): Future[PendingLink] =
      mail.request(account, EmailAddress.parse(address).toOption.get, "https://www.3ch.io", now).map { _ =>
        PendingLink.parse("token=([a-z0-9]{16}\\.[A-Za-z0-9_-]{43})".r.findFirstMatchIn(outbox.last.text).map(_.group(1)).get).get
      }

    def owner(address: String): Future[Option[String]] = keys.index("email", EmailAddress.parse(address).toOption.get.canonical).flatMap(hash => repository.lookup("email", hash.get))
  }

  test("確認が確定前に失敗したら新しく取った email 索引を外し、確定した後で応答を失ったなら残す") {
    val s = new Setup
    for {
      taro <- s.register("taro_2026")
      jiro <- s.register("jiro_2026")
      first <- s.request(taro, "taro@example.com")
      _ = { s.flaky.verifyFails = Some(false) }
      before <- s.mail.verify(first, now).failed
      removed <- s.owner("taro@example.com")
      _ = { s.flaky.verifyFails = None }
      second <- s.request(jiro, "jiro@example.com")
      _ = { s.flaky.verifyFails = Some(true) }
      lost <- s.mail.verify(second, now).failed
      kept <- s.owner("jiro@example.com")
    } yield {
      assert(before.isInstanceOf[IllegalStateException])
      assertEquals(removed, None)
      assert(lost.isInstanceOf[IllegalStateException])
      assertEquals(kept, Some(jiro.aid))
    }
  }

  test("前のアドレスの索引を外せなくても、持ち主が持たなくなった索引はほかのアカウントの確認で解放される") {
    val s = new Setup
    for {
      taro <- s.register("taro_2026")
      jiro <- s.register("jiro_2026")
      old <- s.request(taro, "old@example.com")
      _ <- s.mail.verify(old, now)
      changed <- s.request(taro, "new@example.com")
      _ = { s.flaky.removeFails = true }
      _ <- s.mail.verify(changed, now)
      leaked <- s.owner("old@example.com")
      _ = { s.flaky.removeFails = false }
      taken <- s.request(jiro, "old@example.com")
      _ <- s.mail.verify(taken, now)
      reclaimed <- s.owner("old@example.com")
    } yield {
      assertEquals(leaked, Some(taro.aid))
      assertEquals(reclaimed, Some(jiro.aid))
    }
  }

  test("アドレスの確認は確認先を持つアカウントのまま、現在のパスワードの確認は一致したときだけ通す") {
    val s = new Setup
    for {
      taro <- s.register("taro_2026")
      ok <- s.mail.reauthenticate(taro, "correct horse", keyIp, now)
      wrong <- s.mail.reauthenticate(taro, "wrong password", keyIp, now)
      target <- s.request(taro, "taro@example.com").flatMap(link => s.mail.emailTarget(link, now))
    } yield {
      assertEquals((ok, wrong), (true, false))
      assertEquals(target, PendingCheck("taro_2026", Some("taro@example.com")))
    }
  }
}
