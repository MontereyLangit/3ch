package io._3ch.bbsclient.islands

import io._3ch.common.domain.SubjectText
import io._3ch.common.domain.account.TokenRevokeReason
import io._3ch.common.text.JstTime
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 専ブラ用キーを発行し、その場だけで表示する */
object AccountMenuIsland {
  private val button = "cursor-pointer border border-[#d6dce3] bg-white px-3 py-1 disabled:opacity-50"
  private def number(raw: js.Object, name: String): Option[Double] = {
    val value = raw.asInstanceOf[js.Dynamic].selectDynamic(name)
    Option.when(js.typeOf(value) == "number")(value.asInstanceOf[Double]).filter(_.isFinite)
  }
  private def objects(raw: js.Object, name: String): List[js.Object] =
    raw.asInstanceOf[js.Dynamic].selectDynamic(name).asInstanceOf[js.UndefOr[js.Array[js.Object]]].toOption.fold(List.empty[js.Object])(_.toList)
  private def time(raw: js.Object, name: String): String = number(raw, name).filter(_ > 0).fold("")(JstTime.dateTime)
  private def revokedReason(raw: js.Object): String = TokenRevokeReason.parse(AuthClient.text(raw, "revokeReason")) match {
    case Some(TokenRevokeReason.Superseded) => "新しいキーへ置き換え"
    case Some(TokenRevokeReason.Revoked) => "手動で無効化"
    case Some(TokenRevokeReason.Expired) => "未使用のまま失効"
    case None => "無効化済み"
  }
  private def postView(raw: js.Object): VdomElement = {
    val bbs = AuthClient.text(raw, "bbs")
    val key = AuthClient.text(raw, "key")
    val resNum = number(raw, "resNum").fold(0)(_.toInt)
    val path = s"/test/read.cgi/${URIUtils.encodeURIComponent(bbs)}/${URIUtils.encodeURIComponent(key)}/$resNum"
    <.div(^.className := "mt-2 text-xs", <.span(^.className := "mr-2 text-[#697785]", time(raw, "t")),
      <.a(^.href := path, ^.className := "text-[#06f] hover:underline", s"$bbs › ${SubjectText.decode(AuthClient.text(raw, "title"))} >>$resNum"),
      <.span(^.className := "ml-2 text-[#697785]", AuthClient.text(raw, "network")),
      <.p(^.className := "mt-1 whitespace-pre-wrap", SubjectText.decode(AuthClient.text(raw, "excerpt"))))
  }
  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      issued <- useState(Option.empty[String])
      message <- useState("")
      busy <- useState(false)
      removed <- useState(Set.empty[String])
      replaced <- useState(Set.empty[String])
    } yield {
      def post(path: String, id: Option[String]): Callback = issued.setState(None) >> busy.setState(true) >> message.setState("") >> Callback {
        val params = new dom.URLSearchParams()
        id.foreach(params.set("id", _))
        dom.fetch(path, new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = params }).toFuture
          .flatMap(r => r.json().toFuture.map(json => (r.ok, json))).map { case (ok, json) =>
            busy.setState(false).runNow()
            if ok then {
              if path.endsWith("issue") then {
                val unusedIds = objects(raw, "tokens").filter(t => number(t, "boundAt").isEmpty && number(t, "revokedAt").isEmpty).map(t => AuthClient.text(t, "id")).toSet
                removed.modState(_ ++ unusedIds).runNow()
                replaced.modState(_ ++ unusedIds).runNow()
                issued.setState(Some(AuthClient.text(json.asInstanceOf[js.Object], "token"))).runNow()
              }
              else { id.foreach(key => removed.modState(_ + key).runNow()); message.setState("キーを無効にしました").runNow() }
            } else message.setState(AuthClient.text(json.asInstanceOf[js.Object], "error", "操作できませんでした")).runNow()
          }.recover { case _ => busy.setState(false).runNow(); message.setState("通信できませんでした").runNow() }
      }
      val tokens = objects(raw, "tokens")
      val now = number(raw, "nowSeconds").getOrElse(0d)
      def dead(token: js.Object): Boolean = number(token, "revokedAt").isDefined || removed.value.contains(AuthClient.text(token, "id"))
      val active = tokens.filter(token => !dead(token) && number(token, "boundAt").isDefined)
      val unused = tokens.filter(token => !dead(token) && number(token, "boundAt").isEmpty)
      val activeCount = number(raw, "activeCount").fold(active.size)(_.toInt) - tokens.count(t => removed.value.contains(AuthClient.text(t, "id")) && number(t, "boundAt").isDefined && number(t, "revokedAt").isEmpty)
      <.section(^.id := "key", ^.className := "my-4 border border-[#d6dce3] bg-white p-4",
        <.h2(^.className := "mb-2 font-bold", "■ 専ブラ用認証キー"),
        <.p(^.className := "mb-3", "キーは最初に使った専用ブラウザだけで使えます。キーは発行したときにしか表示されません。"),
        tokens.flatMap(t => objects(t, "warnings").zipWithIndex.map { case (warning, index) =>
          <.p(^.key := s"${AuthClient.text(t, "id")}:$index", ^.role := "alert", ^.className := "my-2 border border-[#e2c17a] bg-[#fff8e7] p-3",
            s"${time(warning, "t")}に、別の専用ブラウザ（${AuthClient.text(warning, "uaLabel")}）から、${AuthClient.text(t, "label")}のキーが使われそうになりました（拒否済み）。心当たりが無ければキーを無効にしてください。")
        }).toVdomArray,
        <.h3(^.className := "my-2 font-bold", s"キーを使っている専用ブラウザ ${math.max(0, activeCount)} / 5"),
        <.p(^.className := "text-xs text-[#697785]", "身に覚えのない書き込みがあれば、そのブラウザのキーを無効にしてください。"),
        active.toVdomArray { t =>
          val id = AuthClient.text(t, "id")
          val posts = objects(t, "recentPosts").take(5)
          <.div(^.key := id, ^.className := "my-2 border-b border-[#d6dce3] py-3",
            <.div(^.className := "flex flex-wrap items-center justify-between gap-2",
              <.div(<.b(AuthClient.text(t, "label")), <.small(^.className := "ml-2 text-[#697785]", s"${time(t, "boundAt")}から使用",
                if number(t, "replacedTokenAt").isDefined then "（前のキーを置き換え）" else "")),
              <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> post("/auth/token/revoke", Some(id)), "このブラウザのキーを無効にする")),
            posts.headOption.fold[VdomNode](<.p(^.className := "mt-2 text-xs", s"最終利用 ${time(t, "lastUsedAt")}"))(postView),
            if posts.nonEmpty then <.details(^.className := "mt-2", <.summary(^.className := "cursor-pointer text-xs", s"最近の書き込み（${posts.size}件）"),
              posts.zipWithIndex.toVdomArray { case (p, index) => <.div(^.key := index, postView(p)) }) else EmptyVdom)
        },
        unused.toVdomArray { t =>
          val id = AuthClient.text(t, "id")
          val remaining = number(t, "expiresAt").filter(_ > now).filter(_ => now > 0).map(at => s"（あと${math.ceil((at - now) / 3600).toInt}時間）").getOrElse("")
          <.div(^.key := id, ^.className := "my-3 border-t border-[#d6dce3] pt-3 text-xs", <.b("未使用のキー"), " ", AuthClient.text(t, "label"),
            s" 発行 ${time(t, "issuedAt")}・${time(t, "expiresAt")}まで有効$remaining ",
            <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> post("/auth/token/revoke", Some(id)), "取り消す"))
        },
        <.details(^.className := "my-3 text-xs", <.summary(^.className := "cursor-pointer", "キーを無効にした専用ブラウザ（30日間表示）"),
          tokens.filter(dead).toVdomArray { t => <.div(^.key := AuthClient.text(t, "id"), ^.className := "my-2 border-t border-[#d6dce3] pt-2",
            <.b(AuthClient.text(t, "label")), " ", time(t, "revokedAt"), " ",
            if replaced.value.contains(AuthClient.text(t, "id")) then "新しい未使用キーへ置き換え" else if removed.value.contains(AuthClient.text(t, "id")) then "手動で無効化" else revokedReason(t),
            objects(t, "recentPosts").headOption.fold[VdomNode](EmptyVdom)(postView)) }),
        <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> post("/auth/token/issue", None), "新しいキーを発行"),
        <.p(^.className := "my-2 text-xs text-[#697785]", "未使用のキーは1個だけ持てます。発行すると、いまの未使用キーは無効になります。"),
        issued.value.toVdomArray { key => <.div(^.key := "issued", ^.className := "mt-3 border border-[#f66] bg-[#fff7f3] p-3",
          <.b("発行したキー（今だけ表示されます）"), <.pre(^.className := "my-2 select-all whitespace-pre-wrap break-all", key),
          <.button(^.`type` := "button", ^.className := button, ^.onClick --> Callback {
            dom.window.navigator.clipboard.writeText(key).toFuture.map(_ => message.setState("コピーしました").runNow()).recover { case _ => message.setState("キーを選択してコピーしてください").runNow() }
          }, "コピー"),
          <.p(^.className := "mt-2 text-xs", "専用ブラウザのメール欄に入れて書き込んでください。24時間使われないと失効します。")) },
        <.p(^.role := "status", ^.className := "my-2", message.value))
    }
  }
}
