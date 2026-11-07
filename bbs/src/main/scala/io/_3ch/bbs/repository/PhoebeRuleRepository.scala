package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.PhoebeRuleStore
import io._3ch.bbs.domain.posting.moderation.PhoebeCapRules
import io._3ch.bbs.external.cloudflare.WorkerEnv
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.control.NonFatal

/** R2 の phoebe.cgi（Lv の上限ルール） */
object PhoebeRuleRepository {
  /** 読めない・壊れているときは上限なしにして記録する（投稿は止めない） */
  def load(env: WorkerEnv): Future[PhoebeCapRules] =
    Future.unit.flatMap(_ => env.ch3www.get("phoebe.cgi").toFuture).flatMap { value =>
      if (value == null) Future.successful(PhoebeCapRules.empty)
      else value.text().toFuture.map(text => PhoebeCapRules.parse(text).fold(error => {
        js.Dynamic.global.console.warn(s"phoebe.cgi is broken at line ${error.line}")
        PhoebeCapRules.empty
      }, identity))
    }.recover { case NonFatal(_) => PhoebeCapRules.empty }

  def store(env: WorkerEnv): PhoebeRuleStore = () => load(env)
}
