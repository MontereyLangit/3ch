package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountCapStore, AccountStoreException, AuthenticationKeys, CapDirectory}
import io._3ch.common.domain.account.{AbuseThresholds, AccountCap, AccountDescription, AccountIndexKind, AccountProfile, AccountRejection, CapName, CapNameRejection}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 発行直後に一度だけ返すキャップ。password はメール欄に # を付けて入れる値で、永続化やログ出力に使わない */
final case class IssuedCap(capId: String, password: String)

/** キャップの表示名が使えないことを Future の失敗として運ぶ */
final case class CapNameException(reason: CapNameRejection) extends RuntimeException(reason.toString)

/**
  * キャップの発行・失効・再発行。Account に記録し、KV の CAP と cap 索引（照合キー → 所有者。失効後も残す）を揃える。
  * 発行できる人と数の判定は Account DO が行う
  */
final class CapIssuing(accounts: AccountCapStore, directory: CapDirectory, keys: AuthenticationKeys, capKey: String => Future[String]) {
  private val kind = AccountIndexKind.Cap.code

  private def unavailable: Nothing = throw AuthServiceException(AuthServiceFailure.Unavailable)
  private def indexHash(key: String): Future[String] = keys.index(kind, key).map(_.getOrElse(unavailable))

  /** 発行する人ごとに一日の回数を数えてから、新しいパスワードと照合キーを作る。KV に同じキーがあれば使わない */
  private def fresh(issuer: AccountDescription, now: Double): Future[(String, String, String)] = {
    val password = keys.token()
    for {
      counter <- keys.index("cap-issue", issuer.aid).map(_.getOrElse(unavailable))
      counts <- accounts.rate(counter, "cap", now)
      _ = if (!AbuseThresholds.capIssue(counts)) throw AuthServiceException(AuthServiceFailure.RateLimited)
      key <- capKey(password)
      taken <- directory.exists(key)
      _ = if (taken || key.isEmpty) unavailable
      hash <- indexHash(key)
    } yield (password, key, hash)
  }

  /** 索引を予約してから body を行い、失敗したら確定前の予約を外す。確定した cap 索引は外れない */
  private def reserved[A](hash: String, owner: String, now: Double)(body: => Future[A]): Future[A] =
    accounts.reserve(kind, hash, owner, now).recoverWith { case NonFatal(error) =>
      accounts.remove(kind, hash, owner).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
    }.flatMap { ok =>
      if (!ok) Future.failed(AuthServiceException(AuthServiceFailure.Unavailable))
      else body.recoverWith { case NonFatal(error) =>
        accounts.remove(kind, hash, owner).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
      }
    }

  /** 索引を確定してから KV に書く（KV に載った照合キーが所有者のない旧キャップに見えないように）。失敗したら新しいキャップを失効させて KV から消す */
  private def publish(owner: String, capId: String, key: String, hash: String, storedName: String, now: Double): Future[Unit] =
    (for {
      _ <- accounts.confirm(kind, hash, owner, now)
      _ <- directory.put(key, storedName)
    } yield ()).recoverWith { case NonFatal(error) =>
      for {
        _ <- accounts.revokeCap(owner, capId, now).map(_ => ()).recover { case NonFatal(_) => () }
        _ <- directory.delete(key).recover { case NonFatal(_) => () }
        result <- Future.failed[Unit](error)
      } yield result
    }

  /** KV から消す。cap 索引は外さず、KV の写しが残っても所有者の失効の記録で照合を拒否する */
  private def retire(key: String): Future[Unit] = directory.delete(key)

  /** issuer が owner に発行する。強キャップと他人宛ては admin だけ、ほかの役割保持者は自分宛てに一個まで */
  def issue(issuer: AccountDescription, owner: String, rawName: String, strong: Boolean, now: Double): Future[IssuedCap] =
    Future.unit.flatMap { _ =>
      val name = CapName.parse(rawName).fold(reason => throw CapNameException(reason), identity)
      if (!AccountProfile.validTime(now)) throw AuthServiceException(AuthServiceFailure.Invalid)
      fresh(issuer, now).flatMap { case (password, key, hash) =>
        val cap = AccountCap(keys.tokenId(), name, key, strong, issuer.aid, now)
        reserved(hash, owner, now) {
          accounts.bindCap(owner, cap, issuer.roles.admin, now).flatMap(_ => publish(owner, cap.capId, key, hash, cap.storedName, now))
        }.map(_ => IssuedCap(cap.capId, password))
      }
    }

  /** 所有者か admin が失効させ、KV から外す */
  def revoke(actor: AccountDescription, owner: String, capId: String, now: Double): Future[Unit] =
    Future.unit.flatMap { _ =>
      if (actor.aid != owner && !actor.roles.admin) throw AccountStoreException(AccountRejection.Forbidden)
      accounts.revokeCap(owner, capId, now).flatMap(retire)
    }

  /** 同じ表示名・強さで新しいパスワードにする。古いキャップは失効させて KV から外す */
  def reissue(actor: AccountDescription, owner: String, capId: String, now: Double): Future[IssuedCap] =
    Future.unit.flatMap { _ =>
      if (actor.aid != owner && !actor.roles.admin) throw AccountStoreException(AccountRejection.Forbidden)
      fresh(actor, now).flatMap { case (password, key, hash) =>
        val nextId = keys.tokenId()
        reserved(hash, owner, now) {
          accounts.reissueCap(owner, capId, nextId, key, actor.aid, actor.roles.admin, now).flatMap { result =>
            publish(owner, nextId, key, hash, result.storedName, now).map(_ => result.previousHash)
          }
        }.flatMap(previous => retire(previous).recover { case NonFatal(_) => () }).map(_ => IssuedCap(nextId, password))
      }
    }
}
