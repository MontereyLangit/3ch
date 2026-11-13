package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ModerationDenial
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.{PostForm, UserInfo}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/** 規制ルールのファイル。この順に評価する */
enum ModerationRuleFile(val path: String) {
  case Access extends ModerationRuleFile("deny.cgi")
  case NgWords extends ModerationRuleFile("ngwords.cgi")
}

/** ルールファイルの読込結果。本文が読めないか書式が不正なら Broken */
enum ModerationRuleSource {
  case Missing
  case Broken
  case Loaded(rules: ModerationRuleSet)
}

/** 規制の判定結果。どちらも mark で名前・本文を加工した後のフォームを持つ */
enum ModerationDecision {
  case Pass(form: PostForm)
  case Deny(denial: ModerationDenial, form: PostForm)
}

/** 規制の判定に使う投稿。form は規制前のフォーム、number はレス番号（スレ立ては 1） */
final case class ModerationInput(form: PostForm, userInfo: UserInfo, cap: Boolean, country: String, number: Double, settings: BbsSettings) {
  /** NG ワードを照合する文字列と、その文字参照を復元したもの */
  val contents: Seq[String] = {
    val content = s"FROM=${form.name} mail=${form.mail} MESSAGE=${form.message} subject=${form.subject}"
    Seq(content, ModerationService.decodeEntities(content))
  }
}

/** deny.cgi、ngwords.cgi の順に規制を評価する */
object ModerationService {
  private val MarkName = "</b>[´・ω・｀]<b> "
  private val MarkMessage = " <small>👀 Madakana: Caution</small>"
  private val NumericEntity = new js.RegExp("&#(x[0-9a-f]+|\\d+);", "gi")
  private val NamedEntity = new js.RegExp("&(?:amp|lt|gt|quot|apos);", "g")
  private val Entities = Map("&amp;" -> "&", "&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'")

  /** キャップと saku 板は対象外、Tor は拒否。それ以外はファイルを順に読み、拒否が出たら後のファイルは読まない */
  def check(input: ModerationInput, load: ModerationRuleFile => Future[ModerationRuleSource])(using ExecutionContext): Future[ModerationDecision] = {
    def loop(files: List[ModerationRuleFile], form: PostForm): Future[ModerationDecision] = files match {
      case Nil => Future.successful(ModerationDecision.Pass(form))
      case file :: rest => load(file).flatMap {
        case ModerationRuleSource.Missing => loop(rest, form)
        case ModerationRuleSource.Broken => Future.successful(ModerationDecision.Deny(ModerationDenial.BrokenRules, form))
        case ModerationRuleSource.Loaded(rules) => evaluate(input, form, rules) match {
          case ModerationDecision.Pass(next) => loop(rest, next)
          case denied => Future.successful(denied)
        }
      }
    }
    if (input.cap || input.form.bbs == "saku") Future.successful(ModerationDecision.Pass(input.form))
    else if (input.country == "T1") Future.successful(ModerationDecision.Deny(ModerationDenial.Tor, input.form))
    else loop(ModerationRuleFile.values.toList, input.form)
  }

  /** 1 つのルール一覧を評価する。照合は規制前の内容で行い、mark は form に重ねる。BBS_NO_MADAKANA なら拒否も mark にする */
  def evaluate(input: ModerationInput, form: PostForm, rules: ModerationRuleSet): ModerationDecision =
    rules.rules.foldLeft[ModerationDecision](ModerationDecision.Pass(form)) {
      case (ModerationDecision.Pass(current), rule) if rule.matches(input.form.bbs, input.userInfo.text, input.contents) =>
        if (rule.isWord && input.settings.checked("BBS_NGWORD_PASS")) ModerationDecision.Pass(current)
        else if (rule.action == ModerationAction.TateDame && input.number != 1) ModerationDecision.Pass(current)
        else if (rule.action == ModerationAction.Mark || input.settings.checked("BBS_NO_MADAKANA")) ModerationDecision.Pass(mark(current, rule.isWord))
        else ModerationDecision.Deny(denial(rule), current)
      case (decision, _) => decision
    }

  private def mark(form: PostForm, isWord: Boolean): PostForm = {
    val name = if (!isWord && !form.name.startsWith(MarkName)) MarkName + form.name else form.name
    form.copy(name = name, message = form.message + MarkMessage)
  }

  private def denial(rule: ModerationRule): ModerationDenial =
    if (rule.isWord) ModerationDenial.NgWord
    else if (rule.action == ModerationAction.TateDame) ModerationDenial.TateDame
    else ModerationDenial.Host

  /** 数値文字参照（1〜0x10FFFF）を復元し、続けて &amp; &lt; &gt; &quot; &apos; を復元する */
  def decodeEntities(text: String): String = {
    val numeric: js.Function2[String, String, String] = (entity, digits) => {
      val code = if (digits.head.toLower == 'x') BigInt(digits.drop(1), 16) else BigInt(digits)
      if (code > 0 && code <= 0x10ffff) new String(Character.toChars(code.toInt)) else entity
    }
    val named: js.Function1[String, String] = entity => Entities(entity)
    text.jsReplace(NumericEntity, numeric).jsReplace(NamedEntity, named)
  }
}
