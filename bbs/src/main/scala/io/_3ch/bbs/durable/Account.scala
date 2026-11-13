package io._3ch.bbs.durable

import io._3ch.bbs.external.cloudflare.{DurableObject, DurableObjectState, WorkerEnv}
import io._3ch.bbs.durable.codec.{AccountCodec, PasswordCredentialCodec}
import io._3ch.bbs.hash.{PasswordHashFailure, PasswordHasher}
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

  /** 新規アカウントを作る。既存のアカウントは上書きしない。パスワード方式は資格情報を一緒に保存する */
  def create(profile: js.Dynamic, now: Double, credential: js.UndefOr[js.Dynamic] = js.undefined): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    read().flatMap {
      case Some(_) => Future.successful(error(AccountRejection.Exists))
      case None =>
        val decoded = AccountCodec.decodeProfile(profile)
        val password = credential.toOption.map(PasswordCredentialCodec.decode(_))
        if (state.id.name.toOption.exists(_ != decoded.aid)) Future.successful(error(AccountRejection.Invalid))
        else AccountState.create(decoded, now, password) match {
          case Left(rejection) => Future.successful(error(rejection))
          case Right(account) => save(account).map(_ => AccountCodec.description(account.description))
        }
    }
  }.toJSPromise

  /** 索引を確定した登録準備中のアカウントの利用を開始する。開始済みなら現在の情報を返す */
  def activate(now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.activate(now).flatMap(next => next.usable(now).map(_ => next)) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right(next) if next == account => Future.successful(AccountCodec.description(next.description))
        case Right(next) => save(next).map(_ => AccountCodec.description(next.description))
      }
    }
  }.toJSPromise

  /** PBKDF2 で照合し、接続元ごとの失敗計数とロックを同じ直列化の中で保存する。アカウントが無ければ固定の資格情報で同じ計算をして失敗にする */
  def verifyPassword(password: String, keyIpHash: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    val parsed = Option(password).filter(p => js.typeOf(p) == "string").flatMap(p => Password.parse(p).toOption)
    read().flatMap { value =>
      val checked = value.toRight(AccountRejection.Missing).flatMap(_.passwordCheck(keyIpHash, now))
      val credential = checked.fold(_ => PasswordCredential.placeholder, _._1)
      parsed.fold(Future.successful[Either[PasswordHashFailure, Boolean]](Right(false)))(PasswordHasher.verify(_, credential)).flatMap {
        case Left(_) => Future.failed(js.JavaScriptException(new js.Error("password-hash-unavailable")))
        case Right(matched) => (value, checked) match {
          case (Some(account), Right(_)) => account.afterPasswordAttempt(keyIpHash, matched, now) match {
            case Left(rejection) => Future.successful(error(rejection))
            case Right((next, PasswordAttempt.Failed)) => save(next).map(_ => AccountCodec.loginFailure())
            case Right((next, PasswordAttempt.Accepted)) => rehash(next, parsed, now).flatMap { saved =>
              save(saved).map(_ => AccountCodec.signIn(saved.usable(now).map(_ => saved.description), saved.profile.uid))
            }
          }
          case _ => Future.successful(AccountCodec.loginFailure())
        }
      }
    }
  }.toJSPromise

  private def rehash(account: AccountState, password: Option[Password], now: Double): Future[AccountState] =
    (account.credential, password) match {
      case (Some(credential), Some(value)) if credential.needsRehash =>
        PasswordHasher.create(value, now).map(_.toOption.flatMap(next => account.rehashed(next).toOption).getOrElse(account))
      case _ => Future.successful(account)
    }

  /** パスワードを置き換えて世代を進める。照合済みかどうかは呼び出し元が確認する */
  def setPassword(credential: js.Dynamic, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.setPassword(PasswordCredentialCodec.decode(credential), now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right(next) => save(next).map(_ => AccountCodec.description(next.description))
      }
    }
  }.toJSPromise

  /** 保留トークンを種別ごとに置き換える。再設定なら送り先のアドレスを返す */
  def issuePending(kind: String, hash: String, email: js.UndefOr[String], now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    PendingKind.parse(kind).fold(Future.successful(error(AccountRejection.Invalid))) { pendingKind =>
      current(error(AccountRejection.Missing)) { account =>
        account.issuePending(pendingKind, hash, email.toOption, now) match {
          case Left(rejection) => Future.successful(error(rejection))
          case Right(next) => save(next).map(_ => AccountCodec.pending(next, pendingKind))
        }
      }
    }
  }.toJSPromise

  /** 一致する期限内の保留トークンがあれば、ユーザー ID と確認先のアドレスを返す。消費はしない */
  def checkPending(kind: String, hash: String, now: Double): js.Promise[js.Dynamic] = read().map { value =>
    PendingKind.parse(kind).toRight(AccountRejection.Invalid).flatMap(k => value.toRight(AccountRejection.Missing).flatMap(a => a.pendingFor(k, hash, now).map(a -> _))) match {
      case Left(rejection) => error(rejection)
      case Right((account, token)) => js.Dynamic.literal(code = "", uid = account.profile.uid, email = token.email.orUndefined)
    }
  }.toJSPromise

  /** メール認証のトークンを消費してアドレスを確定させる。置き換えた前のアドレスも返す */
  def verifyEmail(hash: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.verifyEmail(hash, now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right((next, previous)) => save(next).map { _ =>
          val result = AccountCodec.description(next.description)
          previous.foreach(value => result.previousEmail = value)
          result
        }
      }
    }
  }.toJSPromise

  /** 再設定のトークンを消費してパスワードを置き換え、世代を進める。専ブラキーは全て無効にして token 索引から外す */
  def resetPassword(hash: String, credential: js.Dynamic, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.resetPassword(hash, PasswordCredentialCodec.decode(credential), now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right(next) => save(next).flatMap(_ => release(account, next)).map(_ => AccountCodec.description(next.description))
      }
    }
  }.toJSPromise

  /** クライアント ID を移す。kind が password なら credential、google なら googleSubHash（と参考の email）を渡す */
  def convert(kind: String, uid: String, generation: Int, now: Double, credential: js.UndefOr[js.Dynamic] = js.undefined,
    googleSubHash: js.UndefOr[String] = js.undefined, email: js.UndefOr[String] = js.undefined): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    val target = AccountKind.parse(kind) match {
      case Some(AccountKind.Password) => credential.toOption.map(c => AccountConversion.ToPassword(PasswordCredentialCodec.decode(c)))
      case Some(AccountKind.Google) => googleSubHash.toOption.map(AccountConversion.ToGoogle(_, email.toOption))
      case _ => None
    }
    target.fold(Future.successful(error(AccountRejection.Invalid))) { value =>
      current(error(AccountRejection.Missing)) { account =>
        account.convert(value, uid, generation, now) match {
          case Left(rejection) => Future.successful(error(rejection))
          case Right(next) => save(next).map(_ => AccountCodec.description(next.description))
        }
      }
    }
  }.toJSPromise

  /** 退会する。外す索引の値と、失効させたキャップの照合値を返す。専ブラキーは token 索引から外す */
  def withdraw(generation: Int, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.withdraw(generation, now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right((next, withdrawal)) => save(next).flatMap(_ => release(account, next)).map(_ => AccountCodec.withdrawal(withdrawal))
      }
    }
  }.toJSPromise

  /** Google の sub の HMAC が一致すれば、ログインに使うアカウントの情報を返す */
  def describeGoogle(subHash: String, now: Double): js.Promise[js.Dynamic] =
    read().map(_.fold(error(AccountRejection.Missing))(account => AccountCodec.signIn(account.describeGoogle(subHash, now), account.profile.uid))).toJSPromise

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

  /** キャップを失効させ、KV と索引から外すための照合値を返す。KV と索引の更新は呼び出し元が行う */
  def revokeCap(capId: String, now: Double): js.Promise[js.Dynamic] = DurableLock.serialized(state) {
    current(error(AccountRejection.Missing)) { account =>
      account.revokeCap(capId, now) match {
        case Left(rejection) => Future.successful(error(rejection))
        case Right((next, cap)) => save(next).map(_ => AccountCodec.capRevocation(cap, None))
      }
    }
  }.toJSPromise

  /** 有効なキャップを失効させ、同じ表示名・強さで照合値だけ新しいキャップを登録する。古い照合値と新しいキャップを返す */
  def reissueCap(capId: String, nextId: String, nextHash: String, issuedBy: String, issuerIsAdmin: Boolean, now: Double): js.Promise[js.Dynamic] =
    DurableLock.serialized(state) {
      current(error(AccountRejection.Missing)) { account =>
        account.reissueCap(capId, nextId, nextHash, issuedBy, issuerIsAdmin, now) match {
          case Left(rejection) => Future.successful(error(rejection))
          case Right((next, old, fresh)) => save(next).map(_ => AccountCodec.capRevocation(old, Some(fresh)))
        }
      }
    }.toJSPromise

  /** 操作する人（actorAid）の今の役割で役割を置き換える。呼び出し元の申告は使わず、admin 以外と上位の役割を持つ相手への操作は拒否する */
  def setRoles(roles: js.Array[String], actorAid: String, now: Double): js.Promise[String] =
    Roles.parse(roles.toSeq) match {
      case Some(next) if actorAid.matches("[a-z0-9]{16}") =>
        val accounts = env.asInstanceOf[WorkerEnv].ACCOUNT_DO
        accounts.get(accounts.idFromName(actorAid)).activeRoles(now).toFuture.flatMap { actor =>
          Roles.parse(actor.toSeq).fold(Future.successful(AccountRejection.Invalid.code))(value => update(_.assignRoles(next, value, now)).toFuture)
        }.toJSPromise
      case _ => js.Promise.resolve[String](AccountRejection.Invalid.code)
    }

  /** role:admin 索引（hash）にこのアカウントの確保がある場合に限り、初回の管理者として admin を足す */
  def grantFirstAdmin(hash: String, now: Double): js.Promise[String] = {
    val index = env.asInstanceOf[WorkerEnv].ACCOUNT_INDEX_DO
    val aid = state.id.name.toOption.getOrElse("")
    index.get(index.idFromName(AccountIndexKind.Admin.code)).seat(hash, aid, now).toFuture.flatMap(claimed => update(_.grantFirstAdmin(claimed, now)).toFuture).toJSPromise
  }

  /** 利用できるアカウントの今の役割。凍結・退会・未登録は空 */
  def activeRoles(now: Double): js.Promise[js.Array[String]] =
    read().map(_.fold(Roles())(_.activeRoles(now)).values.toVector.map(_.code).sorted.toJSArray).toJSPromise

  /** 退会していないアカウントが、索引の値を今も持っているか。値はログイン ID・Google の sub の HMAC・メールの正規形 */
  def holds(kind: String, value: String): js.Promise[Boolean] =
    read().map(account => AccountIndexKind.parse(kind).exists(k => account.exists(_.holds(k, value)))).toJSPromise

  /** 利用できる admin か。凍結・退会や役割を外したアカウントは偽 */
  def adminStatus(now: Double): js.Promise[Boolean] = read().map(_.exists(_.activeAdmin(now))).toJSPromise

  /** 管理者による Lv の固定値を保存する。未指定で固定を外す */
  def setLevel(lvOverride: js.UndefOr[Int], now: Double): js.Promise[String] = update(_.setLevelOverride(lvOverride.toOption, now))

  /** 成功した書き込みの期限更新・計数と Lv の昇格、ログを一括保存する */
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
