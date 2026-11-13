package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AuthenticationKeys}
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AccountManagementSuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val keyIp = "a" * 64
  private val device = "p" + "b" * 18
  private val initial = AccountState.create(AccountProfile(aid, "user1234", AccountKind.Client, now, None, now, "v1"), now).toOption.get

  private final class Fixture {
    val trace = mutable.ArrayBuffer.empty[String]
    val states = mutable.Map.empty[String, AccountState]
    val entries = mutable.Map.empty[(String, String), AccountIndexEntry]
    var history = AuthRateHistory()
    var failure = ""
    var loseConfirmation = false
    var loseReservation = false
    var cleanupFailure = false
    var conflict = false
    var attempts = 0
    var keyFailure = false
    private var sequence = 0
    private def result[A](operation: String)(value: => A): Future[A] = Future {
      trace += operation
      if (failure == operation) throw new IllegalStateException("failed:" + operation)
      value
    }
    val keys = new AuthenticationKeys {
      def aid(): String = { attempts += 1; f"$attempts%016x" }
      def uid(): String = f"$attempts%08x"
      def token(): String = "a" * 20
      def tokenId(): String = "newtoken"
      def linkSecret(): String = "s" * 43
      def index(kind: String, value: String): Future[Option[String]] = {
        sequence += 1
        Future.successful(if (keyFailure) None else Some(f"$sequence%064x"))
      }
    }
    val store = new AccountManagementStore {
      def rate(keyIp: String, kind: String, now: Double): Future[AuthRateCounts] = result("rate:" + kind) {
        val (next, counts) = history.hit(kind, now).toOption.get
        history = next
        counts
      }
      def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String]): Future[Boolean] = result("reserve:" + kind) {
        if (conflict) false
        else {
          AccountIndexEntry.register(entries.get(kind -> hash), aid, now, tokenId, AccountIndexKind.parse(kind).get) match {
            case Left(_) => false
            case Right(entry) =>
              entries.update(kind -> hash, entry)
              if (loseReservation) throw new IllegalStateException("lost reservation")
              true
          }
        }
      }
      def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit] = result("confirm:" + kind) {
        entries.update(kind -> hash, entries(kind -> hash).confirm(aid, now).toOption.get)
        if (loseConfirmation) throw new IllegalStateException("lost confirmation")
      }
      def remove(kind: String, hash: String, aid: String): Future[Unit] = result("remove:" + kind) {
        if (cleanupFailure) throw new IllegalStateException("cleanup failed")
        entries.get(kind -> hash).foreach { entry =>
          entry.remove(AccountIndexKind.parse(kind).get, aid).fold(reason => throw new IllegalStateException(reason.code), _ => entries.remove(kind -> hash))
        }
      }
      def holds(aid: String, kind: String, value: String): Future[Boolean] = Future.successful(true)
      def releaseReservation(kind: String, hash: String, aid: String): Future[Unit] = result("release:" + kind) {
        entries.get(kind -> hash).filter(entry => entry.aid == aid && !entry.confirmed).foreach(_ => entries.remove(kind -> hash))
      }
      def create(profile: AccountProfile, now: Double): Future[AccountDescription] = result("create") {
        val state = AccountState.create(profile, now).toOption.get
        states.update(profile.aid, state)
        state.description
      }
      def bindDevice(aid: String, device: String, keyIp: String, now: Double): Future[Unit] = result("bind") {
        states.update(aid, states(aid).bindDevice(device, keyIp, now).toOption.get)
      }
      def agree(aid: String, version: String, now: Double): Future[Unit] = result("agree") {
        states.update(aid, states(aid).agree(version, now).toOption.get)
      }
      def issueToken(aid: String, tokenId: String, hash: String, now: Double): Future[AccountDescription] = result("issue") {
        val state = states(aid).issueToken(tokenId, hash, now).toOption.get
        states.update(aid, state)
        state.description
      }
      def revokeToken(aid: String, tokenId: String, now: Double): Future[Unit] = result("revoke") {
        states.update(aid, states(aid).revokeToken(tokenId, now).toOption.get)
      }
    }
    def service: ClientAuthentication = new ClientAuthentication(store, keys, "v2")
    def issuer: TokenIssuing = new TokenIssuing(store, keys)
    def error[A](future: Future[A]): Future[String] = future.transform {
      case scala.util.Failure(error) => scala.util.Success(error.getMessage)
      case scala.util.Success(_) => scala.util.Failure(new AssertionError("expected failure"))
    }
  }

  test("認証計数の後に UID を予約して作成・端末登録・確定する") {
    val fixture = new Fixture
    fixture.service.verify(device, keyIp, now).map { account =>
      assertEquals(fixture.trace.toVector, Vector("rate:deviceHash:" + device, "rate:verify", "reserve:uid", "create", "bind", "confirm:uid"))
      assertEquals(account.agreementVersion, "v2")
      assertEquals(fixture.states(account.aid).devices.map(_.hash), Vector(device))
      assert(fixture.entries.values.forall(_.confirmed))
    }
  }

  test("本人確認済みなら新規作成せず端末と同意を更新する") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.service.verify(device, keyIp, now + 1, Some(initial.description)).map { account =>
      assertEquals(account.aid, aid)
      assertEquals(account.expiresAt, initial.profile.expiresAt)
      assertEquals((account.agreementVersion, account.agreedAt), ("v2", now + 1))
      assertEquals(fixture.attempts, 0)
      assertEquals(fixture.trace.takeRight(2).toVector, Vector("bind", "agree"))
    }
  }

  test("端末の申告が無い要求は毎回別の端末として数え、省略しても端末数の制限を避けられない") {
    val fixture = new Fixture
    for {
      _ <- fixture.service.verify("", keyIp, now)
      _ = assertEquals(fixture.history.events.map(_.kind).sorted, Vector("anonymousDevice", "verify"))
      _ = assert(fixture.states.values.head.devices.isEmpty)
      _ = fixture.history = AuthRateHistory((1 to 5).map(i => AuthRateEvent(AuthRateHistory.anonymousDevice, now - i)).toVector)
      error <- fixture.error(fixture.service.verify("", keyIp, now + 1))
    } yield assertEquals(error, "auth:rate")
  }

  test("認証回数または端末数の超過は作成前に拒否する") {
    val fixture = new Fixture
    fixture.history = AuthRateHistory(Vector.fill(10)(AuthRateEvent("verify", now - 1)))
    for {
      repeated <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(repeated, "auth:rate")
      _ = fixture.history = AuthRateHistory((1 to 5).map(i => AuthRateEvent("deviceHash:p" + f"$i%018x", now - 1)).toVector)
      distinct <- fixture.error(fixture.service.verify(device, keyIp, now))
      _ = assertEquals(distinct, "auth:rate")
      _ = assert(fixture.states.isEmpty)
    } yield ()
  }

  test("認証計数または HMAC の障害ではアカウントを作成しない") {
    val fixture = new Fixture
    fixture.failure = "rate:verify"
    for {
      unavailable <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(unavailable, "failed:rate:verify")
      _ = fixture.failure = ""
      _ = fixture.keyFailure = true
      cryptography <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(cryptography, "auth:unavailable")
      _ = assert(fixture.states.isEmpty && fixture.entries.isEmpty)
    } yield ()
  }

  test("UID 衝突は八回まで採番をやり直し他人の予約を消さない") {
    val fixture = new Fixture
    fixture.conflict = true
    fixture.error(fixture.service.verify("", keyIp, now)).map { error =>
      assertEquals(error, "auth:unavailable")
      assertEquals(fixture.attempts, 8)
      assert(!fixture.trace.exists(_.startsWith("remove")))
      assert(fixture.states.isEmpty)
    }
  }

  test("作成後の失敗では未確定予約を外し掃除の失敗で元の例外を隠さない") {
    val fixture = new Fixture
    fixture.failure = "bind"
    for {
      original <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(original, "failed:bind")
      _ = assert(fixture.entries.isEmpty)
      _ = fixture.cleanupFailure = true
      again <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(again, "failed:bind")
    } yield ()
  }

  test("UID 確定の応答を失っても確定済み UID を再利用しない") {
    val fixture = new Fixture
    fixture.loseConfirmation = true
    fixture.error(fixture.service.verify("", keyIp, now)).map { error =>
      assertEquals(error, "lost confirmation")
      assertEquals(fixture.entries.size, 1)
      assert(fixture.entries.values.head.confirmed)
      assertEquals(fixture.trace.last, "remove:uid")
    }
  }

  test("UID 予約の応答を失ったときも本人の未確定予約を掃除する") {
    val fixture = new Fixture
    fixture.loseReservation = true
    for {
      error <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(error, "lost reservation")
      _ = assert(fixture.entries.isEmpty && fixture.states.isEmpty)
      _ = fixture.cleanupFailure = true
      failedCleanup <- fixture.error(fixture.service.verify("", keyIp, now))
      _ = assertEquals(failedCleanup, "lost reservation")
    } yield ()
  }

  test("キーの平文は索引と Account の両方が確定してから返す") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.issuer.issue(initial.description, now).map { issued =>
      assertEquals(issued, IssuedToken("newtoken", "a" * 20))
      assertEquals(fixture.trace.toVector, Vector("rate:token", "reserve:token", "issue", "confirm:token"))
      assert(fixture.entries.values.head.confirmed)
      assertEquals(fixture.states(aid).tokens.head.hash, fixture.entries.keys.head._2)
      assert(!fixture.states(aid).tokens.exists(_.hash == issued.token))
    }
  }

  test("キー確定の応答を失ったときはキーを失効させて索引を外す") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.loseConfirmation = true
    fixture.error(fixture.issuer.issue(initial.description, now)).map { error =>
      assertEquals(error, "lost confirmation")
      assertEquals(fixture.states(aid).tokens.head.revokeReason, Some(TokenRevokeReason.Revoked))
      assert(fixture.entries.isEmpty)
      assertEquals(fixture.trace.takeRight(2).toVector, Vector("revoke", "remove:token"))
    }
  }

  test("キー発行の失敗では掃除が失敗しても平文を返さない") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.failure = "issue"
    fixture.cleanupFailure = true
    fixture.error(fixture.issuer.issue(initial.description, now)).map { error =>
      assertEquals(error, "failed:issue")
      assert(fixture.states(aid).tokens.isEmpty)
      assertEquals(fixture.trace.takeRight(2).toVector, Vector("revoke", "remove:token"))
    }
  }

  test("キー予約の応答を失ったときは Account に触れず本人の予約を掃除する") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.loseReservation = true
    for {
      error <- fixture.error(fixture.issuer.issue(initial.description, now))
      _ = assertEquals(error, "lost reservation")
      _ = assert(fixture.entries.isEmpty)
      _ = assertEquals(fixture.states(aid), initial)
      _ = assertEquals(fixture.trace.toVector, Vector("rate:token", "reserve:token", "remove:token"))
      _ = fixture.cleanupFailure = true
      failedCleanup <- fixture.error(fixture.issuer.issue(initial.description, now))
      _ = assertEquals(failedCleanup, "lost reservation")
    } yield ()
  }

  test("キーの発行は一アカウント一日十回までにする") {
    val fixture = new Fixture
    fixture.states.update(aid, initial)
    fixture.history = AuthRateHistory((1 to 10).map(i => AuthRateEvent("token", now - i)).toVector)
    fixture.error(fixture.issuer.issue(initial.description, now)).map { error =>
      assertEquals(error, "auth:rate")
      assertEquals(fixture.trace.toVector, Vector("rate:token"))
      assert(fixture.states(aid).tokens.isEmpty)
    }
  }
}
