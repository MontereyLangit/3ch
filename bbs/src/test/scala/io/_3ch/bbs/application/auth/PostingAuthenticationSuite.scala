package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.*
import io._3ch.bbs.domain.account.AuthenticationFailure
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PostingAuthenticationSuite extends munit.FunSuite {
  private val now = 1800000000d
  private val aid = "abcdefghijklmnop"
  private val ua = "f" * 64
  private val account = AccountDescription(aid, "abcdefgh", AccountKind.Client, 2, Some(now + 31536000), 1, Roles(), now, "v1")
  private def request(mail: String = "", cap: Option[String] = None, monazilla: Boolean = true, secure: Boolean = true): PostingAuthenticationRequest =
    PostingAuthenticationRequest(mail, "cookie", "Monazilla/1.00 Test", monazilla, secure, now, cap)
  private final class Fake {
    var cookieReads = 0
    var described = 0
    var tokenUses = 0
    var capReads = 0
    var failDescribe = false
    var cookie: Option[SessionClaims] = Some(SessionClaims(aid, "abcdefgh", 2, (now + 100).toLong, 1, "", None))
    var tokenResult: Either[AccountRejection, TokenAuthentication] = Right(TokenAuthentication(account, "token1"))
    var describeResult: Either[AccountRejection, AccountDescription] = Right(account)
    var capOwnerResult: Either[AccountRejection, Option[String]] = Right(None)
    var proof: Option[TokenProof] = None
    val store = new AccountStore {
      def describe(aid: String, generation: Int, now: Double, token: Option[TokenProof]): Future[Either[AccountRejection, AccountDescription]] = {
        described += 1; proof = token; if (failDescribe) Future.failed(new IllegalStateException("unavailable")) else Future.successful(describeResult)
      }
      def token(hash: String): Future[Either[AccountRejection, Option[TokenReference]]] = Future.successful(Right(Some(TokenReference(aid, "token1"))))
      def useToken(reference: TokenReference, hash: String, label: String, now: Double): Future[Either[AccountRejection, TokenAuthentication]] = {
        tokenUses += 1; assertEquals(hash, ua); Future.successful(tokenResult)
      }
      def capOwner(hash: String): Future[Either[AccountRejection, Option[String]]] = { capReads += 1; Future.successful(capOwnerResult) }
      def describeCap(aid: String, capKey: String, now: Double): Future[Either[AccountRejection, CapAuthentication]] = Future.successful(Left(AccountRejection.CapRevoked))
      def recordPost(aid: String, post: AccountPost, observation: AccountObservation): Future[AccountDescription] = Future.successful(account)
    }
    val credentials = new PostingCredentials {
      def session(header: String, now: Double): Future[Option[SessionClaims]] = { cookieReads += 1; Future.successful(cookie) }
      def issue(claims: SessionClaims, now: Double): Future[Option[String]] = Future.successful(Some("issued"))
      def index(kind: String, value: String): Future[Option[String]] = Future.successful(Some("a" * 64))
      def ua(value: String): Future[Option[String]] = Future.successful(Some(PostingAuthenticationSuite.this.ua))
    }
    val service = new PostingAuthentication(store, credentials, "v1")
  }
  test("明示した不正キーは有効な Cookie にフォールバックしない") {
    val fake = new Fake
    fake.service.resolve(request("!#bad")).map { result =>
      assertEquals(result, PostingAuthenticationResult.Rejected(AuthenticationFailure.Account(AccountRejection.TokenUnknown)))
      assertEquals(fake.cookieReads, 0)
      assertEquals(fake.described, 0)
    }
  }
  test("専ブラキーは HTTPS と専ブラだけに受け付ける") {
    val fake = new Fake
    for {
      web <- fake.service.resolve(request("!#" + "a" * 20, monazilla = false))
      http <- fake.service.resolve(request("!#" + "a" * 20, secure = false))
    } yield {
      assertEquals(web, PostingAuthenticationResult.Rejected(AuthenticationFailure.TokenNotDedicated))
      assertEquals(http, PostingAuthenticationResult.Rejected(AuthenticationFailure.TokenInsecure))
      assertEquals(fake.tokenUses, 0)
      assertEquals(fake.cookieReads, 0)
    }
  }
  test("旧キャップはキーと Cookie より先に採用する") {
    val fake = new Fake
    fake.service.resolve(request("!#bad", Some("cap"))).map { result =>
      assertEquals(result, PostingAuthenticationResult.LegacyCap)
      assertEquals(fake.capReads, 1)
      assertEquals(fake.cookieReads, 0)
    }
  }
  test("所有キャップの失効を拒否する") {
    val fake = new Fake
    fake.capOwnerResult = Right(Some(aid))
    fake.service.resolve(request(cap = Some("cap"))).map(result =>
      assertEquals(result, PostingAuthenticationResult.Rejected(AuthenticationFailure.Account(AccountRejection.CapRevoked))))
  }
  test("キー Cookie は完全な UA ハッシュを一回の DO 照会に渡す") {
    val fake = new Fake
    fake.cookie = fake.cookie.map(_.copy(binding = Some(SessionBinding("token1", ua.take(16)))))
    fake.service.resolve(request()).map { result =>
      assert(result.isInstanceOf[PostingAuthenticationResult.Accepted])
      assertEquals(fake.proof, Some(TokenProof("token1", ua)))
      assertEquals(fake.described, 1)
      assertEquals(fake.tokenUses, 0)
    }
  }
  test("失効したキー由来の Cookie は未ログインになる") {
    val fake = new Fake
    fake.cookie = fake.cookie.map(_.copy(binding = Some(SessionBinding("token1", ua.take(16)))))
    fake.describeResult = Left(AccountRejection.TokenRevoked)
    fake.service.resolve(request()).map(result => assertEquals(result, PostingAuthenticationResult.Anonymous))
  }
  test("キー初回成功で token binding の Cookie を発行する") {
    val fake = new Fake
    fake.service.resolve(request("!#" + "a" * 20)).map {
      case value: PostingAuthenticationResult.Accepted =>
        assertEquals(value.poster.session.flatMap(_.binding), Some(SessionBinding("token1", ua.take(16))))
        assertEquals(fake.tokenUses, 1)
        assertEquals(fake.cookieReads, 0)
      case other => fail(other.toString)
    }
  }
  test("凍結・禁止・削除の拒否理由を変えずに返す") {
    Future.sequence(Seq(AccountRejection.Frozen, AccountRejection.Banned, AccountRejection.Deleted).map { reason =>
      val fake = new Fake
      fake.describeResult = Left(reason)
      fake.service.resolve(request()).map(result => assertEquals(result, PostingAuthenticationResult.Rejected(AuthenticationFailure.Account(reason))))
    })
  }
  test("世代が Int の範囲を外れた Cookie は DO を呼ばない") {
    val fake = new Fake
    fake.cookie = fake.cookie.map(_.copy(generation = Int.MaxValue.toLong + 1))
    fake.service.resolve(request()).map { result =>
      assertEquals(result, PostingAuthenticationResult.Anonymous)
      assertEquals(fake.described, 0)
    }
  }
  test("UA が一致しないキー Cookie は DO を呼ばない") {
    val fake = new Fake
    fake.cookie = fake.cookie.map(_.copy(binding = Some(SessionBinding("token1", "0" * 16))))
    fake.service.resolve(request()).map { result =>
      assertEquals(result, PostingAuthenticationResult.Anonymous)
      assertEquals(fake.described, 0)
    }
  }
  test("Account DO 障害は認証を通さない") {
    val fake = new Fake
    fake.failDescribe = true
    fake.service.resolve(request()).map(result => assertEquals(result, PostingAuthenticationResult.Rejected(AuthenticationFailure.Unavailable)))
  }
  test("同意版が古いアカウントを拒否する") {
    val fake = new Fake
    fake.describeResult = Right(account.copy(agreementVersion = "old"))
    fake.service.resolve(request()).map(result => assertEquals(result, PostingAuthenticationResult.Rejected(AuthenticationFailure.Account(AccountRejection.Agreement))))
  }
  test("Cookie を再発行しない投稿も binding の写しを保持する") {
    val fake = new Fake
    fake.cookie = fake.cookie.map(_.copy(expiresAt = account.expiresAt.get.toLong, binding = Some(SessionBinding("token1", ua.take(16)))))
    fake.service.resolve(request()).map {
      case value: PostingAuthenticationResult.Accepted =>
        assertEquals(value.poster.refreshSession, false)
        assertEquals(value.poster.session.flatMap(_.binding), Some(SessionBinding("token1", ua.take(16))))
      case other => fail(other.toString)
    }
  }

}
