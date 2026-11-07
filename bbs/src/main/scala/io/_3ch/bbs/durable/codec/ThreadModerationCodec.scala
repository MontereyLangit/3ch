package io._3ch.bbs.durable.codec

import io._3ch.bbs.application.admin.ports.ThreadRecord
import io._3ch.bbs.external.cloudflare.ThreadInspectResult
import io._3ch.common.admin.ThreadAction
import scala.scalajs.js

/** Thread DO の moderate の引数と inspect の結果の変換 */
object ThreadModerationCodec {
  /** moderate の input。管理画面のフォームと同じ項目名にする */
  def encode(bbs: String, key: String, action: ThreadAction): js.Dynamic = {
    val input = js.Dynamic.literal(bbs = bbs, key = key)
    action match {
      case ThreadAction.Move(targetBoard, targetKey) =>
        input.targetBoard = targetBoard
        input.targetKey = targetKey
      case ThreadAction.EditRes(number, name, mail, message) =>
        input.number = number
        input.name = name
        input.mail = mail
        input.message = message
      case ThreadAction.DeleteRes(number) => input.number = number
      case ThreadAction.Stop | ThreadAction.Resume | ThreadAction.Delete => ()
    }
    input
  }

  def decode(result: ThreadInspectResult): ThreadRecord = ThreadRecord(
    dat = result.dat.getOrElse(""),
    log = result.log.getOrElse(""),
    legacyLog = result.legacyLog.toOption,
    settings = result.settings.getOrElse(""),
    hidden = result.hidden.getOrElse(false),
    stopped = result.stopped.getOrElse(false),
    movedTo = result.movedTo.toOption,
    movingTo = result.movingTo.toOption
  )
}
