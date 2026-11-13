package io._3ch.bbs.domain.posting.moderation

import scala.scalajs.js

/** 規制ルールの動作欄。空・deny・md5 は拒否 */
enum ModerationAction {
  case Deny, Mark, TateDame
}

/** ルールの書式の誤り */
enum ModerationRuleProblem {
  case Format, Action, Pattern
}

/** ルールの書式の誤りと、その行番号（1 始まり） */
final case class ModerationRuleError(line: Int, problem: ModerationRuleProblem)

/** deny.cgi / ngwords.cgi の 1 行。板・対象・NG ワードは JavaScript の正規表現で、空欄は全対象 */
final class ModerationRule private (board: js.RegExp, target: js.RegExp, word: js.RegExp, val action: ModerationAction, val isWord: Boolean) {
  /** 板名と投稿者情報が一致し、照合する文字列のどれかが NG ワードに一致するか */
  def matches(bbs: String, userInfo: String, contents: Seq[String]): Boolean =
    board.test(bbs) && target.test(userInfo) && contents.exists(word.test)
}

object ModerationRule {
  private[moderation] def parse(line: String): Either[ModerationRuleProblem, ModerationRule] = {
    val parts = line.split("<>", -1)
    if (parts.length != 4) Left(ModerationRuleProblem.Format)
    else action(parts(3)).toRight(ModerationRuleProblem.Action).flatMap { action =>
      try {
        def regex(i: Int) = new js.RegExp(if (parts(i).isEmpty) ".*" else parts(i))
        Right(new ModerationRule(regex(0), regex(1), regex(2), action, parts(2).nonEmpty))
      } catch {
        case _: js.JavaScriptException => Left(ModerationRuleProblem.Pattern)
      }
    }
  }

  private def action(value: String): Option[ModerationAction] = value match {
    case "" | "deny" | "md5" => Some(ModerationAction.Deny)
    case "mark" => Some(ModerationAction.Mark)
    case "TateDame" => Some(ModerationAction.TateDame)
    case _ => None
  }
}

/** 規制ルールの一覧。JVM の正規表現とは受理する言語が違うので JavaScript の RegExp を使う */
final case class ModerationRuleSet(rules: List[ModerationRule])

object ModerationRuleSet {
  private val Skipped = new js.RegExp("^\\s*(?:#|$)")

  /** 板<>対象<>NGワード<>動作 の行を読む。空行と # で始まる行は飛ばし、誤りがあれば最初の行を返す */
  def parse(text: String): Either[ModerationRuleError, ModerationRuleSet] = {
    val (errors, rules) = text.split("\r?\n", -1).toList.zipWithIndex.collect {
      case (line, index) if !Skipped.test(line) => ModerationRule.parse(line).left.map(ModerationRuleError(index + 1, _))
    }.partitionMap(identity)
    errors.headOption.toLeft(ModerationRuleSet(rules))
  }
}
