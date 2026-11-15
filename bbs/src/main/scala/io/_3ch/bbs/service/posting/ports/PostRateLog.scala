package io._3ch.bbs.service.posting.ports

import io._3ch.bbs.domain.posting.PostRateObservation
import io._3ch.bbs.domain.posting.moderation.PostRateFinding

import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 試行モードの判定の記録先 */
trait PostRateLog {
  /** 判定を記録する。記録先の障害は確定した投稿の成否に影響させない */
  def record(observation: PostRateObservation): Unit
}

object PostRateLog {
  /** Workers Logs に 1 行の JSON で出す */
  val console: PostRateLog = observation => js.Dynamic.global.console.log(js.JSON.stringify(js.Dynamic.literal(
    event = "post-rate", mode = "log", bbs = observation.bbs, key = observation.key, number = observation.number,
    codes = observation.findings.map(_.code).toJSArray, ip = observation.ip, shiki = observation.shiki)))
}
