package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState, WorkerEnv}
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

  /** 確定した uid と cap は退会後も削除しない */
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

  /** 自分の確定前の予約だけを取り消す。確定済みの値は取り消さない */
  def release(hash: String, expectedAid: String): js.Promise[String] = DurableLock.serialized(state) {
    if (!validHash(hash)) Future.successful(AccountRejection.Invalid.code)
    else read(hash).flatMap {
      case None => Future.successful("")
      case Some(entry) => entry.release(expectedAid) match {
        case Left(error) => Future.successful(error.code)
        case Right(_) => state.storage.delete(hash).toFuture.map(_ => "")
      }
    }
  }.toJSPromise

  /** aid の期限内の予約か確定があるか。Account.grantFirstAdmin が確保の有無を確かめるのに使う */
  def seat(hash: String, aid: String, now: Double): js.Promise[Boolean] =
    if (!validHash(hash)) js.Promise.resolve[Boolean](false) else read(hash).map(_.exists(_.claimedBy(aid, now))).toJSPromise

  private def seats(now: Double): Future[Vector[AdminSeat]] = state.storage.list().toFuture.flatMap { values =>
    val entries = values.toVector.flatMap { case (key, value) => AccountIndexCodec.decode(value).map(key -> _) }
    val accounts = env.asInstanceOf[WorkerEnv].ACCOUNT_DO
    Future.traverse(entries) { case (key, entry) => accounts.get(accounts.idFromName(entry.aid)).adminStatus(now).toFuture.map(AdminSeat(key, entry, _)) }
  }

  /** aid の admin を外す前に、他に利用できる admin が居ることを確かめて索引から外す。最後の admin は拒否する */
  def surrender(hash: String, aid: String, now: Double): js.Promise[String] = DurableLock.serialized(state) {
    if (kind != AccountIndexKind.Admin || !validHash(hash) || !AccountProfile.validTime(now)) Future.successful(AccountRejection.Invalid.code)
    else seats(now).flatMap { all =>
      if (!FirstAdminClaim.otherAdminExists(all, aid)) Future.successful(AccountRejection.Forbidden.code)
      else state.storage.delete(hash).toFuture.map(_ => "")
    }
  }.toJSPromise

  /**
    * role:admin 索引で初回の管理者を予約する。有効な admin が居なければ（非常用期間なら居ても）予約し、
    * 利用できなくなった所有者の索引は外す。続く Account.grantFirstAdmin と confirm は呼び出し元が行い、途中で止まっても予約は外さず admin の有無から揃え直す
    */
  def claimFirstAdmin(hash: String, aid: String, now: Double, breakGlass: Boolean): js.Promise[String] = DurableLock.serialized(state) {
    if (kind != AccountIndexKind.Admin || !validHash(hash)) Future.successful(AccountRejection.Invalid.code)
    else seats(now).flatMap { all =>
      FirstAdminClaim.claim(all, aid, now, breakGlass) match {
        case Left(error) => Future.successful(error.code)
        case Right((stale, entry)) =>
          Future.traverse(stale)(key => state.storage.delete(key).toFuture)
            .flatMap(_ => state.storage.put(hash, AccountIndexCodec.encode(entry)).toFuture).map(_ => "")
      }
    }
  }.toJSPromise
}
