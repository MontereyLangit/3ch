package io._3ch.common.islands

import scala.scalajs.js

/** AuthFormJs の props */
trait AuthFormProps extends js.Object {
  val turnstileSiteKey: String
  val returnPath: String
  val embed: Boolean
  val login: js.UndefOr[Boolean]
  val agreementVersion: String
  val googleEnabled: js.UndefOr[Boolean]
  val notice: js.UndefOr[String]
  val mailEnabled: js.UndefOr[Boolean]
  val upgrade: js.UndefOr[Boolean]
}

/** AuthTokenJs の props。mode は "reset"（パスワードの再設定）か "email"（メールアドレスの確認） */
trait AuthTokenProps extends js.Object {
  val mode: String
  val token: String
  val enabled: Boolean
  val uid: String
  val email: String
}

/** AccountCredentialsJs の props */
trait AccountCredentialsProps extends js.Object {
  val email: String
  val pendingEmail: String
  val mailEnabled: Boolean
}

/** アカウント設定画面に出す書き込み記録の 1 件。t は UNIX 秒 */
trait AccountPostItemProps extends js.Object {
  val t: Double
  val bbs: String
  val key: String
  val title: String
  val resNum: Int
  val excerpt: String
  val network: String
}

/** 別の専用ブラウザからキーが使われそうになった記録 */
trait AccountTokenWarningProps extends js.Object {
  val t: Double
  val uaLabel: String
}

/** 専ブラ用キーの 1 件。時刻は UNIX 秒 */
trait AccountTokenItemProps extends js.Object {
  val id: String
  val label: String
  val issuedAt: js.UndefOr[Double]
  val expiresAt: js.UndefOr[Double]
  val recentPosts: js.UndefOr[js.Array[AccountPostItemProps]]
  val warnings: js.UndefOr[js.Array[AccountTokenWarningProps]]
  val boundAt: js.UndefOr[Double]
  val lastUsedAt: js.UndefOr[Double]
  val revokedAt: js.UndefOr[Double]
  val replacedTokenAt: js.UndefOr[Double]
  val revokeReason: js.UndefOr[String]
}

/** AccountMenuJs の props */
trait AccountMenuProps extends js.Object {
  val tokens: js.Array[AccountTokenItemProps]
  val activeCount: js.UndefOr[Int]
  val nowSeconds: js.UndefOr[Double]
}

/** 本人のキャップの 1 件。照合値とパスワードは持たない */
trait AccountCapItemProps extends js.Object {
  val id: String
  val name: String
  val strong: Boolean
  val issuedAt: Double
  val recentPosts: js.UndefOr[js.Array[AccountPostItemProps]]
  val revokedAt: js.UndefOr[Double]
}

/** AccountCapJs の props。canIssue は発行できる役割を持つか */
trait AccountCapProps extends js.Object {
  val caps: js.Array[AccountCapItemProps]
  val admin: Boolean
  val canIssue: Boolean
}

/** AccountLogoutJs の props。kind はアカウントの登録方法のコード */
trait AccountLogoutProps extends js.Object {
  val simple: Boolean
  val kind: js.UndefOr[String]
  val google: js.UndefOr[Boolean]
}
