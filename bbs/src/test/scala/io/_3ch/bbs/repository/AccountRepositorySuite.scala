package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.ports.TokenReference
import io._3ch.bbs.durable.codec.AccountCodec
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class AccountRepositorySuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val account = AccountDescription(aid, "user1234", AccountKind.Client, 1, Some(now + 31536000), 1, Roles(), now, "v1")

  private final class Fixture {
    var accountReply = AccountCodec.description(account)
    var indexReply = js.Dynamic.literal(code = "", aid = aid, tokenId = "token1")
    var rateReply = js.Dynamic.literal(code = "", hour = 1, day = 2, distinctDevices24h = 1)
    var panelReply: js.Dynamic = js.Dynamic.literal()
    var nextGeneration: js.Any = 2
    var commandReply: js.Any = ""
    private val stub = js.Dynamic.literal(
      describeForPosting = ((_: String, _: Int, _: Double, _: js.UndefOr[String], _: js.UndefOr[String]) => js.Promise.resolve(accountReply)): js.Function5[String, Int, Double, js.UndefOr[String], js.UndefOr[String], js.Promise[js.Dynamic]],
      describeForCap = ((_: String, _: String, _: Double) => js.Promise.resolve(accountReply)): js.Function3[String, String, Double, js.Promise[js.Dynamic]],
      useToken = ((_: String, _: String, _: String, _: Double) => js.Promise.resolve(accountReply)): js.Function4[String, String, String, Double, js.Promise[js.Dynamic]],
      lookup = ((_: String) => js.Promise.resolve(indexReply)): js.Function1[String, js.Promise[js.Dynamic]],
      hit = ((_: String, _: Double) => js.Promise.resolve(rateReply)): js.Function2[String, Double, js.Promise[js.Dynamic]],
      inspect = (() => js.Promise.resolve(panelReply)): js.Function0[js.Promise[js.Dynamic]],
      bumpGeneration = ((_: Double) => js.Promise.resolve(nextGeneration)): js.Function1[Double, js.Promise[js.Any]],
      bindDevice = ((_: String, _: String, _: Double) => js.Promise.resolve(commandReply)): js.Function3[String, String, Double, js.Promise[js.Any]]
    )
    private val namespace = js.Dynamic.literal(
      idFromName = ((name: String) => name): js.Function1[String, String],
      get = ((_: js.Any) => stub): js.Function1[js.Any, js.Dynamic]
    )
    val repository = new AccountRepository(js.Dynamic.literal(ACCOUNT_DO = namespace, ACCOUNT_INDEX_DO = namespace, AUTH_RATE_DO = namespace).asInstanceOf[WorkerEnv])
    def rejected[A](future: Future[A]): Future[Unit] = future.transform {
      case scala.util.Failure(error) =>
        assertEquals(error.getMessage, "auth:invalid")
        scala.util.Success(())
      case scala.util.Success(_) => scala.util.Failure(new AssertionError("expected invalid RPC response"))
    }
  }

  test("成功した認証結果を完全な型へ変換し既知・未知の拒否コードを区別する") {
    val fixture = new Fixture
    for {
      accepted <- fixture.repository.describe(aid, 1, now, None)
      _ = assertEquals(accepted, Right(account))
      _ = fixture.accountReply = js.Dynamic.literal(code = AccountRejection.Frozen.code)
      frozen <- fixture.repository.describe(aid, 1, now, None)
      _ = assertEquals(frozen, Left(AccountRejection.Frozen))
      _ = fixture.accountReply = js.Dynamic.literal(code = "unknown")
      unknown <- fixture.repository.describe(aid, 1, now, None)
      _ = assertEquals(unknown, Left(AccountRejection.Invalid))
    } yield ()
  }

  test("成功 DTO の欠損・未知の種別や役割・範囲外の値を拒否する") {
    val invalid = Vector[(String, js.Any)]("code" -> js.undefined, "code" -> 1, "aid" -> "qrstuvwxyz012345", "uid" -> "short",
      "kind" -> "unknown", "generation" -> -1, "generation" -> 1.5, "generation" -> 2147483648d,
      "expiresAt" -> js.undefined, "expiresAt" -> null, "lv" -> 11, "lv" -> Double.NaN,
      "roles" -> js.Array("unknown"), "roles" -> js.Array("admin"), "roles" -> js.undefined,
      "agreedAt" -> -1, "agreementVersion" -> "")
    invalid.foldLeft(Future.unit) { case (previous, (field, value)) => previous.flatMap { _ =>
      val fixture = new Fixture
      fixture.accountReply.updateDynamic(field)(value)
      fixture.rejected(fixture.repository.describe(aid, 1, now, None))
    } }
  }

  test("索引が欠落を明示したときだけ所有者なしとして扱う") {
    val fixture = new Fixture
    for {
      token <- fixture.repository.token("hash")
      _ = assertEquals(token, Right(Some(TokenReference(aid, "token1"))))
      _ = fixture.indexReply = js.Dynamic.literal(code = "auth:index:missing")
      missing <- fixture.repository.capOwner("hash")
      _ = assertEquals(missing, Right(None))
      _ = fixture.indexReply = js.Dynamic.literal()
      _ <- fixture.rejected(fixture.repository.capOwner("hash"))
      _ = fixture.indexReply = js.Dynamic.literal(code = "")
      _ <- fixture.rejected(fixture.repository.capOwner("hash"))
      _ = fixture.indexReply = js.Dynamic.literal(code = "", aid = aid)
      _ <- fixture.rejected(fixture.repository.token("hash"))
    } yield ()
  }

  test("キー認証・所有キャップの成功結果は参照 ID も必須とする") {
    val fixture = new Fixture
    for {
      _ <- fixture.rejected(fixture.repository.useToken(TokenReference(aid, "token1"), "a" * 64, "専ブラ", now))
      _ = fixture.accountReply.tokenId = "token2"
      _ <- fixture.rejected(fixture.repository.useToken(TokenReference(aid, "token1"), "a" * 64, "専ブラ", now))
      _ <- fixture.rejected(fixture.repository.describeCap(aid, "cap", now))
    } yield ()
  }

  test("認証計数は欠損・負数・小数と矛盾した窓を拒否する") {
    val invalid = Vector[(String, js.Any)]("code" -> js.undefined, "hour" -> -1, "day" -> 0, "day" -> 1.5,
      "distinctDevices24h" -> "1", "distinctDevices24h" -> js.undefined, "hour" -> Double.NaN)
    invalid.foldLeft(Future.unit) { case (previous, (field, value)) => previous.flatMap { _ =>
      val fixture = new Fixture
      fixture.rateReply.updateDynamic(field)(value)
      fixture.rejected(fixture.repository.rate("key-ip", "verify", now))
    } }
  }

  test("文字列のコマンド応答と世代更新も実行時の型を検証する") {
    val fixture = new Fixture
    fixture.commandReply = js.undefined
    fixture.nextGeneration = 2.5
    for {
      _ <- fixture.rejected(fixture.repository.bindDevice(aid, "", "a" * 64, now))
      _ <- fixture.rejected(fixture.repository.logout(aid, 1, now))
    } yield ()
  }

  test("本人向け一覧に投稿・置換・失効・UA 不一致の情報を変換する") {
    val fixture = new Fixture
    val token = DedicatedBrowserToken("token1", "a" * 64, now, now + 86400,
      uaHash = Some("b" * 64), uaLabel = Some("専ブラ"), boundAt = Some(now), lastUsedAt = Some(now + 1),
      recentPosts = Vector(TokenPost(now + 1, "bbs", "key", "題名", 2, "本文", "回線")))
    val state = AccountState(AccountProfile(aid, "user1234", AccountKind.Client, now, account.expiresAt, now, "v1", generation = 1),
      AccountLevel(1, now), tokens = Vector(token))
    val warning = AccountPost(now + 2, AccountVia.Token, Some("token1"), result = AccountRejection.TokenUaMismatch.code, uaLabel = "別の専ブラ")
    fixture.panelReply = AccountCodec.inspect(state, Vector(warning), now + 3)
    fixture.repository.inspect(aid).map { panel =>
      assertEquals(panel.account, account)
      assertEquals(panel.activeCount, 1)
      assertEquals(panel.tokens.head.label, "専ブラ")
      assertEquals(panel.tokens.head.recentPosts.head.network, "回線")
      assertEquals(panel.tokens.head.warnings, Vector(AccountTokenWarningView(now + 2, "別の専ブラ")))
      assertEquals(panel.tokens.head.lastUsedAt, Some(now + 1))
    }
  }
}
