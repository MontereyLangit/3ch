package io._3ch.admin.components

import japgolly.scalajs.react.{Callback, CallbackTo}
import org.scalajs.dom

/** 確認のダイアログ。ask が偽なら確かめずに action を実行する */
object Confirm {
  val DiscardMessage = "編集中の内容を捨てて開き直しますか？"

  def apply(ask: Boolean, message: => String)(action: Callback): Callback =
    CallbackTo(!ask || dom.window.confirm(message)).flatMap(ok => Callback.when(ok)(action))
}
