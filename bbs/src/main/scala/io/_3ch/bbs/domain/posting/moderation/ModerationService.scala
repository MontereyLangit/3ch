package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ModerationDenial
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.{PostForm, UserInfo}

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

/** 規制の判定に使う投稿。form は規制前のフォーム、poster は各行の回避レベルと照らす投稿者、number はレス番号（スレ立ては 1） */
final case class ModerationInput(form: PostForm, userInfo: UserInfo, poster: BypassPoster, country: String, number: Double, settings: BbsSettings) {
  /** NG ワードを照合する文字列と、その文字参照を復元したもの */
  val contents: Seq[String] = {
    val content = s"FROM=${form.name} mail=${form.mail} MESSAGE=${form.message} subject=${form.subject}"
    Seq(content, ModerationService.decodeEntities(content))
  }
}

/** 投稿者の免除条件と規制ルールを評価する */
object ModerationService {
  private val MarkName = "</b>[´・ω・｀]<b> "
  private val MarkMessage = " <small>👀 Madakana: Caution</small>"
  private val NumericEntity = new js.RegExp("&#(x[0-9a-f]+|\\d+);", "gi")
  private val NamedEntity = new js.RegExp("&(?:amp|lt|gt|quot|apos);", "g")
  private val Entities = Map("&amp;" -> "&", "&lt;" -> "<", "&gt;" -> ">", "&quot;" -> "\"", "&apos;" -> "'")

  /** saku 板は対象外。キャップ以外の Tor は役割によらず拒否し、そのあとどの段階でも免除される admin を通す。ルールを読む必要があれば None */
  def beforeRules(input: ModerationInput): Option[ModerationDecision] =
    if (input.form.bbs == "saku") Some(ModerationDecision.Pass(input.form))
    else if (input.country == "T1" && !input.poster.cap) Some(ModerationDecision.Deny(ModerationDenial.Tor, input.form))
    else if (BypassLevel.Admin.exempts(input.poster)) Some(ModerationDecision.Pass(input.form))
    else None

  /** ルールが読めなくても通す投稿者か。行ごとの段階が分からないので既定の段階で判定する */
  def exemptWithoutRules(input: ModerationInput): Boolean = BypassLevel.Default.exempts(input.poster)

  /** ファイルが無ければ通し、壊れていれば既定の段階で免除されない投稿者を拒否し、読み取れたルールを評価する */
  def evaluate(input: ModerationInput, form: PostForm, source: ModerationRuleSource): ModerationDecision = source match {
    case ModerationRuleSource.Missing => ModerationDecision.Pass(form)
    case ModerationRuleSource.Broken if exemptWithoutRules(input) => ModerationDecision.Pass(form)
    case ModerationRuleSource.Broken => ModerationDecision.Deny(ModerationDenial.BrokenRules, form)
    case ModerationRuleSource.Loaded(rules) => evaluate(input, form, rules)
  }

  /**
    * 1 つのルール一覧を評価する。照合は規制前の内容で行い、mark は form に重ねる。BBS_NO_MADAKANA なら拒否も mark にする。
    * 回避レベルで免除される行は飛ばす
    */
  def evaluate(input: ModerationInput, form: PostForm, rules: ModerationRuleSet): ModerationDecision =
    rules.rules.foldLeft[ModerationDecision](ModerationDecision.Pass(form)) {
      case (ModerationDecision.Pass(current), rule) if !rule.bypass.exempts(input.poster) && rule.matches(input.form.bbs, input.userInfo.text, input.contents) =>
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
