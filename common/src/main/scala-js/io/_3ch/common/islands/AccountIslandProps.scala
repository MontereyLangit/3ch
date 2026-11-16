package io._3ch.common.islands

import io._3ch.common.validation.{JsField, JsKind, JsShape}
import io._3ch.common.validation.JsShape.{optional, required}

import scala.scalajs.js

/** AuthFormJs の props */
trait AuthFormProps extends js.Object {
  val turnstileSiteKey: String
  val returnPath: String
  val embed: Boolean
  val login: Boolean
  val agreementVersion: String
  val googleEnabled: Boolean
  val notice: String
  val mailEnabled: Boolean
  val upgrade: Boolean
}

object AuthFormProps {
  val fields: List[JsField] = List(
    required("turnstileSiteKey", JsKind.Str), required("returnPath", JsKind.Str), required("embed", JsKind.Bool), required("login", JsKind.Bool),
    required("agreementVersion", JsKind.Str), required("googleEnabled", JsKind.Bool), required("notice", JsKind.Str),
    required("mailEnabled", JsKind.Bool), required("upgrade", JsKind.Bool)
  )
  def validate(raw: js.Any): Either[String, AuthFormProps] = JsShape.validate(raw, fields)
}

/** AuthTokenJs の props。mode は "reset"（パスワードの再設定）か "email"（メールアドレスの確認） */
trait AuthTokenProps extends js.Object {
  val mode: String
  val token: String
  val enabled: Boolean
  val uid: String
  val email: String
}

object AuthTokenProps {
  val fields: List[JsField] = List(
    required("mode", JsKind.Str), required("token", JsKind.Str), required("enabled", JsKind.Bool), required("uid", JsKind.Str), required("email", JsKind.Str)
  )
  def validate(raw: js.Any): Either[String, AuthTokenProps] = JsShape.validate(raw, fields)
}

/** AccountCredentialsJs の props */
trait AccountCredentialsProps extends js.Object {
  val email: String
  val pendingEmail: String
  val mailEnabled: Boolean
}

object AccountCredentialsProps {
  val fields: List[JsField] = List(required("email", JsKind.Str), required("pendingEmail", JsKind.Str), required("mailEnabled", JsKind.Bool))
  def validate(raw: js.Any): Either[String, AccountCredentialsProps] = JsShape.validate(raw, fields)
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

object AccountPostItemProps {
  val fields: List[JsField] = List(
    required("t", JsKind.Num), required("bbs", JsKind.Str), required("key", JsKind.Str), required("title", JsKind.Str),
    required("resNum", JsKind.Int), required("excerpt", JsKind.Str), required("network", JsKind.Str)
  )
}

/** 別の専用ブラウザからキーが使われそうになった記録 */
trait AccountTokenWarningProps extends js.Object {
  val t: Double
  val uaLabel: String
}

object AccountTokenWarningProps {
  val fields: List[JsField] = List(required("t", JsKind.Num), required("uaLabel", JsKind.Str))
}

/** 専ブラ用キーの 1 件。時刻は UNIX 秒 */
trait AccountTokenItemProps extends js.Object {
  val id: String
  val label: String
  val issuedAt: Double
  val expiresAt: Double
  val recentPosts: js.Array[AccountPostItemProps]
  val warnings: js.Array[AccountTokenWarningProps]
  val boundAt: js.UndefOr[Double]
  val lastUsedAt: js.UndefOr[Double]
  val revokedAt: js.UndefOr[Double]
  val replacedTokenAt: js.UndefOr[Double]
  val revokeReason: js.UndefOr[String]
}

object AccountTokenItemProps {
  val fields: List[JsField] = List(
    required("id", JsKind.Str), required("label", JsKind.Str), required("issuedAt", JsKind.Num), required("expiresAt", JsKind.Num),
    required("recentPosts", JsKind.Arr(JsKind.Obj(AccountPostItemProps.fields))),
    required("warnings", JsKind.Arr(JsKind.Obj(AccountTokenWarningProps.fields))),
    optional("boundAt", JsKind.Num), optional("lastUsedAt", JsKind.Num), optional("revokedAt", JsKind.Num), optional("replacedTokenAt", JsKind.Num),
    optional("revokeReason", JsKind.Str)
  )
}

/** AccountMenuJs の props */
trait AccountMenuProps extends js.Object {
  val tokens: js.Array[AccountTokenItemProps]
  val activeCount: Int
  val nowSeconds: Double
}

object AccountMenuProps {
  val fields: List[JsField] = List(
    required("tokens", JsKind.Arr(JsKind.Obj(AccountTokenItemProps.fields))), required("activeCount", JsKind.Int), required("nowSeconds", JsKind.Num)
  )
  def validate(raw: js.Any): Either[String, AccountMenuProps] = JsShape.validate(raw, fields)
}

/** 本人のキャップの 1 件。照合値とパスワードは持たない */
trait AccountCapItemProps extends js.Object {
  val id: String
  val name: String
  val strong: Boolean
  val issuedAt: Double
  val recentPosts: js.Array[AccountPostItemProps]
  val revokedAt: js.UndefOr[Double]
}

object AccountCapItemProps {
  val fields: List[JsField] = List(
    required("id", JsKind.Str), required("name", JsKind.Str), required("strong", JsKind.Bool), required("issuedAt", JsKind.Num),
    required("recentPosts", JsKind.Arr(JsKind.Obj(AccountPostItemProps.fields))), optional("revokedAt", JsKind.Num)
  )
}

/** AccountCapJs の props。canIssue は発行できる役割を持つか */
trait AccountCapProps extends js.Object {
  val caps: js.Array[AccountCapItemProps]
  val admin: Boolean
  val canIssue: Boolean
}

object AccountCapProps {
  val fields: List[JsField] = List(
    required("caps", JsKind.Arr(JsKind.Obj(AccountCapItemProps.fields))), required("admin", JsKind.Bool), required("canIssue", JsKind.Bool)
  )
  def validate(raw: js.Any): Either[String, AccountCapProps] = JsShape.validate(raw, fields)
}

/** AccountLogoutJs の props。kind はアカウントの登録方法のコード */
trait AccountLogoutProps extends js.Object {
  val simple: Boolean
  val kind: String
  val google: Boolean
}

object AccountLogoutProps {
  val fields: List[JsField] = List(required("simple", JsKind.Bool), required("kind", JsKind.Str), required("google", JsKind.Bool))
  def validate(raw: js.Any): Either[String, AccountLogoutProps] = JsShape.validate(raw, fields)
}
