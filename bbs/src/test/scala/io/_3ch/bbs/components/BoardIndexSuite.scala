package io._3ch.bbs.components

import io._3ch.bbs.BuildInfo
import io._3ch.common.domain.{BbsSubjects, BoardId, ThreadCommand, ThreadKey}
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
  private val sample = BoardId.create("sample").toOption.get

  private def threadKey(value: Long) = Option(ThreadKey.create(value).toOption.get)

  private val props = BoardIndexProps("テスト板", "<b>ローカルルール</b>", sample, subjects = subjects, time = "1800000100")

  /** island の data-serialized-propsは見える描画ではないので除く */
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
    assert(html.contains("https://github.com/MontereyLangit/3ch"))
    assert(html.contains(s" ver ${BuildInfo.version} ${BuildInfo.updatedDate} "))
    assert(html.contains("text-decoration:none;color:rgb(254, 100, 46);font-weight:700"))
    assert(html.contains("†Monterey Langit ★"))
    assert(html.contains("aria-controls=\"board-new-thread\""))
  }

  test("投稿フィールドを form 内にまとめ、スレ立て用 submit 値を保持する") {
    val html = renderForm(PostingFormProps(sample, "1800000100"))
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
    val thread = renderForm(PostingFormProps(sample, "1", threadKey(1800000001L), "cid123"))
    assert(thread.contains("data-draft-key=\"sample/1800000001\""))
  }

  test("コマンド補助は候補があるスレ立てフォームだけに出す") {
    assert(!render(props).contains("data-command-helper"))
    val command = ThreadCommand("!force:ID", "ID強制", "MESSAGE", "IDを表示する")
    val html = renderForm(PostingFormProps(sample, "1", commands = Seq(command)))
    assert(html.contains("data-command-helper"))
    assert(html.contains("data-command=\"!force:ID\""))
    assert(html.contains("data-command-field=\"MESSAGE\""))
    assert(html.contains("aria-pressed=\"false\""))
    assert(html.contains("title=\"IDを表示する\""))
    val reply = renderForm(PostingFormProps(sample, "1", threadKey(1800000001L), commands = Seq(command)))
    assert(!reply.contains("data-command-helper"))
  }

  test("板名（BoardId は英数字のみ）は URL のパス要素とクエリ値にそのまま出る") {
    for (bbs <- List("news4test", "A1b2C3d4E5f6G7h8")) {
      val html = render(props.copy(bbs = BoardId.create(bbs).toOption.get, `type` = "subback"))
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
  test("認証有効時は未ログインの導線と検証済みの表示情報を出し分ける") {
    val guest = render(props.copy(authEnabled = true, returnPath = "/sample/"))
    assert(guest.contains("/auth/?return=%2Fsample%2F"))
    assert(guest.contains("書き込みには認証・登録が必要です"))
    assert(!guest.contains("name=\"showid\""))
    val member = render(props.copy(authEnabled = true, session = Some(io._3ch.common.domain.account.SessionView("test_user", 3)), settings = Map("BBS_PHOEBE" -> "5")))
    assert(member.contains("@test_user"))
    assert(member.contains("Lv3"))
    assert(member.contains("このスレはLv5以上"))
    assert(member.contains("name=\"showid\""))
    assert(!member.contains("書き込みには認証・登録が必要です"))
    val mature = render(props.copy(authEnabled = true, session = Some(io._3ch.common.domain.account.SessionView("adult", 10))))
    assert(mature.contains("Lv*"))
    assert(!mature.contains("Lv10"))
  }

  test("認証ページのSSRには秘密や発行済みのキーを含めない") {
    val html = BoardMarkup(AuthComponent.Component(AuthProps("public-site-key", "/sample/", embed = true, agreementVersion = "v2")).rawElement)
    assert(html.contains("public-site-key"))
    assert(html.contains("利用規約を読み"))
    assert(!html.contains("<header"))
    assert(!html.contains("<footer"))
    assert(!html.contains("challenges.cloudflare.com/turnstile/v0/api.js"))
    val account = BoardMarkup(AccountComponent.Component(AccountProps("member", 1, Seq(AccountTokenProps("opaque-id", "Jane")))).rawElement)
    assert(account.contains("Jane"))
    assert(!account.contains("!#"))
    assert(!account.contains("発行したキー（今だけ表示されます）"))
  }
  test("認証ページは ID・パスワードを選べ、Google はクライアント未設定なら準備中にする") {
    val off = BoardMarkup(AuthComponent.Component(AuthProps("public-site-key", "/sample/", agreementVersion = "v2")).rawElement)
    assert(off.contains("ID・パスワード"))
    assert(off.contains("Googleで続ける（準備中）"))
    assert(off.contains("新しく登録する"))
    val on = BoardMarkup(AuthComponent.Component(AuthProps("public-site-key", "/sample/", agreementVersion = "v2", googleEnabled = true,
      notice = "Googleでのログインを中止しました。")).rawElement)
    assert(!on.contains("準備中"))
    assert(on.contains("Googleでのログインを中止しました。"))
    val login = BoardMarkup(AuthComponent.Component(AuthProps("public-site-key", "/sample/", login = true)).rawElement)
    assert(login.contains("autoComplete=\"current-password\"") || login.contains("autocomplete=\"current-password\""))
  }
  test("アカウント画面は専ブラの使用枠・履歴・不一致・期限・失効理由を公開情報から描く") {
    val recent = AccountPostProps(1800000000, "sample", "1800000001", "スレ &lt;script&gt;", 12, "本文 &lt;img&gt;", "回線・東京都")
    val tokens = Seq(
      AccountTokenProps("active", "JaneStyle・Windows", boundAt = Some(1800000000), replacedTokenAt = Some(1800000000),
        recentPosts = Seq(recent), warnings = Seq(AccountTokenWarningProps(1800000010, "別のブラウザ"))),
      AccountTokenProps("unused", "未使用", issuedAt = 1800000000, expiresAt = 1800086400),
      AccountTokenProps("old", "古い専ブラ", boundAt = Some(1799990000), revokedAt = Some(1800000000), revokeReason = Some("superseded"), recentPosts = Seq(recent)))
    val html = visible(BoardMarkup(AccountComponent.Component(AccountProps("member", 3, tokens, activeCount = 1, nowSeconds = 1800000100)).rawElement))
    assert(html.contains("専用ブラウザ 1 / 5"))
    assert(html.contains("前のキーを置き換え"))
    assert(html.contains("別の専用ブラウザ（別のブラウザ）"))
    assert(html.contains("最近の書き込み（1件）"))
    assert(html.contains("/test/read.cgi/sample/1800000001/12"))
    assert(html.contains("スレ &lt;script&gt;"))
    assert(html.contains("本文 &lt;img&gt;"))
    assert(html.contains("あと24時間"))
    assert(html.contains("新しいキーへ置き換え"))
    assert(!html.contains("<script>"))
  }

  test("アカウント画面は登録方法・有効期限（日本時間）・この端末とログアウトの注意を出す") {
    val html = visible(BoardMarkup(AccountComponent.Component(AccountProps("member", 1, kind = io._3ch.common.domain.account.AccountKind.Client,
      expiresAt = Some(1830265200), device = Some(io._3ch.common.domain.device.DeviceGranularity.Personal), deviceCount = 2)).rawElement))
    assert(html.contains("かんたん登録（クライアントID）"))
    assert(html.contains("2028/01/01まで有効"))
    assert(html.contains("準個体（p）"))
    assert(html.contains("登録した端末2台"))
    assert(html.contains("ログアウトするとこの端末から戻せません"))
    val password = visible(BoardMarkup(AccountComponent.Component(AccountProps("member", 3, kind = io._3ch.common.domain.account.AccountKind.Password)).rawElement))
    assert(password.contains("ID・パスワード"))
    assert(!password.contains("まで有効"))
    assert(!password.contains("戻せません"))
    assert(password.contains("端末情報なし"))
  }
}
