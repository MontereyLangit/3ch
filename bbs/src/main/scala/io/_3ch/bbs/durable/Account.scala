package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState, WorkerEnv}
import io._3ch.bbs.durable.codec.AccountCodec
import io._3ch.bbs.durable.support.DurableLock
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** アカウントの状態を保存し、認証とキーの状態遷移を直列化する */
@JSExportTopLevel("Account", "server")
class Account(val state: DurableObjectState, env: js.Any) extends DurableObject(state, env) {
  private def read(): Future[Option[AccountState]] = state.storage.get(AccountCodec.stateKeys).toFuture.map(AccountCodec.decodeState)
  private def save(account: AccountState): Future[Unit] = state.storage.put(AccountCodec.encodeState(account)).toFuture
  private def error(reason: AccountRejection): js.Dynamic = AccountCodec.describe(Left(reason))
  private def current[A](missing: => A)(f: AccountState => Future[A]): Future[A] = read().flatMap(_.fold(Future.successful(missing))(f))

  /** 無効にしたキーを token 索引から外す。索引の障害では保存済みの状態を戻さない */
  private def release(before: AccountState, after: AccountState): Future[Unit] = {
    val hashes = DedicatedBrowserTokens.released(before.tokens, after.tokens)
    if (hashes.isEmpty) Future.unit
    else Future.unit.flatMap { _ =>
      val namespace = env.asInstanceOf[WorkerEnv].ACCOUNT_INDEX_DO
      val index = namespace.get(namespace.idFromName(AccountIndexKind.Token.code))
      Future.traverse(hashes)(hash => index.remove(hash, after.profile.aid).toFuture).map(_ => ())
    }.recover { case scala.util.control.NonFatal(_) => js.Dynamic.global.console.warn("account token index cleanup failed") }
  }

  private def saveWithRejection(account: AccountState, post: AccountPost): Future[Unit] =
    state.storage.get[js.Any](AccountCodec.rejectionsKey).toFuture.flatMap { value =>
      val entries = AccountCodec.encodeState(account)
      entries(AccountCodec.rejectionsKey) = AccountCodec.encodeRejections(AccountRejectionLog.append(AccountCodec.decodeRejections(value.asInstanceOf[js.Any]), post))
      state.storage.put(entries).toFuture
    }

  private def saveWithLog(account: AccountState, post: AccountPost): Future[Unit] = {
    state.storage.get[js.Any]("logMeta").toFuture.map(v => AccountCodec.decodeLogPosition(v.asInstanceOf[js.Any])).flatMap { position =>
      val key = s"log:${position.pageSlot}"
      state.storage.get[js.Any](key).toFuture.flatMap { value =>
        val (next, page) = position.append(AccountCodec.decodeLogPage(value.asInstanceOf[js.Any]), post)
        val entries = AccountCodec.encodeState(account)
        entries("logMeta") = next.next.toDouble
        entries(key) = AccountCodec.encodeLogPage(page)
        state.storage.put(entries).toFuture
      }
    }
  }

  private def update(f: AccountState => Either[AccountRejection, AccountState]): js.Promise[String] = DurableLock.serialized(state) {
    current(AccountRejection.Missing.code) { account =>
      f(account) match {
        case Left(rejection) => Future.successful(rejection.code)
        case Right(next) => save(next).flatMap(_ => release(account, next)).map(_ => "")
      }
    }
  }.toJSPromise

  /** 新規アカウントを作る。既存のアカウントは上書きしない */
  def create(profile: js.Dynamic, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    read().flatMap {
      case Some(_) => Future.successful(error(AccountRejection.Exists))
      case None =>
        val decoded = AccountCodec.decodeProfile(profile)
        if (state.id.name.toOption.exists(_ != decoded.aid)) Future.successful(error(AccountRejection.Invalid))
        else AccountState.create(decoded, now) match {
          case Left(rejection) => Future.successful(error(rejection))
          case Right(account) => save(account).map(_ => AccountCodec.description(account.description))
        }
    }
  }.toJSPromise

  /** 世代・状態・失効と、専ブラ Cookie のキーと UA を一度で検証する */
  def describeForPosting(aid: String, generation: Int, now: Double, tokenId: js.UndefOr[String] = js.undefined, uaHash: js.UndefOr[String] = js.undefined): js.Promise[js.Dynamic] =
    read().map(_.fold(error(AccountRejection.Missing))(account => AccountCodec.describe(account.describe(aid, generation, now, tokenId.toOption, uaHash.toOption)))).toJSPromise

  /** 所有キャップが失効していないことを確認する */
  def describeForCap(aid: String, capHash: String, now: Double): js.Promise[js.Dynamic] = read().map { value =>
    value.toRight(AccountRejection.Missing).flatMap(_.describeForCap(aid, capHash, now)) match {
      case Left(rejection) => error(rejection)
      case Right((description, cap)) => AccountCodec.capDescription(description, cap.capId)
    }
  }.toJSPromise

  /** 未使用の専ブラキーを一個発行し、前の未使用キーを失効させる */
  def issueToken(tokenId: String, hash: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.issueToken(tokenId, hash, now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right(next) => save(next).flatMap(_ => release(account, next)).map(_ => AccountCodec.tokenDescription(next.description, tokenId))
      }
    }
  }.toJSPromise

  /** キーと UA の照合・初回紐付けを保存する。成功はログへ、拒否は上限付きの拒否記録へ残す */
  def useToken(tokenId: String, uaHash: String, uaLabel: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      val (next, result) = account.useToken(tokenId, uaHash, uaLabel, now)
      val post = AccountPost(now, via = AccountVia.Token, tokenId = Some(tokenId), result = result.fold(_.code, _ => "authenticated"), uaLabel = uaLabel)
      val saved = result match {
        case Left(_) => saveWithRejection(next, post)
        case Right(_) => saveWithLog(next, post)
      }
      saved.flatMap(_ => release(account, next)).map(_ => result.fold(error, _ => AccountCodec.tokenDescription(next.description, tokenId)))
    }
  }.toJSPromise

  /** キーを失効させ、そこから発行した Cookie を使えなくする */
  def revokeToken(tokenId: String, now: Double): js.Promise[String] = update(_.revokeToken(tokenId, now))

  /** 法的投稿確認の版と同意時刻を更新する */
  def agree(version: String, now: Double): js.Promise[String] = update(_.agree(version, now))

  /** 世代を進めて全端末をログアウトさせる */
  def bumpGeneration(now: Double): js.Promise[Int] = DurableLock.serialized(state) {
    current[Either[AccountRejection, AccountState]](Left(AccountRejection.Missing))(account => Future.successful(account.bumpGeneration(now))).flatMap {
      case Left(rejection) => Future.failed(js.JavaScriptException(new js.Error(rejection.code)))
      case Right(next) => save(next).map(_ => next.profile.generation)
    }
  }.toJSPromise

  /** 端末の観測を最新十件まで保持する */
  def bindDevice(hash: String, keyIpHash: String, now: Double): js.Promise[String] = update(_.bindDevice(hash, keyIpHash, now))

  /** 発行者を確認済みのキャップを所有先に登録する */
  def bindCap(cap: js.Dynamic, issuerIsAdmin: Boolean, now: Double): js.Promise[String] = update(_.bindCap(AccountCodec.decodeCap(cap), issuerIsAdmin, now))

  /** キャップを失効させる。KV と索引の更新は呼び出し元が行う */
  def revokeCap(capId: String, now: Double): js.Promise[String] = update(_.revokeCap(capId, now))

  /** 成功した書き込みの期限更新・計数とログを一括保存する */
  def recordPost(now: Double, post: js.Dynamic, observation: js.Dynamic): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      val entry = AccountCodec.decodePost(post)
      account.recordPost(now, entry, AccountCodec.decodeObservation(observation)) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right(next) => saveWithLog(next, entry).flatMap(_ => release(account, next)).map(_ => AccountCodec.description(next.description))
      }
    }
  }.toJSPromise

  /** 本人向けのアカウント情報と利用履歴。照合用ハッシュは返さない */
  def inspect(): js.Promise[js.Dynamic] = {
    val now = math.floor(js.Date.now() / 1000)
    current(error(AccountRejection.Missing)) { account =>
      state.storage.get(((0 until 16).map(i => s"log:$i") :+ AccountCodec.rejectionsKey).toJSArray).toFuture.map { values =>
        val logs = (0 until 16).flatMap(i => values.get(s"log:$i")).map(AccountCodec.decodeLogPage).toVector.sortBy(_.number).flatMap(_.entries)
        AccountCodec.inspect(account, logs, AccountCodec.decodeRejections(values.get(AccountCodec.rejectionsKey).getOrElse(js.undefined)), now)
      }
    }.toJSPromise
  }
}
