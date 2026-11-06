package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.*
import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.application.posting.ports.PostingAccount
import io._3ch.bbs.domain.account.AuthenticatedPoster
import io._3ch.bbs.domain.posting.{PostingContext, PostingIdentity, PostingUser}
import io._3ch.common.domain.account.*
import io._3ch.common.domain.posting.PostForm
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class PostingAccountServiceSuite extends munit.FunSuite {
  private val now = 1800000000d
  private val aid = "abcdefghijklmnop"
  private val oldExpiry = now + AccountProfile.clientLifetime - 90000
  private val account = AccountDescription(aid, "abcdefgh", AccountKind.Client, 1, Some(oldExpiry), 1, Roles(), now - 1, "v1")
  private val binding = SessionBinding("token1", "0123456789abcdef")

  private final class Fake(updated: AccountDescription) {
    val issued: mutable.Buffer[SessionClaims] = mutable.Buffer.empty
    val posts: mutable.Buffer[(AccountPost, AccountObservation)] = mutable.Buffer.empty
    val store = new AccountStore {
      def describe(aid: String, generation: Int, now: Double, token: Option[TokenProof]) = Future.successful(Right(account))
      def token(hash: String) = Future.successful(Right(None))
      def useToken(reference: TokenReference, uaHash: String, uaLabel: String, now: Double) = Future.successful(Left(AccountRejection.TokenUnknown))
      def capOwner(hash: String) = Future.successful(Right(None))
      def describeCap(aid: String, capKey: String, now: Double) = Future.successful(Left(AccountRejection.CapRevoked))
      def recordPost(aid: String, post: AccountPost, observation: AccountObservation): Future[AccountDescription] = {
        posts += ((post, observation)); Future.successful(updated)
      }
    }
    val credentials = new PostingCredentials {
      def session(cookieHeader: String, now: Double) = Future.successful(None)
      def issue(claims: SessionClaims, now: Double): Future[Option[String]] = { issued += claims; Future.successful(Some(s"cookie${issued.size}")) }
      def index(kind: String, value: String) = Future.successful(Some("f" * 64))
      def ua(userAgent: String) = Future.successful(Some("0" * 64))
    }
    val service = new PostingAccountService(store, credentials, "v1", _ => ())
  }

  private val request = PostingRequest(
    method = "POST", contentType = "application/x-www-form-urlencoded", referer = "", userAgent = "Monazilla/1.00 Test/1.0",
    secChUa = "", secChUaFullVersionList = None, accept = "", acceptLanguage = "", acceptEncoding = "", deviceInformation = "", clientIp = "",
    connectingIp = Some("192.0.2.1"), forwardedFor = "", forwarded = "", via = "", cookie = "",
    asn = Some("2516"), country = Some("JP"), region = Some("Tokyo"), city = None, body = () => Future.failed(new NoSuchElementException("body")))
  private val context = PostingContext(PostForm(bbs = "sample", key = "1800000000", message = "本文<br>二行目"),
    PostingIdentity(Some("192.0.2.1"), "192.0.2.1", "Monazilla/1.00 Test/1.0", "", "", "2516", "JP", "Tokyo", "", "salt"),
    (now * 1000).toLong.toString, now.toLong.toString, number = 2, title = "スレ")
  private def poster(via: AccountVia, session: Option[SessionClaims]) =
    AuthenticatedPoster(account, via, Option.when(via == AccountVia.Token)("token1"), None, "", session, refreshSession = false)

  test("投稿で期限が延びたら専ブラの binding を保ったまま Cookie を発行し直す") {
    val fake = new Fake(account.copy(expiresAt = Some(now + AccountProfile.clientLifetime)))
    val session = SessionClaims(aid, "abcdefgh", 1, oldExpiry.toLong, 1, "", Some(binding))
    val authenticated = PostingAccount(Some(PostingUser(aid, "abcdefgh")), 1, authentication = Some(poster(AccountVia.Token, Some(session))))
    fake.service.posted(context, request, authenticated).map { cookie =>
      assertEquals(cookie, Some("cookie1"))
      assertEquals(fake.issued.head.binding, Some(binding))
      assertEquals(fake.issued.head.expiresAt, (now + AccountProfile.clientLifetime).toLong)
      val (post, observation) = fake.posts.head
      assertEquals(post.excerpt, "本文 二行目")
      assertEquals(post.uaLabel, DedicatedBrowserLabel.of("Monazilla/1.00 Test/1.0"))
      assertEquals(observation.keyIpHash, "f" * 64)
    }
  }
  test("期限の差が1日未満なら Cookie を発行しない") {
    val fake = new Fake(account.copy(expiresAt = Some(oldExpiry + 3600)))
    val session = SessionClaims(aid, "abcdefgh", 1, oldExpiry.toLong, 1)
    fake.service.posted(context, request, PostingAccount(None, authentication = Some(poster(AccountVia.Web, Some(session))))).map { cookie =>
      assertEquals(cookie, None)
      assert(fake.issued.isEmpty)
    }
  }
  test("投稿で Lv が上がったら期限が同じでも Cookie を発行し直し、期限の無い方式は元の期限を保つ") {
    val fake = new Fake(account.copy(lv = 2, expiresAt = Some(oldExpiry + 3600)))
    val session = SessionClaims(aid, "abcdefgh", 1, oldExpiry.toLong, 1)
    fake.service.posted(context, request, PostingAccount(None, authentication = Some(poster(AccountVia.Web, Some(session))))).flatMap { cookie =>
      assertEquals(cookie, Some("cookie1"))
      assertEquals(fake.issued.head.lv, 2)
      assertEquals(fake.issued.head.expiresAt, (oldExpiry + 3600).toLong)
      val google = new Fake(account.copy(kind = AccountKind.Google, lv = 10, expiresAt = None))
      google.service.posted(context, request, PostingAccount(None, authentication = Some(poster(AccountVia.Web, Some(session.copy(lv = 9))))))
        .map(_ => assertEquals(google.issued.map(c => (c.lv, c.expiresAt)).toList, List((10, oldExpiry.toLong))))
    }
  }
  test("セッションを持たないキャップの投稿には Cookie を発行しない") {
    val fake = new Fake(account.copy(expiresAt = Some(now + AccountProfile.clientLifetime)))
    fake.service.posted(context, request, PostingAccount(None, authentication = Some(poster(AccountVia.Cap, None)))).map { cookie =>
      assertEquals(cookie, None)
      assertEquals(fake.posts.size, 1)
    }
  }
  test("認証していない投稿は記録しない") {
    val fake = new Fake(account)
    fake.service.posted(context, request, PostingAccount(None)).map { cookie =>
      assertEquals(cookie, None)
      assert(fake.posts.isEmpty)
    }
  }
}
