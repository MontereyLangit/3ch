package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountDescription, AccountProfile, AccountRejection, Password, PasswordCredential}
import scala.concurrent.Future

/** パスワード照合と Google の照合の結果 */
enum SignInResult {
  /** 本人と確認でき、利用できるアカウント */
  case Verified(account: AccountDescription)
  /** 本人と確認できたが、登録の確定が途中で止まっている */
  case Unfinished(uid: String)
  /** 照合に失敗した。存在しない ID・不一致・ロック中を区別しない */
  case Failed
  /** 本人と確認できたが、凍結などで利用できない */
  case Refused(reason: AccountRejection)
}

/** ID/Password と Google の登録・ログインに必要なアカウント操作 */
trait AccountSignInStore {
  /** 確定済みの索引の所有者。未登録や未確定なら None */
  def lookup(kind: String, hash: String): Future[Option[String]]
  def create(profile: AccountProfile, now: Double, credential: Option[PasswordCredential]): Future[AccountDescription]
  /** 索引を確定したアカウントの利用を開始する */
  def activate(aid: String, now: Double): Future[AccountDescription]
  /** Account DO の中でパスワードを照合し、失敗を数える */
  def verifyPassword(aid: String, password: String, keyIpHash: String, now: Double): Future[SignInResult]
  def describeGoogle(aid: String, subHash: String, now: Double): Future[SignInResult]
}

/** パスワードの資格情報を作る。暗号 API が使えなければ None */
trait PasswordHashing {
  def create(password: Password, now: Double): Future[Option[PasswordCredential]]
}
