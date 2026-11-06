package io._3ch.bbs.domain.posting

import io._3ch.common.domain.identity.UserAgentProfile

import scala.scalajs.js
import scala.util.Try
import scala.util.matching.Regex

/** UA 解析表の一行。pattern に一致したら label の $1〜$9 を捕捉した文字列に置き換えて返す */
final class UserAgentRule private (pattern: js.RegExp, label: String) {
  def apply(userAgent: String): Option[String] = Option(pattern.exec(userAgent)).map { found =>
    UserAgentRule.Group.replaceAllIn(label, group =>
      Regex.quoteReplacement(found.lift(group.group(1).toInt).flatMap(_.toOption).flatMap(Option(_)).getOrElse("")))
  }
}

object UserAgentRule {
  private val Group: Regex = "\\$([1-9])".r

  /** 正規表現として読めない行と、g・y など照合の状態を持つフラグの行は None */
  def create(pattern: String, flags: String, label: String): Option[UserAgentRule] =
    if (!flags.forall("ius".contains(_)) || flags.distinct.length != flags.length) None
    else Try(new js.RegExp(pattern, flags)).toOption.map(new UserAgentRule(_, label))
}

/** OS・ブラウザ・端末種別ごとの解析表。各表は上から順に見て最初に一致した行を使う */
final case class UserAgentTable(os: Seq[UserAgentRule], browser: Seq[UserAgentRule], device: Seq[UserAgentRule]) {
  def profile(userAgent: String): UserAgentProfile = {
    def first(rules: Seq[UserAgentRule]): String = rules.iterator.flatMap(_(userAgent)).nextOption().getOrElse("")
    UserAgentProfile(first(os), first(browser), first(device))
  }
}

object UserAgentTable {
  /** 表が無いときの解析表。どの UA も解析できない */
  val empty: UserAgentTable = UserAgentTable(Nil, Nil, Nil)
}
