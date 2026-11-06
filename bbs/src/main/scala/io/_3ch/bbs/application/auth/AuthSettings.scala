package io._3ch.bbs.application.auth

/** 認証の設定。秘密鍵・同意の版・Turnstile の鍵がすべて揃ったときだけ認証を有効にする */
final case class AuthSettings(secret: String, agreementVersion: String, turnstileSiteKey: String, turnstileSecretKey: String)

object AuthSettings {
  /** HMAC の鍵とみなす秘密の最短の長さ */
  val minimumSecretLength: Int = 32

  /** 欠けた値や短すぎる秘密があれば認証を無効にする */
  def of(secret: String, agreementVersion: String, turnstileSiteKey: String, turnstileSecretKey: String): Option[AuthSettings] =
    Option.when(secret.length >= minimumSecretLength && agreementVersion.nonEmpty && turnstileSiteKey.nonEmpty && turnstileSecretKey.nonEmpty) {
      AuthSettings(secret, agreementVersion, turnstileSiteKey, turnstileSecretKey)
    }
}
