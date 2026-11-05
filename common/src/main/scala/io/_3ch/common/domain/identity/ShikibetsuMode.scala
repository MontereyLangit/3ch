package io._3ch.common.domain.identity

enum ShikibetsuMode {
  case Checked, Raw, Ken, City, As, Hidden
}

object ShikibetsuMode {
  def parse(value: String): ShikibetsuMode = value match {
    case "checked" => ShikibetsuMode.Checked
    case "raw" => ShikibetsuMode.Raw
    case "ken" => ShikibetsuMode.Ken
    case "city" => ShikibetsuMode.City
    case "as" => ShikibetsuMode.As
    case _ => ShikibetsuMode.Hidden
  }
}
