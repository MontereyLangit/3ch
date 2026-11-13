package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.EdgeRuleStore
import io._3ch.bbs.domain.account.{EdgeDecision, EdgeMode, EdgeRules, EdgeSignal, TlsRule}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.Request
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** R2 の edge-rules.json と request.cf の読み取り、判定の記録 */
object EdgeRuleRepository {
  /** 投稿処理のポート */
  def store(env: WorkerEnv): EdgeRuleStore = new EdgeRuleStore {
    def load(): Future[EdgeRules] = EdgeRuleRepository.load(env)
    def report(endpoint: String, signal: EdgeSignal, decision: EdgeDecision): Unit = EdgeRuleRepository.report(endpoint, signal, decision)
  }

  /** {"mode": "log"|"on", "datacenterAsns": [...], "rttMs": 400, "tls": [{"ua": 正規表現, "min": n, "max": n}]}。無い・読めない・壊れていれば規則なし */
  def load(env: WorkerEnv): Future[EdgeRules] =
    Future.unit.flatMap(_ => env.ch3www.get("edge-rules.json").toFuture).flatMap { value =>
      if (value == null) Future.successful(EdgeRules.empty) else value.text().toFuture.map(parse)
    }.recover { case NonFatal(_) => EdgeRules.empty }

  def parse(text: String): EdgeRules = Try {
    val raw = js.JSON.parse(text)
    def array(name: String): Vector[js.Dynamic] = {
      val v = raw.selectDynamic(name)
      if (js.Array.isArray(v)) v.asInstanceOf[js.Array[js.Dynamic]].toVector else Vector.empty
    }
    val mode = EdgeMode.values.find(m => js.typeOf(raw.mode) == "string" && raw.mode.asInstanceOf[String] == m.code).getOrElse(EdgeMode.Log)
    val asns = array("datacenterAsns").map(v => js.Dynamic.global.String(v).asInstanceOf[String]).filter(_.matches("[0-9]+")).toSet
    val rtt = if (js.typeOf(raw.rttMs) == "number") raw.rttMs.asInstanceOf[Double] else 400d
    val tls = array("tls").map { rule =>
      TlsRule(new js.RegExp(rule.ua.asInstanceOf[String]), rule.min.asInstanceOf[Double].toInt, rule.max.asInstanceOf[Double].toInt)
    }
    EdgeRules(mode, asns, rtt, tls)
  }.getOrElse {
    js.Dynamic.global.console.warn("edge-rules.json is broken")
    EdgeRules.empty
  }

  /** request.cf の AS 番号・国・RTT・ClientHello の長さと UA */
  def signal(request: Request): EdgeSignal = {
    val cf = request.cf.toOption
    def value(name: String): Option[String] = cf.flatMap { c =>
      val v = c.selectDynamic(name)
      Option.when(v != null && !js.isUndefined(v))(js.Dynamic.global.String(v).asInstanceOf[String])
    }
    EdgeSignal(value("asn").getOrElse(""), value("country").getOrElse(""), value("clientTcpRtt").flatMap(_.toDoubleOption),
      value("tlsClientHelloLength").flatMap(_.toIntOption), Option(request.headers.get("User-Agent")).getOrElse(""))
  }

  /** 見つけた矛盾を Workers Logs に一行の JSON で残す。応答には出さない */
  def report(endpoint: String, signal: EdgeSignal, decision: EdgeDecision): Unit = if (decision.findings.nonEmpty || decision.strict) {
    js.Dynamic.global.console.warn(js.JSON.stringify(js.Dynamic.literal(event = "edge", endpoint = endpoint, reject = decision.reject,
      strict = decision.strict, findings = decision.findings.map(_.code).toJSArray, asn = signal.asn, country = signal.country)))
  }
}
