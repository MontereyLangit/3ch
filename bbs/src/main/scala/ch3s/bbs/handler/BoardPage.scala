package ch3s.bbs.handler

import japgolly.scalajs.react.facade.React
import scala.scalajs.js

/** 
 * 専ブラ向け板トップ。
 * TODO: この画面のみMonazilla UAの場合のみSJIS, それ以外ではUTF-8で描画するようにする
 * TODO: こういうReact Componentは/componentに分離＆japgolly.scalajs.react.vdom.html_<^.*にしたい
 */
object BoardPage {
  private def element(name: String, props: js.Dictionary[js.Any] = js.Dictionary.empty[js.Any])(children: js.Any*): React.Element = {
    React.createElement(name, props.asInstanceOf[js.Object], children.map(_.asInstanceOf[React.Node])*)
  }

  private def raw(name: String, content: String, kind: String): React.Element = {
    element(name, js.Dictionary[js.Any](
      "type" -> kind,
      "dangerouslySetInnerHTML" -> js.Dictionary("__html" -> content)
    ))()
  }

  private val script = "<!--\nfunction l(e){\n var N=getCookie(\"NAME\"),M=getCookie(\"MAIL\"),i;\n with(document) for(i=0;i<forms.length;i++)if(forms[i].FROM&&forms[i].mail)with(forms[i]){\n  FROM.value=N;\n  mail.value=M;\n }\n}\nonload=l;\nfunction getCookie(key, tmp1, tmp2, xx1, xx2, xx3) {\n tmp1 = \" \" + document.cookie + \";\";\n while(tmp1.match(/+/)) {tmp1 = tmp1.replace(\"+\", \" \");};\n xx1 = xx2 = 0;\n len = tmp1.length;\n while (xx1 < len) {\n  xx2 = tmp1.indexOf(\";\", xx1);\n  tmp2 = tmp1.substring(xx1 + 1, xx2);\n  xx3 = tmp2.indexOf(\"=\");\n  if (tmp2.substring(0, xx3) == key) {return(unescape(tmp2.substring(xx3 + 1, xx2 - xx1 - 1)));}\n  xx1 = xx2 + 1;\n }\n return(\"\");\n}\n//-->"
  private val css = "<!--\nimg {border:0;}\n.post{margin-top:10px}\n.number{display:inline-block}\n.name{display:inline-block;margin-left:12px}\ndiv.name{color:green;}\n.email{display:inline-block;margin-left:5px}\n.date{display:inline-block;margin-left:5px}\n.message{margin-left:40px;clear:both}\na{ word-break: break-all; }\nbody { word-break: break-word; overflow-wrap: break-word; overscroll-behavior-y: contain; }\n.AA {font-family:\"ヒラギノ角ゴ Pro W3\",\"Hiragino Kaku Gothic Pro\",Osaka,\"ＭＳ Ｐゴシック\",\"MS PGothic\",sans-serif; font-size: 16px;}\n.rep-comment { background-color: lavender; border: 1px outset lavender; color: black; opacity: 0.95; position: absolute; text-decoration: none; left: 7em; padding-top: 5px; padding-left: 5px; padding-right: 5px; }\ndisplay:inline-block;\n-->"

  def render(bbsTitle: String, bbs: String): React.Element = {
    def link(href: String, text: String): React.Element = element("a", js.Dictionary[js.Any]("href" -> href))(text)
    def meta(name: String, content: String): React.Element = element("meta", js.Dictionary[js.Any]("httpEquiv" -> name, "content" -> content))()
    val head = element("head")(
      meta("Content-Type", "text/html; charset=Shift_JIS"),
      meta("Pragma", "no-cache"),
      meta("Cache-Control", "no-cache"),
      element("script", js.Dictionary[js.Any]("type" -> "text/javascript", "src" -> "https://code.jquery.com/jquery-2.2.4.min.js"))(),
      raw("script", script, "text/javascript"),
      raw("style", css, "text/css"),
      element("title")(bbsTitle),
      RendererShell.clientScript()
    )
    val settingsLink = element("a", js.Dictionary[js.Any](
      "href" -> "../setting.cgi?bbs=livegalileo",
      "style" -> js.Dictionary("backgroundColor" -> "dimgray", "border" -> "1px outset dimgray", "color" -> "palegreen", "textDecoration" -> "none")
    ))("閲覧設定")
    val titleRow = element("tr")(
      element("td", js.Dictionary[js.Any]("nowrap" -> "", "colSpan" -> 2))(
        element("font", js.Dictionary[js.Any]("size" -> "+1"))(element("b")(bbsTitle)), element("br")()
      ),
      element("td", js.Dictionary[js.Any]("nowrap" -> "", "width" -> "5%", "align" -> "right", "vAlign" -> "top"))(
        settingsLink, "\u00a0 ", link("../test/history.cgi?bbs=livegalileo", "履歴"), "\u00a0 ", link("#menu", "■"), " ", link("#1", "▼")
      )
    )
    val table = element("table", js.Dictionary[js.Any]("border" -> "1", "cellSpacing" -> "7", "cellPadding" -> "3", "width" -> "95%", "bgcolor" -> "#CCFFCC", "align" -> "center"))(
      element("tbody")(element("tr")(element("td", js.Dictionary[js.Any]("align" -> "center"))(
        element("table", js.Dictionary[js.Any]("border" -> "0", "cellPadding" -> "1", "width" -> "100%"))(
          element("tbody")(titleRow, element("tr")(element("td", js.Dictionary[js.Any]("colSpan" -> 3))()))
        )
      )))
    )
    val body = element("body", js.Dictionary[js.Any](
      "bgcolor" -> "#FFFFFF", "text" -> "#000000", "link" -> "#0000FF", "alink" -> "#FF0000", "vlink" -> "#660099", "background" -> "../ba.gif"
    ))(
      element("center")(link("//3ch.shop", "")),
      table,
      element("center")(element("br")(), element("b")("どのような形の削除依頼であれ公開させていただきます。")),
      element("br")(),
      element("p", js.Dictionary[js.Any]("style" -> js.Dictionary("margin" -> "0", "padding" -> "0.25em 0", "textAlign" -> "center", "color" -> "#333")))(
        link("//3ch.shop/", "３ちゃんねる"), " BBS.CGI ver 1.07.428 - 2022/04/27 22:56 JST", element("br")(), link("./SETTING.TXT", "SETTING.TXT")
      ),
      element("div", js.Dictionary[js.Any]("align" -> "right", "id" -> "kanri"))(
        element("a", js.Dictionary[js.Any]("href" -> s"/admin/board?bbs=$bbs"))(
          element("input", js.Dictionary[js.Any]("type" -> "submit", "value" -> "管理画面へ"))()
        )
      )
    )
    element("html")(head, body)
  }
}
