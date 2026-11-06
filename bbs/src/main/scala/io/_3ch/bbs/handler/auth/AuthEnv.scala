package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.AuthSettings
import io._3ch.bbs.external.cloudflare.WorkerEnv

/** Worker の環境変数から認証の設定を読む */
object AuthEnv {
  /** 設定が揃っていなければ None。このとき画面と投稿は認証の無い従来の動作にする */
  def settings(env: WorkerEnv): Option[AuthSettings] =
    AuthSettings.of(env.AUTH_SECRET.getOrElse(""), env.AGREEMENT_VERSION.getOrElse(""), env.TURNSTILE_SITE_KEY.getOrElse(""), env.TURNSTILE_SECRET_KEY.getOrElse(""))
}
