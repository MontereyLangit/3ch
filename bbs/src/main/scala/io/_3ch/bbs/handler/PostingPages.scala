package io._3ch.bbs.handler

import io._3ch.common.domain.posting.PostForm
import japgolly.scalajs.react.facade.React
import scala.scalajs.js

/** Monazilla posting pages rendered with React, preserving formatted message fragments. */
object PostingPages {
  private def str(value: js.Any): String = js.Dynamic.global.String(value).asInstanceOf[String]
  private def element(tag: String, attrs: js.Dictionary[String] = js.Dictionary.empty[String])(children: React.Node*): React.Element = {
    React.createElement(tag, attrs.asInstanceOf[js.Object], children*)
  }

  // Only preformatted message fragments and literal protocol comments bypass escaping.
  private def raw(tag: String, content: String): React.Element = {
    React.createElement(tag, js.Dynamic.literal(dangerouslySetInnerHTML = js.Dynamic.literal(__html = content)))
  }

  def respondEndHtml(form: PostForm, charset: String): React.Element = {
    element("html", js.Dictionary("lang" -> "ja"))(
      element("head")(
        element("title")("書きこみました。"),
        RendererShell.clientScript(),
        element("meta", js.Dictionary("httpEquiv" -> "Content-Type", "content" -> ("text/html; charset=" + charset)))(),
        element("meta", js.Dictionary("content" -> ("5;URL=/test/read.cgi/" + form.bbs + "/" + form.key + "/"), "httpEquiv" -> "refresh"))()
      ),
      element("body")(
        "書きこみが終わりました。",
        element("br")(),
        element("br")(),
        "\n画面を切り替えるまでしばらくお待ち下さい。",
        element("br")(),
        element("br")(),
        element("br")(),
        element("br")(),
        element("br")(),
        element("br")(),
        element("br")(),
        element("center")()
      )
    )
  }

  def respondConfirmationPhase(gb: js.Dynamic, charset: String): React.Element = {
    element("html")(
      element("head")(
        element("title")("■ 書き込み確認 ■"),
        RendererShell.clientScript(),
        element("meta", js.Dictionary("httpEquiv" -> "Content-Type", "content" -> ("text/html; charset=" + charset)))(),
        element("meta", js.Dictionary("name" -> "viewport", "content" -> "width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes"))()
      ),
      element("body", js.Dictionary("bgcolor" -> "#EEEEEE"))(
        raw("span", "<!-- 2ch_X:cookie -->"),
        element("font", js.Dictionary("size" -> "+1", "color" -> "#FF0000"))(
          element("b")("書きこみ＆クッキー確認")
        ),
        element("ul")(
          element("br")(),
          element("br")(),
          raw("b", str(gb.form.subject) + " "),
          element("br")(),
          "\n名前： ",
          raw("b", str(gb.form.FROM)),
          element("br")(),
          raw("span", "\nE-mail： " + str(gb.form.mail)),
          element("br")(),
          "\n内容：",
          element("br")(),
          raw("span", str(gb.form.MESSAGE)),
          element("br")(),
          element("br")()
        ),
        element("b")(
          "\n投稿確認",
          element("br")(),
          "\n\n・投稿者は、投稿に関して発生する責任が全て投稿者に帰すことを承諾します。",
          element("br")(),
          "\n・投稿者は、話題と無関係な広告の投稿に関して、相応の費用を支払うことを承諾します",
          element("br")(),
          "\n・",
          element("font", js.Dictionary("color" -> "red"))("＊"),
          "投稿者は、投稿された内容及びこれに含まれる知的財産権、（著作権法第21条ないし第28条に規定される権利も含む）\nその他の権利につき（第三者に対して再許諾する権利を含みます。）、掲示板運営者に対し、無償で譲渡することを\n承諾します。ただし、投稿が別に定める削除ガイドラインに該当する場合、投稿に関する知的財産権その他の権利、\n義務は一定期間投稿者に留保されます。",
          element("br")(),
          "\n・掲示板運営者は、投稿者に対して日本国内外において無償で非独占的に複製、公衆送信、\n頒布及び翻訳する権利を投稿者に許諾します。また、投稿者は掲示板運営者が指定する第三者に対して、一切の権利（第三者に対して再許諾する権利を含みます）を許諾しないことを承諾します。",
          element("br")(),
          "\n・投稿者は、掲示板運営者あるいはその指定する者に対して、著作者人格権を一切行使しないことを承諾します。",
          element("br")(),
          element("font", js.Dictionary("color" -> "red"))(
            "\n・投稿者および掲示板運営者は、上記の承諾事項に加えて、各掲示板（板）のローカルルール（head.txt）に掲載された規定を、あわせて承諾します。",
            element("br")(),
            "\n・投稿者および掲示板運営者は、上記のうち＊のついた承諾事項と、各掲示板のローカルルールにおける規定が相反する場合、各掲示板の規定が優先されることを承諾します。",
            element("br")(),
            "\n（例：ローカルルールに投稿者が著作権等を保持する規定のある掲示板では、＊の規定は適用されません）\n"
          ),
          element("br")()
        ),
        "\n変更する場合はキャンセルして書き直して下さい。",
        element("br")(),
        element("br")(),
        "\n現在、荒らし対策でクッキーを設定していないと書きこみできないようにしています。",
        element("br")(),
        element("font", js.Dictionary("size" -> "-1"))("(cookieを設定するとこの画面はでなくなります。)"),
        element("br")()
      )
    )
  }

  def respondError(form: PostForm, host: String, message: String, charset: String): React.Element = {
    element("html")(
      element("head")(
        element("title")("ＥＲＲＯＲ！"),
        RendererShell.clientScript(),
        element("meta", js.Dictionary("httpEquiv" -> "Content-Type", "content" -> ("text/html; charset=" + charset)))()
      ),
      element("body", js.Dictionary("bgcolor" -> "#FFFFFF"))(
        raw("span", "<!-- 2ch_X:error -->"),
        element("font", js.Dictionary("size" -> "+1", "color" -> "#FF0000"))(
          raw("b", "ERROR: " + message)
        ),
        element("ul")(
          element("br")(),
          "ホスト",
          raw("b", host),
          element("br")(),
          raw("b", form.subject + " "),
          element("br")(),
          "\n名前： ",
          raw("b", form.name),
          element("br")(),
          raw("span", "E-mail： " + form.mail),
          element("br")(),
          "\n内容：",
          element("br")(),
          raw("span", form.message),
          element("br")(),
          element("br")()
        ),
        element("a", js.Dictionary("href" -> "https://wiki.3chbbs.com/index.php/%E3%82%A2%E3%82%AF%E3%82%BB%E3%82%B9%E8%A6%8F%E5%88%B6%E4%B8%AD%E3%81%A7%E3%82%82%E6%9B%B8%E3%81%91%E3%82%8B%E6%9D%BF"))("★ アクセス規制中でも書ける板 ★"),
        element("br")(),
        element("br")(),
        element("hr")(),
        "\nこちらでリロードしてください。",
        element("a", js.Dictionary("href" -> ("/test/read.cgi/" + form.bbs + "/" + form.key + "/")))(" GO! "),
        element("br")(),
        "\n自分で解決してみよう! ",
        element("a", js.Dictionary("href" -> "https://wiki.3chbbs.com/index.php/%E6%9B%B8%E3%81%8D%E8%BE%BC%E3%82%81%E3%81%AA%E3%81%84%E6%99%82%E3%81%AE%E6%97%A9%E8%A6%8B%E8%A1%A8"))("書き込めない時の早見表"),
        element("br")(),
        "\n分からないことがあったら",
        element("a", js.Dictionary("href" -> "https://www.3ch.io/html/guide/"))("３ちゃんねるガイド"),
        "へ。。。",
        element("br")(),
        element("br")(),
        element("p")()
      )
    )
  }

}
