package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.account.{EdgeDecision, EdgeRules, EdgeSignal}
import scala.concurrent.Future

/** エッジ防御の規則と判定の記録。読めないときは規則なしを返す */
trait EdgeRuleStore {
  def load(): Future[EdgeRules]
  def report(endpoint: String, signal: EdgeSignal, decision: EdgeDecision): Unit
}

object EdgeRuleStore {
  /** 規則を持たず、何も記録しない */
  val empty: EdgeRuleStore = new EdgeRuleStore {
    def load(): Future[EdgeRules] = Future.successful(EdgeRules.empty)
    def report(endpoint: String, signal: EdgeSignal, decision: EdgeDecision): Unit = ()
  }
}
