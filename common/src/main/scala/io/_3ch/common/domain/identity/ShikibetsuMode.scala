package io._3ch.common.domain.identity

/** 識別子の表示方式（BBS_ID_SHIKIBETSU の値）。Phoebe は県・AS と端末の5軸・Lv の書式 */
enum ShikibetsuMode {
  case Checked, Raw, Ken, City, As, Phoebe, Hidden
}

object ShikibetsuMode {
  /** 設定値を読む。知らない値は Hidden */
  def parse(value: String): ShikibetsuMode = value match {
    case "checked" => ShikibetsuMode.Checked
    case "raw" => ShikibetsuMode.Raw
    case "ken" => ShikibetsuMode.Ken
    case "city" => ShikibetsuMode.City
    case "as" => ShikibetsuMode.As
    case "phoebe" => ShikibetsuMode.Phoebe
    case _ => ShikibetsuMode.Hidden
  }
}
