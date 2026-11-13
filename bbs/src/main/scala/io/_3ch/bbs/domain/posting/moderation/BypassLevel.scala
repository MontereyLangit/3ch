package io._3ch.bbs.domain.posting.moderation

import io._3ch.common.domain.account.{Phoebe, Roles}

/** 規制の免除を判定する投稿者。cap はキャップを付けた投稿（★）、authenticated は認証済、lv は実効 Lv */
final case class BypassPoster(roles: Roles = Roles(), cap: Boolean = false, authenticated: Boolean = false, lv: Int = 0)

/** 規制を免除する投稿者の段階（設計書 §7）。value は設定に書く値 */
enum BypassLevel(val value: String) {
  case Admin extends BypassLevel("admin")
  case Staff extends BypassLevel("staff")
  case Privileged extends BypassLevel("privileged")
  case L10 extends BypassLevel("l10")
  case Login extends BypassLevel("login")

  /** 免除するか。cap 権限は名無しでは使えず、キャップを付けた投稿だけが privileged に入る */
  def exempts(poster: BypassPoster): Boolean = this match {
    case Admin => poster.roles.admin
    case Staff => poster.roles.staff
    case Privileged => poster.roles.staff || poster.cap
    case L10 => Privileged.exempts(poster) || (poster.authenticated && poster.lv >= Phoebe.MaxLevel)
    case Login => Privileged.exempts(poster) || poster.authenticated
  }
}

object BypassLevel {
  /** 指定が無いか知らない値のときの段階 */
  val Default: BypassLevel = Privileged

  /** 設定の値を読む。知らない値なら None */
  def parse(value: String): Option[BypassLevel] = values.find(_.value == value.trim)
}
