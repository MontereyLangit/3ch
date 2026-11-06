package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.cloudflare.WorkerEnv
import scala.scalajs.js

/** 認証を有効にする最小の設定。秘密はテスト専用の値 */
object AuthTestEnv {
  val secret: String = "test-auth-secret-0123456789abcdefghij"

  def env(values: (String, js.Any)*): WorkerEnv = {
    val raw = js.Dynamic.literal(AUTH_SECRET = secret, AGREEMENT_VERSION = "v1", TURNSTILE_SITE_KEY = "test-site-key", TURNSTILE_SECRET_KEY = "test-server-key")
    values.foreach { case (name, value) => raw.updateDynamic(name)(value) }
    raw.asInstanceOf[WorkerEnv]
  }
}
