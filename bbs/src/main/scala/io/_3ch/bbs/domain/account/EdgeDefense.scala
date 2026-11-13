package io._3ch.bbs.domain.account

import scala.scalajs.js

/** エッジ防御の動作。Log は記録だけで拒否しない（試行モード） */
enum EdgeMode(val code: String) {
  case Log extends EdgeMode("log")
  case On extends EdgeMode("on")
}

/** UA が ua に一致するブラウザの TLS ClientHello の長さとして有り得る範囲 */
final case class TlsRule(ua: js.RegExp, minLength: Int, maxLength: Int) {
  def contradicts(userAgent: String, helloLength: Int): Boolean =
    ua.test(userAgent) && (helloLength < minLength || helloLength > maxLength)
}

/** R2 の edge-rules.json。datacenterAsns はデータセンターの AS 番号、rttMs は国内の遠い接続元の閾値 */
final case class EdgeRules(mode: EdgeMode = EdgeMode.Log, datacenterAsns: Set[String] = Set.empty, rttMs: Double = 400, tls: Vector[TlsRule] = Vector.empty)

object EdgeRules {
  /** 規則なし。ファイルが無い・読めないときもこれで通す */
  val empty: EdgeRules = EdgeRules()
}

/** エッジ防御で見つけた矛盾。code は記録と拒否のコード */
enum EdgeFinding(val code: String) {
  case Datacenter extends EdgeFinding("datacenter")
  case DeclarationMismatch extends EdgeFinding("declaration_mismatch")
}

/** 判定に使う request.cf と UA の値。無い値は None */
final case class EdgeSignal(asn: String, country: String, rttMs: Option[Double], tlsHelloLength: Option[Int], userAgent: String)

/** 判定の結果。reject は拒否するか、strict は乱造検知の閾値を半分にするか。どちらも試行モード（と規則なし）では常に偽で、記録だけ行う */
final case class EdgeDecision(findings: Vector[EdgeFinding], reject: Boolean, strict: Boolean) {
  def rejection: Option[EdgeFinding] = findings.headOption.filter(_ => reject)
}

/** request.cf によるエッジ防御（設計書 §3.6）。規則は edge-rules.json、免除は Lv3 以上で作成から7日以上の認証済み */
object EdgeDefense {
  val exemptionAge: Double = 7d * 86400

  /** 実効 Lv 3 以上で、アカウント作成から7日以上たっているか */
  def exempt(lv: Int, createdAt: Double, now: Double): Boolean = lv >= 3 && createdAt > 0 && now - createdAt >= exemptionAge

  /** browser はブラウザ向けの認証エンドポイントで、TLS の矛盾と RTT を見る。投稿ではデータセンターだけを見る */
  def check(rules: EdgeRules, signal: EdgeSignal, browser: Boolean, exempted: Boolean): EdgeDecision =
    if (exempted) EdgeDecision(Vector.empty, reject = false, strict = false)
    else {
      val datacenter = Option.when(rules.datacenterAsns.contains(signal.asn))(EdgeFinding.Datacenter)
      val tls = Option.when(browser && signal.tlsHelloLength.exists(length => rules.tls.exists(_.contradicts(signal.userAgent, length))))(EdgeFinding.DeclarationMismatch)
      val findings = datacenter.toVector ++ tls
      val enforced = rules.mode == EdgeMode.On
      EdgeDecision(findings, enforced && findings.nonEmpty, enforced && browser && signal.country == "JP" && signal.rttMs.exists(_ > rules.rttMs))
    }
}
