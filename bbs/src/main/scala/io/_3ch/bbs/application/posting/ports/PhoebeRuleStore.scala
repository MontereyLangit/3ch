package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.moderation.PhoebeCapRules
import scala.concurrent.Future

/** Lv の上限ルール。無い・読めない・壊れているときは規則なしを返す */
trait PhoebeRuleStore {
  def load(): Future[PhoebeCapRules]
}

object PhoebeRuleStore {
  val empty: PhoebeRuleStore = () => Future.successful(PhoebeCapRules.empty)
}
