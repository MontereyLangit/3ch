package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState}
import io._3ch.bbs.durable.codec.AccountIndexCodec
import io._3ch.bbs.durable.support.DurableLock
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** 種別ごとの一意な索引。予約の所有者を確かめてから確定・削除する */
@JSExportTopLevel("AccountIndex", "server")
class AccountIndex(val state: DurableObjectState, env: js.Any) extends DurableObject(state, env) {
  private def kind: AccountIndexKind = state.id.name.toOption.flatMap(AccountIndexKind.parse)
    .getOrElse(throw js.JavaScriptException(new js.Error("invalid-account-index-kind")))
  private def validHash(hash: String): Boolean = DedicatedBrowserTokens.validHash(hash)
  private def read(hash: String): Future[Option[AccountIndexEntry]] = state.storage.get[js.Any](hash).toFuture.map(v => AccountIndexCodec.decode(v.asInstanceOf[js.Any]))

  /** 十分古い未確定予約だけを取り直せる。成功は空文字 */
  def register(hash: String, aid: String, now: Double, tokenId: js.UndefOr[String] = js.undefined): js.Promise[String] = DurableLock.serialized(state) {
    if (!validHash(hash)) Future.successful(AccountRejection.Invalid.code)
    else read(hash).flatMap { previous =>
      AccountIndexEntry.register(previous, aid, now, tokenId.toOption, kind) match {
        case Left(error) => Future.successful(error.code)
        case Right(entry) => state.storage.put(hash, AccountIndexCodec.encode(entry)).toFuture.map(_ => "")
      }
    }
  }.toJSPromise

  /** 予約したアカウントだけが有効期限内に確定できる */
  def confirm(hash: String, expectedAid: String, now: Double): js.Promise[String] = DurableLock.serialized(state) {
    if (!validHash(hash) || !AccountProfile.validTime(now)) Future.successful(AccountRejection.Invalid.code)
    else read(hash).flatMap { value =>
      value.toRight(AccountRejection.IndexOwnership).flatMap(_.confirm(expectedAid, now)) match {
        case Left(error) => Future.successful(error.code)
        case Right(entry) => state.storage.put(hash, AccountIndexCodec.encode(entry)).toFuture.map(_ => "")
      }
    }
  }.toJSPromise

  /** 未確定の索引は利用せず、確定済みの所有先だけを返す */
  def lookup(hash: String): js.Promise[js.Dynamic] = {
    if (!validHash(hash)) js.Promise.resolve(js.Dynamic.literal(code = AccountRejection.Invalid.code))
    else read(hash).map(AccountIndexCodec.lookup).toJSPromise
  }

  /** 確定した uid は退会後も削除しない */
  def remove(hash: String, expectedAid: String): js.Promise[String] = DurableLock.serialized(state) {
    if (!validHash(hash)) Future.successful(AccountRejection.Invalid.code)
    else read(hash).flatMap { value =>
      value match {
        case None => Future.successful("")
        case Some(entry) => entry.remove(kind, expectedAid) match {
          case Left(error) => Future.successful(error.code)
          case Right(_) => state.storage.delete(hash).toFuture.map(_ => "")
        }
      }
    }
  }.toJSPromise
}
