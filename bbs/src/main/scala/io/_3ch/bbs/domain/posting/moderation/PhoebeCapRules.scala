package io._3ch.bbs.domain.posting.moderation

import io._3ch.common.domain.account.Phoebe
import scala.scalajs.js

/** phoebe.cgi の 1 行。対象は投稿者情報（userInfo）に当てる JavaScript の正規表現で、空欄は全対象 */
final class PhoebeCapRule private (target: js.RegExp, val limit: Int) {
  def matches(userInfo: String): Boolean = target.test(userInfo)
}

object PhoebeCapRule {
  private[moderation] def parse(line: String): Either[ModerationRuleProblem, PhoebeCapRule] = {
    val parts = line.split("<>", -1)
    if (parts.length != 2) Left(ModerationRuleProblem.Format)
    else {
      val limit = parts(1).trim
      if (!limit.matches("[0-9]{1,2}") || limit.toInt > Phoebe.MaxLevel) Left(ModerationRuleProblem.Action)
      else try Right(new PhoebeCapRule(new js.RegExp(if (parts(0).isEmpty) ".*" else parts(0)), limit.toInt))
      catch { case _: js.JavaScriptException => Left(ModerationRuleProblem.Pattern) }
    }
  }
}

/** Lv の上限ルール（R2 の phoebe.cgi）。当てはまる行の上限のうち最も低いもので実効 Lv を抑え、アカウントの Lv は下げない */
final case class PhoebeCapRules(rules: List[PhoebeCapRule]) {
  def cap(lv: Int, userInfo: String): Int = rules.iterator.filter(_.matches(userInfo)).map(_.limit).foldLeft(lv)(math.min)
}

object PhoebeCapRules {
  val empty: PhoebeCapRules = PhoebeCapRules(Nil)
  private val Skipped = new js.RegExp("^\\s*(?:#|$)")

  /** 対象<>上限Lv の行を読む。空行と # で始まる行は飛ばし、誤りがあれば最初の行を返す */
  def parse(text: String): Either[ModerationRuleError, PhoebeCapRules] = {
    val (errors, rules) = text.split("\r?\n", -1).toList.zipWithIndex.collect {
      case (line, index) if !Skipped.test(line) => PhoebeCapRule.parse(line).left.map(ModerationRuleError(index + 1, _))
    }.partitionMap(identity)
    errors.headOption.toLeft(PhoebeCapRules(rules))
  }
}
