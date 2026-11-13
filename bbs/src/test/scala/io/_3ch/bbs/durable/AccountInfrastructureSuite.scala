package io._3ch.bbs.durable

import io._3ch.bbs.durable.codec.AccountCodec
import io._3ch.bbs.external.cloudflare.DurableObjectState
import io._3ch.common.domain.account.*
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class AccountInfrastructureSuite extends FunSuite {
  private val now = 2000000000d
  private val aid = "abcdefghijklmnop"
  private val otherAid = "qrstuvwxyz012345"
  private val hash = "a" * 64
  private val profile = AccountProfile(aid, "user1234", AccountKind.Client, now, None, now, "v1")

  private class Storage(name: String) {
    val data = js.Map[String, js.Any]()
    var failWrites = false
    private var tail = Future.unit
    private def clone(value: js.Any): js.Any = js.Dynamic.global.structuredClone(value).asInstanceOf[js.Any]
    private val storage = js.Dynamic.literal(
      get = ((key: js.Any) => {
        val result: js.Any = if (js.Array.isArray(key)) {
          val values = js.Map[String, js.Any]()
          key.asInstanceOf[js.Array[String]].foreach(k => data.get(k).foreach(v => values.update(k, clone(v))))
          values
        } else data.get(key.asInstanceOf[String]).map(clone).getOrElse(js.undefined)
        js.Promise.resolve(result)
      }): js.Function1[js.Any, js.Promise[js.Any]],
      put = ((key: js.Any, value: js.UndefOr[js.Any]) => {
        if (failWrites) js.Promise.reject(new js.Error("write failed"))
        else {
          if (value.isDefined) data.update(key.asInstanceOf[String], clone(value.get))
          else key.asInstanceOf[js.Dictionary[js.Any]].foreach { case (k, v) => data.update(k, clone(v)) }
          js.Promise.resolve[js.Any](js.undefined)
        }
      }): js.Function2[js.Any, js.UndefOr[js.Any], js.Promise[js.Any]],
      delete = ((key: String) => {
        val existed = data.contains(key)
        data.subtractOne(key)
        js.Promise.resolve(existed)
      }): js.Function1[String, js.Promise[Boolean]]
    )
    val state: DurableObjectState = js.Dynamic.literal(
      id = js.Dynamic.literal(name = name), storage = storage,
      blockConcurrencyWhile = ((callback: js.Function0[js.Promise[js.Any]]) => {
        val result = tail.flatMap(_ => callback().toFuture)
        tail = result.transform(_ => scala.util.Success(()))
        result.toJSPromise
      }): js.Function1[js.Function0[js.Promise[js.Any]], js.Promise[js.Any]]
    ).asInstanceOf[DurableObjectState]
  }

  test("同時のアカウント作成は一方だけを保存し同時発行では未使用キーを一個に保つ") {
    val storage = new Storage(aid)
    val account = new Account(storage.state, js.Dynamic.literal())
    for {
      results <- Future.sequence(Vector.fill(2)(account.create(AccountCodec.encodeProfile(profile), now).toFuture))
      _ = assertEquals(results.map(_.code.asInstanceOf[String]).sorted, Vector("", AccountRejection.Exists.code).sorted)
      _ <- Future.sequence(Vector(
        account.issueToken("token1", hash, now + 1).toFuture,
        account.issueToken("token2", "b" * 64, now + 1).toFuture))
      saved = AccountCodec.decodeState(storage.data).get
      _ = assertEquals(saved.tokens.count(_.active(now + 1)), 1)
      _ = assertEquals(saved.tokens.head.revokeReason, Some(TokenRevokeReason.Superseded))
    } yield ()
  }

  test("Account の保存が失敗した後もゲートを解放し中途半端な世代を保存しない") {
    val storage = new Storage(aid)
    val account = new Account(storage.state, js.Dynamic.literal())
    for {
      _ <- account.create(AccountCodec.encodeProfile(profile), now).toFuture
      _ = storage.failWrites = true
      failure <- DurableTestKit.rejectionMessage(account.bumpGeneration(now + 1).toFuture)
      _ = assertEquals(failure, "write failed")
      _ = assertEquals(AccountCodec.decodeState(storage.data).get.profile.generation, 0)
      _ = storage.failWrites = false
      generation <- account.bumpGeneration(now + 2).toFuture
      _ = assertEquals(generation, 1)
    } yield ()
  }

  test("キーの UA 不一致は上限付きの拒否記録だけに保存し既存の UA 紐づけと書き込みログを維持する") {
    val storage = new Storage(aid)
    val account = new Account(storage.state, js.Dynamic.literal())
    for {
      _ <- account.create(AccountCodec.encodeProfile(profile), now).toFuture
      _ <- account.issueToken("token1", hash, now).toFuture
      accepted <- account.useToken("token1", "b" * 64, "専ブラ", now + 1).toFuture
      _ = assertEquals(accepted.code.asInstanceOf[String], "")
      rejected <- account.useToken("token1", "c" * 64, "別の専ブラ", now + 2).toFuture
      _ = assertEquals(rejected.code.asInstanceOf[String], AccountRejection.TokenUaMismatch.code)
      saved = AccountCodec.decodeState(storage.data).get
      _ = assertEquals(saved.tokens.head.uaHash, Some("b" * 64))
      _ = assertEquals(saved.tokens.head.revokedAt, None)
      page = AccountCodec.decodeLogPage(storage.data("log:0"))
      _ = assertEquals(page.entries.map(_.result), Vector("authenticated"))
      _ = assertEquals(AccountCodec.decodeRejections(storage.data(AccountCodec.rejectionsKey)).map(_.result), Vector(AccountRejection.TokenUaMismatch.code))
      _ <- Future.traverse((1 to 30).toVector)(i => account.useToken("token1", "c" * 64, "別の専ブラ", now + 2 + i).toFuture)
      _ = assertEquals(AccountCodec.decodeRejections(storage.data(AccountCodec.rejectionsKey)).size, AccountRejectionLog.limit)
      _ = assertEquals(AccountCodec.decodeLogPage(storage.data("log:0")).entries.size, 1)
    } yield ()
  }

  test("置き換え・失効で無効になったキーを token 索引から外す") {
    val storage = new Storage(aid)
    val removed = scala.collection.mutable.Buffer.empty[(String, String, String)]
    val namespace = js.Dynamic.literal(
      idFromName = ((name: String) => name): js.Function1[String, String],
      get = ((name: String) => js.Dynamic.literal(remove = ((hash: String, owner: String) => {
        removed += ((name, hash, owner)); js.Promise.resolve("")
      }): js.Function2[String, String, js.Promise[String]])): js.Function1[String, js.Dynamic])
    val account = new Account(storage.state, js.Dynamic.literal(ACCOUNT_INDEX_DO = namespace))
    for {
      _ <- account.create(AccountCodec.encodeProfile(profile), now).toFuture
      _ <- account.issueToken("token1", hash, now).toFuture
      _ = assert(removed.isEmpty)
      _ <- account.issueToken("token2", "b" * 64, now + 1).toFuture
      _ = assertEquals(removed.toList, List(("token", hash, aid)))
      _ <- account.revokeToken("token2", now + 2).toFuture
      _ = assertEquals(removed.last, ("token", "b" * 64, aid))
      _ <- account.revokeToken("token2", now + 3).toFuture
    } yield assertEquals(removed.size, 2)
  }

  test("成功投稿はアカウントとログを一括保存し失敗時には両方を維持する") {
    val storage = new Storage(aid)
    val account = new Account(storage.state, js.Dynamic.literal())
    for {
      _ <- account.create(AccountCodec.encodeProfile(profile), now).toFuture
      _ = storage.failWrites = true
      message <- DurableTestKit.rejectionMessage(account.recordPost(now + 1,
        AccountCodec.encodePost(AccountPost(now + 1)), js.Dynamic.literal()).toFuture)
      _ = assertEquals(message, "write failed")
      _ = assertEquals(AccountCodec.decodeState(storage.data).get.level.lvPosts, 0)
      _ = assert(!storage.data.contains("logMeta"))
      _ = storage.failWrites = false
      _ <- account.recordPost(now + 2, AccountCodec.encodePost(AccountPost(now + 2)), js.Dynamic.literal()).toFuture
      _ = assertEquals(AccountCodec.decodeState(storage.data).get.level.lvPosts, 1)
      _ = assertEquals(AccountCodec.decodeLogPage(storage.data("log:0")).entries.size, 1)
    } yield ()
  }

  test("uid 索引は競合・取り直し・所有者確認・確定後の削除拒否を保存状態に反映する") {
    val storage = new Storage("uid")
    val index = new AccountIndex(storage.state, js.Dynamic.literal())
    for {
      results <- Future.sequence(Vector(aid, otherAid).map(id => index.register(hash, id, now).toFuture))
      _ = assertEquals(results.count(_ == ""), 1)
      pending <- index.lookup(hash).toFuture
      _ = assertEquals(pending.code.asInstanceOf[String], "auth:index:missing")
      _ <- index.register(hash, otherAid, now + 600).toFuture
      stale <- index.confirm(hash, aid, now + 601).toFuture
      _ = assertEquals(stale, AccountRejection.IndexOwnership.code)
      confirmed <- index.confirm(hash, otherAid, now + 601).toFuture
      _ = assertEquals(confirmed, "")
      refused <- index.remove(hash, otherAid).toFuture
      _ = assertEquals(refused, AccountRejection.IndexPermanent.code)
      lookup <- index.lookup(hash).toFuture
      _ = assertEquals(lookup.aid.asInstanceOf[String], otherAid)
    } yield ()
  }

  test("AuthRate の同時計数は履歴を失わず保存障害は成功の計数を返さない") {
    val storage = new Storage("key-ip")
    val rate = new AuthRate(storage.state, js.Dynamic.literal())
    for {
      results <- Future.sequence(Vector.fill(10)(rate.hit("verify", now).toFuture))
      _ = assertEquals(results.map(_.hour.asInstanceOf[Int]).sorted, (1 to 10).toVector)
      _ = storage.failWrites = true
      message <- DurableTestKit.rejectionMessage(rate.hit("verify", now + 1).toFuture)
      _ = assertEquals(message, "write failed")
      _ = storage.failWrites = false
      next <- rate.hit("verify", now + 2).toFuture
      _ = assertEquals(next.day.asInstanceOf[Int], 11)
    } yield ()
  }
}
