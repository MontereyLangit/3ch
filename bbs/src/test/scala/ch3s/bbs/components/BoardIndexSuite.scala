package ch3s.bbs.components

import ch3s.bbs.BuildInfo
import ch3s.common.domain.{BbsSubjects, ThreadCommand}
import japgolly.scalajs.react.facade.React

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-dom/server", "renderToStaticMarkup")
private object BoardMarkup extends js.Function1[React.Element, String] {
  def apply(element: React.Element): String = js.native
}

class BoardIndexSuite extends munit.FunSuite {
  private val subjects = BbsSubjects.create("1800000001.dat<>実スレ &amp; &lt;script&gt; (3)\n1800000002.dat<>次の話題 (7)\n9240000001.dat<>共通のお知らせ (12)\n")
  private val props = BoardIndexProps("テスト板", "<b>ローカルルール</b>", "sample", subjects = subjects, time = "1800000100")

  /** island の `data-serialized-props`（JSON）は見える描画ではないので除く。 */
  private def visible(html: String): String = html.replaceAll("data-serialized-props=\"[^\"]*\"", "")

  private def renderForm(props: PostingFormProps): String = visible(BoardMarkup(PostingFormComponent.Component(props).rawElement))

  private def render(props: BoardIndexProps): String = visible(BoardMarkup(BoardIndexComponent.Component(props).rawElement))

  test("feed と新着レス一覧は準備中表示、subback は実際の subject を描画する") {
    val feed = render(props.copy(`type` = "feed"))
    assert(feed.contains("Coming Soon…"))
    assert(!feed.contains("data-feed-source"))
    assert(!feed.contains("ダミーデータ"))
    assert(!feed.contains("実スレ"))
    val subback = render(props.copy(`type` = "subback"))
    assert(subback.contains("Coming Soon…"))
    assert(!subback.contains("ダミーデータ"))
    assert(subback.contains("実スレ &amp; &lt;script&gt;"))
    assert(!subback.contains("実スレ &amp;amp;"))
    assert(subback.contains("href=\"/test/read.cgi/sample/1800000001/\""))
    assert(subback.contains("href=\"/test/read.cgi/924/9240000001/\""))
  }

  test("検索とレス数順を適用し、表示切り替えでも条件を維持する") {
    val sorted = render(props.copy(`type` = "subback", sort = "res"))
    assert(sorted.indexOf("次の話題") < sorted.indexOf("実スレ"))
    val filtered = render(props.copy(`type` = "subback", query = "実スレ &", sort = "res"))
    assert(filtered.contains("実スレ &amp; &lt;script&gt;"))
    assert(!filtered.contains("次の話題"))
    assert(filtered.contains("?type=feed&amp;q=%E5%AE%9F%E3%82%B9%E3%83%AC%20%26&amp;sort=res"))
  }

  test("共通レイアウトと指定フッターを表示する") {
    val html = render(props)
    assert("<header[^>]*class=\"site-header[ \"]".r.findFirstIn(html).isDefined)
    assert("<aside[^>]*class=\"side-panel[ \"]".r.findFirstIn(html).isDefined)
    assert(html.contains("<b>ローカルルール</b>"))
    assert(html.contains("https://github.com/MontereyLangit/ch3s"))
    assert(html.contains(s" ver ${BuildInfo.version} ${BuildInfo.updatedDate} "))
    assert(html.contains("text-decoration:none;color:rgb(254, 100, 46);font-weight:700"))
    assert(html.contains("†Monterey Langit ★"))
    assert(html.contains("aria-controls=\"board-new-thread\""))
  }

  test("投稿フィールドを form 内にまとめ、スレ立て用 submit 値を保持する") {
    val html = renderForm(PostingFormProps("sample", "1800000100"))
    assert(html.startsWith("<honox-island"))
    assert(html.contains("<form"))
    assert(html.endsWith("</form></honox-island>"))
    for (name <- List("FROM", "mail", "subject", "MESSAGE", "bbs", "time", "submit")) {
      assert(html.contains(s"name=\"$name\""))
    }
    assert(html.contains("value=\"書き込む\""))
    assert(html.contains("action=\"/test/bbs.cgi\""))
    assert(!html.contains("disabled=\"\""))
    assert(!html.contains("name=\"key\""))
  }

  test("一覧を #thread-list で包み、リロードボタンを出す") {
    val html = render(props)
    assert(html.contains("id=\"thread-list\""))
    assert(html.contains("data-board-reload"))
    assert(html.contains("data-board-reload-status"))
  }

  test("フォームに下書きキーを付け、imgur 欄は Client-ID があるときだけ name なしで出す") {
    val none = render(props)
    assert(none.contains("data-draft-key=\"sample/new\""))
    assert(!none.contains("data-imgur-upload"))
    assert(!none.contains("data-imgur-client-id"))
    val html = render(props.copy(imgurClientId = "cid123"))
    assert(html.contains("data-imgur-client-id=\"cid123\""))
    assert("<input[^>]*data-imgur-upload[^>]*>".r.findFirstIn(html).exists(tag => !tag.contains("name=") && tag.contains("type=\"file\"")))
    assert(html.contains("data-imgur-status"))
    val thread = renderForm(PostingFormProps("sample", "1", Option("1800000001"), "cid123"))
    assert(thread.contains("data-draft-key=\"sample/1800000001\""))
  }

  test("コマンド補助は候補があるスレ立てフォームだけに出す") {
    assert(!render(props).contains("data-command-helper"))
    val command = ThreadCommand("!force:ID", "ID強制", "MESSAGE", "IDを表示する")
    val html = renderForm(PostingFormProps("sample", "1", commands = Seq(command)))
    assert(html.contains("data-command-helper"))
    assert(html.contains("data-command=\"!force:ID\""))
    assert(html.contains("data-command-field=\"MESSAGE\""))
    assert(html.contains("aria-pressed=\"false\""))
    assert(html.contains("title=\"IDを表示する\""))
    val reply = renderForm(PostingFormProps("sample", "1", Option("1"), commands = Seq(command)))
    assert(!reply.contains("data-command-helper"))
  }

  test("板名は URL のパス要素とクエリ値として符号化する") {
    for (bbs <- List("/evil.example", "news_4-test", "a&b/#c")) {
      val html = render(props.copy(bbs = bbs, `type` = "subback"))
      val encoded = js.URIUtils.encodeURIComponent(bbs)
      assert(html.contains(s"href=\"/$encoded/SETTING.TXT\""))
      assert(html.contains(s"href=\"/$encoded/kako/\""))
      assert(html.contains(s"href=\"/test/read.cgi/$encoded/1800000001/\""))
      assert(html.contains(s"href=\"/admin/board?bbs=$encoded\""))
      assert(!html.contains("href=\"//evil.example"))
      assert(!html.contains("bbs=a&amp;b/"))
    }
  }

  test("勢い順でも全板共通スレの順序はレス数に影響されない") {
    val data = BbsSubjects.create("9000000001.dat<>共通少レス (1)\n1800000001.dat<>通常少レス (3)\n9240000001.dat<>共通多レス (999)\n1800000002.dat<>通常多レス (30)\n")
    val html = render(props.copy(`type` = "subback", subjects = data, sort = "speed"))
    assert(html.indexOf("共通少レス") < html.indexOf("共通多レス"))
    assert(html.indexOf("通常多レス") < html.indexOf("通常少レス"))
  }
}
