package io._3ch.admin.pages

import io._3ch.admin.Nav
import io._3ch.common.admin.RuleName
import japgolly.scalajs.react.vdom.html_<^.*

/** 規制ルール（deny.cgi・ngwords.cgi）。書式は docs/admin.md の「規制ルール」 */
object RulesPage {
  private def help(name: RuleName): VdomNode = <.div(
    <.p(<.b("書き方"), ": 1行に「板の正規表現<>投稿者情報の正規表現<>NGワードの正規表現<>動作」。空欄は全対象。空行と#で始まる行は無視します。"),
    <.p("動作: deny（拒否）、mark（注意表示）、TateDame（スレ立てのみ禁止）、md5（詳細非公開で拒否）。空欄は拒否。"),
    <.p("5列目に回避レベル（admin・staff・privileged・l10・login）を書くと、その投稿者にはこの行を適用しません。省略すると privileged（権限者とキャップ）です。"),
    <.p("対象の例: IP:192\\.0\\.2\\.1(?: |$)、AS1234-。NGワードは名前・メール・本文・タイトルに適用します。"),
    <.p("例: ", <.code(if (name == RuleName.Access) "^sandbox$<>IP:192\\.0\\.2\\.1(?: |$)<><>deny" else "^sandbox$<><>禁止語|宣伝サイト<>deny")))

  def apply(nav: Nav, name: RuleName): VdomElement = {
    val title = if (name == RuleName.Access) "規制ルール（アクセス）" else "規制ルール（NGワード）"
    TextFilePage.Component(TextFilePage.Props(nav, title, name.file, s"/rules/${name.value}/", "ルール", help(name)))
  }
}
