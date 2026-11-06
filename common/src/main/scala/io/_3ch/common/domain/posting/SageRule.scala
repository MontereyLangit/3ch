package io._3ch.common.domain.posting

import io._3ch.common.domain.BbsSettings
import io._3ch.common.text.JsNumber

/** 書き込みでスレを上げないか（sage）の判定 */
object SageRule {
  /**
    * 返信で、メール欄に sage があるか、key + BBS_FORCE_SAGE の秒数を過ぎたか、BBS_LIVE で roll（0 以上 1 未満）が 0.1 以上なら sage
    * スレ立ては常に上げる。roll は BBS_LIVE の判定に進んだときだけ読む
    */
  def sage(number: Int, mail: String, key: String, unixtime: String, settings: BbsSettings, roll: => Double): Boolean =
    number >= 2 && (
      mail.contains("sage") ||
        JsNumber.parseInt(key) + settings.int("BBS_FORCE_SAGE", "9999999999") < JsNumber.parseInt(unixtime) ||
        (settings.checked("BBS_LIVE") && roll * 10 >= 1)
    )
}
