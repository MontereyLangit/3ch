package io._3ch.bbs.domain.posting.moderation

import scala.scalajs.js

/** 規制ルールの動作欄。空・deny・md5 は拒否 */
enum ModerationAction {
  case Deny, Mark, TateDame
}

/** ルールの書式の誤り */
enum ModerationRuleProblem {
  case Format, Action, Pattern, Bypass
}

/** ルールの書式の誤りと、その行番号（1 始まり） */
final case class ModerationRuleError(line: Int, problem: ModerationRuleProblem)

/** deny.cgi / ngwords.cgi の 1 行。板・対象・NG ワードは JavaScript の正規表現で、空欄は全対象。bypass はこの行を免除される段階 */
final class ModerationRule private (board: js.RegExp, target: js.RegExp, word: js.RegExp, val action: ModerationAction, val isWord: Boolean,
  val bypass: BypassLevel) {
  /** 板名と投稿者情報が一致し、照合する文字列のどれかが NG ワードに一致するか */
  def matches(bbs: String, userInfo: String, contents: Seq[String]): Boolean =
    board.test(bbs) && target.test(userInfo) && contents.exists(word.test)
}

object ModerationRule {
  private[moderation] def parse(line: String): Either[ModerationRuleProblem, ModerationRule] = {
    val parts = line.split("<>", -1)
    if (parts.length != 4 && parts.length != 5) Left(ModerationRuleProblem.Format)
    else for {
      action <- action(parts(3)).toRight(ModerationRuleProblem.Action)
      bypass <- bypass(parts.lift(4).getOrElse("")).toRight(ModerationRuleProblem.Bypass)
      rule <- try {
        def regex(i: Int) = new js.RegExp(if (parts(i).isEmpty) ".*" else parts(i))
        Right(new ModerationRule(regex(0), regex(1), regex(2), action, parts(2).nonEmpty, bypass))
      } catch {
        case _: js.JavaScriptException => Left(ModerationRuleProblem.Pattern)
      }
    } yield rule
  }

  /** 5 列目の回避レベル。省略と空欄は既定の privileged */
  private def bypass(value: String): Option[BypassLevel] = if (value.trim.isEmpty) Some(BypassLevel.Default) else BypassLevel.parse(value)

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

  /** 板<>対象<>NGワード<>動作（<>回避レベル）の行を読む。空行と # で始まる行は飛ばし、誤りがあれば最初の行を返す */
  def parse(text: String): Either[ModerationRuleError, ModerationRuleSet] = {
    val (errors, rules) = text.split("\r?\n", -1).toList.zipWithIndex.collect {
      case (line, index) if !Skipped.test(line) => ModerationRule.parse(line).left.map(ModerationRuleError(index + 1, _))
    }.partitionMap(identity)
    errors.headOption.toLeft(ModerationRuleSet(rules))
  }
}
