package io._3ch.common.domain.account

/** 登録の準備状態。凍結などの利用状態とは分け、準備中は認証に使わない */
enum AccountReadiness(val code: String) {
  case Pending extends AccountReadiness("pending")
  case Ready extends AccountReadiness("ready")
}

object AccountReadiness {
  def parse(code: String): Option[AccountReadiness] = values.find(_.code == code)
}
