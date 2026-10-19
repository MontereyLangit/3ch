package ch3s.common.domain

/**
 * 板やスレの設定
 * @param value 設定名と設定値のマップ。
 */
case class BbsSettings private (
  value: Map[String, String]
  // def convertToText(settings: BbsSettings): String
)

object BbsSettings {
  def create(value: Map[String, String]): Either[String, BbsSettings] = {
    val isValidValue = true
    Either.cond(isValidValue, BbsSettings(value), "error")
  }

  // def parse(text: String): Either[String, BbsSettings]
}
