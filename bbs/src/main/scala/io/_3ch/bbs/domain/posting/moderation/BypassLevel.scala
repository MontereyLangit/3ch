package io._3ch.bbs.domain.posting.moderation

/** 規制を免除する投稿者の段階（設計書 §7）。value は設定に書く値 */
enum BypassLevel(val value: String) {
  case Admin extends BypassLevel("admin")
  case Staff extends BypassLevel("staff")
  case Privileged extends BypassLevel("privileged")
  case L10 extends BypassLevel("l10")
  case Login extends BypassLevel("login")

  /** 免除するか。権限と Lv はまだ無いので、admin・staff は誰も免除せず、l10 は privileged と同じになる */
  def exempts(cap: Boolean, authenticated: Boolean): Boolean = this match {
    case Admin | Staff => false
    case Privileged | L10 => cap
    case Login => cap || authenticated
  }
}

object BypassLevel {
  /** 設定の値を読む。知らない値なら None */
  def parse(value: String): Option[BypassLevel] = values.find(_.value == value.trim)
}
