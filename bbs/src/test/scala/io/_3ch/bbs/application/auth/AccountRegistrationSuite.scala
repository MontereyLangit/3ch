package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.*
import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.hash.{AuthHmac, PasswordHasher}
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AccountRegistrationSuite extends FunSuite {
  private val now = 2000000000d
  private val secret = "test-registration-secret-0123456789"
  private val keyIp = "a" * 64
  private val id = LoginId.parse("taro_2026").toOption.get
  private val password = Password.parse("correct horse").toOption.get

  /** 実 DO を呼び、指定した操作だけ失敗や応答の喪失を起こす */
  private final class Store(val kit: DurableTestKit.Env) extends AccountManagementStore with AccountSignInStore {
    private val repository = new AccountRepository(kit.worker)
    val trace = mutable.ArrayBuffer.empty[String]
    /** 操作名ごとの失敗。after なら操作を実行してから失敗させる */
    val failures = mutable.Map.empty[String, (Throwable, Boolean)]
    private def step[A](name: String)(run: => Future[A]): Future[A] = {
      trace += name
      failures.remove(name) match {
        case Some((error, false)) => Future.failed(error)
        case Some((error, true)) => run.flatMap(_ => Future.failed(error))
        case None => run
      }
    }
    def rate(key: String, kind: String, now: Double) = repository.rate(key, kind, now)
    def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String]) = step("reserve:" + kind)(repository.reserve(kind, hash, aid, now, tokenId))
    def confirm(kind: String, hash: String, aid: String, now: Double) = step("confirm:" + kind)(repository.confirm(kind, hash, aid, now))
    def remove(kind: String, hash: String, aid: String) = step("remove:" + kind)(repository.remove(kind, hash, aid))
    def create(profile: AccountProfile, now: Double) = repository.create(profile, now)
    def create(profile: AccountProfile, now: Double, credential: Option[PasswordCredential]) = step("create")(repository.create(profile, now, credential))
    def bindDevice(aid: String, device: String, keyIp: String, now: Double) = step("bind")(repository.bindDevice(aid, device, keyIp, now))
    def agree(aid: String, version: String, now: Double) = repository.agree(aid, version, now)
    def issueToken(aid: String, tokenId: String, hash: String, now: Double) = repository.issueToken(aid, tokenId, hash, now)
    def revokeToken(aid: String, tokenId: String, now: Double) = repository.revokeToken(aid, tokenId, now)
    def lookup(kind: String, hash: String) = step("lookup:" + kind)(repository.lookup(kind, hash))
    def activate(aid: String, now: Double) = step("activate")(repository.activate(aid, now))
    def verifyPassword(aid: String, password: String, keyIpHash: String, now: Double) = step("verify")(repository.verifyPassword(aid, password, keyIpHash, now))
    def describeGoogle(aid: String, subHash: String, now: Double) = repository.describeGoogle(aid, subHash, now)
  }

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
  private def service(store: Store) = new PasswordAuthentication(store, keys, hashing, "v1")
  private def hash(kind: String): Future[String] = keys.index(kind, id.value).map(_.get)
  private def failure[A](future: Future[A]): Future[Throwable] = future.transform {
    case scala.util.Failure(error) => scala.util.Success(error)
    case scala.util.Success(_) => scala.util.Failure(new AssertionError("expected failure"))
  }

  test("uid とログイン ID を予約し、作成・ログイン索引・uid の順に確定してから利用を開始する") {
    val store = new Store(DurableTestKit.newEnv())
    for {
      account <- service(store).register(id, password, "", keyIp, now)
      _ = assertEquals(store.trace.toVector, Vector("reserve:uid", "reserve:login", "create", "confirm:login", "confirm:uid", "activate", "bind"))
      _ = assertEquals((account.uid, account.kind, account.lv), ("taro_2026", AccountKind.Password, 1))
      owner <- hash("uid").flatMap(store.lookup("uid", _))
    } yield assertEquals(owner, Some(account.aid))
  }

  test("ログイン ID が使用済みなら自分の uid の予約だけを外して使えない ID とする") {
    val store = new Store(DurableTestKit.newEnv())
    for {
      loginHash <- hash("login")
      uidHash <- hash("uid")
      _ <- store.reserve("login", loginHash, "otherowner000001", now, None)
      error <- failure(service(store).register(id, password, "", keyIp, now))
      _ = assertEquals(error, AuthServiceException(AuthServiceFailure.Taken))
      _ = assert(!store.trace.contains("remove:login"))
      retaken <- store.reserve("uid", uidHash, "otherowner000002", now, None)
    } yield assert(retaken)
  }

  test("uid の確定が拒否されたら両方の索引を外し、応答だけが失われたら所有者を確かめて続ける") {
    val rejected = new Store(DurableTestKit.newEnv())
    rejected.failures("confirm:uid") = (AccountStoreException(AccountRejection.IndexOwnership), false)
    val lost = new Store(DurableTestKit.newEnv())
    lost.failures("confirm:uid") = (new IllegalStateException("response lost"), true)
    for {
      error <- failure(service(rejected).register(id, password, "", keyIp, now))
      _ = assertEquals(error, AccountStoreException(AccountRejection.IndexOwnership))
      _ = assert(rejected.trace.containsSlice(Vector("remove:login", "remove:uid")))
      loginHash <- hash("login")
      released <- rejected.reserve("login", loginHash, "otherowner000001", now, None)
      _ = assert(released)
      account <- service(lost).register(id, password, "", keyIp, now)
      _ = assert(lost.trace.containsSlice(Vector("confirm:uid", "lookup:uid", "activate")))
    } yield assert(!lost.trace.exists(_.startsWith("remove")))
  }

  test("利用開始の前に止まった登録は、正しいパスワードでのログインで再開する") {
    val store = new Store(DurableTestKit.newEnv())
    store.failures("activate") = (new IllegalStateException("response lost"), false)
    for {
      _ <- failure(service(store).register(id, password, "", keyIp, now))
      wrong <- service(store).login(Some(id), "wrong password", "", keyIp, now + 1)
      _ = assertEquals(wrong, LoginOutcome.Failed(0))
      resumed <- service(store).login(Some(id), "correct horse", "", keyIp, now + 2)
      account = resumed match {
        case LoginOutcome.Success(value) => value
        case other => fail(other.toString)
      }
      again <- service(store).login(Some(id), "correct horse", "", keyIp, now + 3)
    } yield {
      assertEquals(account.uid, "taro_2026")
      assertEquals(again, LoginOutcome.Success(account))
    }
  }

  test("uid の確定の結果が分からず予約が他人に渡ったら、再開せずログインの失敗にする") {
    val store = new Store(DurableTestKit.newEnv())
    store.failures("confirm:uid") = (new IllegalStateException("response lost"), false)
    store.failures("lookup:uid") = (new IllegalStateException("unreachable"), false)
    for {
      _ <- failure(service(store).register(id, password, "", keyIp, now))
      _ = assert(!store.trace.exists(_.startsWith("remove")))
      uidHash <- hash("uid")
      taken <- store.reserve("uid", uidHash, "otherowner000001", now + 601, None)
      _ = assert(taken)
      outcome <- service(store).login(Some(id), "correct horse", "", keyIp, now + 602)
    } yield assertEquals(outcome, LoginOutcome.Failed(0))
  }

  test("存在しない ID は固定の Account(\"dummy\") で照合し、ログイン ID の無い入力も同じにする") {
    val store = new Store(DurableTestKit.newEnv())
    for {
      unknown <- service(store).login(Some(id), "correct horse", "", keyIp, now)
      invalid <- service(store).login(None, "correct horse", "", keyIp, now)
    } yield {
      assertEquals((unknown, invalid), (LoginOutcome.Failed(0), LoginOutcome.Failed(0)))
      assertEquals(store.kit.accountNames, Set(PasswordAuthentication.dummyAid))
      assertEquals(store.trace.count(_ == "verify"), 2)
    }
  }
}
