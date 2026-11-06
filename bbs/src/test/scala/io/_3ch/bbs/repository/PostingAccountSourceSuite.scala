package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.PostingAuthenticationResult
import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.application.posting.ports.PostingAccount
import io._3ch.bbs.durable.codec.AccountCodec
import io._3ch.bbs.domain.posting.{PostingContext, PostingIdentity, PostingUser}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.HandlerTestKit
import io._3ch.bbs.handler.auth.SessionCookies
import io._3ch.bbs.hash.AuthHmac
import io._3ch.common.domain.account.*
import io._3ch.common.domain.posting.PostForm
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class PostingAccountSourceSuite extends munit.FunSuite {
  test("成功投稿の期限延長で専ブラ Cookie の binding を保ち、キー失効も適用する") {
    val secret = "test-key"
    val now = 1800000000d
    val aid = "abcdefghijklmnop"
    val userAgent = "Monazilla/1.00 Test"
    val oldExpiry = now + AccountProfile.clientLifetime - 90000
    val account = AccountDescription(aid, "abcdefgh", AccountKind.Client, 1, Some(oldExpiry), 1, Roles(), now - 1, "v1")
    var descriptions = 0
    val stub = js.Dynamic.literal(
      recordPost = ((_: Double, _: js.Dynamic, _: js.Dynamic) => js.Promise.resolve(AccountCodec.description(account.copy(expiresAt = Some(now + AccountProfile.clientLifetime))))): js.Function3[Double, js.Dynamic, js.Dynamic, js.Promise[js.Dynamic]],
      describeForPosting = ((_: String, _: Int, _: Double, id: js.UndefOr[String], hash: js.UndefOr[String]) => {
        descriptions += 1
        assertEquals(id.toOption, Some("token1"))
        assertEquals(hash.toOption.map(_.length), Some(64))
        js.Promise.resolve(js.Dynamic.literal(code = AccountRejection.TokenRevoked.code))
      }): js.Function5[String, Int, Double, js.UndefOr[String], js.UndefOr[String], js.Promise[js.Dynamic]]
    )
    val namespace = js.Dynamic.literal(
      idFromName = ((name: String) => name): js.Function1[String, String],
      get = ((_: js.Any) => stub): js.Function1[js.Any, js.Dynamic]
    )
    val env = js.Dynamic.literal(AUTH_SECRET = secret, AGREEMENT_VERSION = "v1", ACCOUNT_DO = namespace).asInstanceOf[WorkerEnv]
    val request = HandlerTestKit.request("https://www.3ch.io/test/bbs.cgi", headers = Seq("User-Agent" -> userAgent, "CF-Connecting-IP" -> "192.0.2.1"))
    val posting = PostingRequest.from(request)
    val source = new PostingAccountSource(request, env)
    val context = PostingContext(PostForm(bbs = "sample", key = "1800000000", message = "本文"),
      PostingIdentity(Some("192.0.2.1"), "192.0.2.1", userAgent, "", "", "2516", "JP", "Tokyo", "", "salt"),
      (now * 1000).toLong.toString, now.toLong.toString, number = 2)
    for {
      hash <- AuthHmac.uaHash(secret, userAgent)
      binding = SessionBinding("token1", hash.get.take(16))
      claims = SessionClaims(aid, "abcdefgh", 1, oldExpiry.toLong, 1, "", Some(binding))
      accepted = new PostingAuthenticationResult.Accepted(account, AccountVia.Token, Some("token1"), None, "", Some(claims), refreshSession = false)
      authenticated = PostingAccount(Some(PostingUser(aid, "abcdefgh")), 1, authentication = Some(accepted))
      headers <- source.posted(context, posting, authenticated)
      cookie = headers.find(_._1 == "Set-Cookie").get._2.takeWhile(_ != ';')
      refreshed <- SessionCookies.verify(cookie, secret, now)
      rejected <- source.authenticate("", None, posting.copy(cookie = cookie), now)
    } yield {
      assertEquals(refreshed.flatMap(_.binding), Some(binding))
      assertEquals(refreshed.map(_.expiresAt), Some((now + AccountProfile.clientLifetime).toLong))
      assertEquals(rejected.toOption.flatMap(_.user), None)
      assertEquals(descriptions, 1)
    }
  }
}
