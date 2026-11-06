package io._3ch.bbsclient.islands

import io._3ch.common.domain.account.CapName
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** アカウント設定画面のキャップ。発行・再発行したパスワードはその場だけで表示する */
object AccountCapIsland {
  import AccountPostView.{button, number, objects}

  private final case class CapRow(id: String, name: String, strong: Boolean, issuedAt: Double, revokedAt: Option[Double], posts: List[js.Object]) {
    def active: Boolean = revokedAt.isEmpty
    def label: String = if strong then s"$name ★（強キャップ）" else s"$name ★"
  }
  private final case class Issued(name: String, password: String)

  private def rows(raw: js.Object): List[CapRow] = objects(raw, "caps").map { cap =>
    CapRow(AuthClient.text(cap, "id"), AuthClient.text(cap, "name"), AuthClient.flag(cap, "strong"), number(cap, "issuedAt").getOrElse(0d),
      number(cap, "revokedAt"), objects(cap, "recentPosts").take(5))
  }
  private def dateTime(seconds: Double): String = if seconds > 0 then io._3ch.common.text.JstTime.dateTime(seconds) else ""

  final val Component = ScalaFnComponent[js.Object] { raw =>
    for {
      caps <- useState(rows(raw))
      name <- useState("")
      strong <- useState(false)
      issued <- useState(Option.empty[Issued])
      message <- useState("")
      busy <- useState(false)
    } yield {
      val admin = AuthClient.flag(raw, "admin")
      val allowed = admin || AuthClient.flag(raw, "canIssue")
      def now: Double = math.floor(js.Date.now() / 1000)
      def send(path: String, fields: Seq[(String, String)])(done: js.Object => Unit): Callback =
        issued.setState(None) >> busy.setState(true) >> message.setState("") >> Callback {
          val params = new dom.URLSearchParams()
          fields.foreach { case (k, v) => params.set(k, v) }
          dom.fetch(path, new dom.RequestInit { method = dom.HttpMethod.POST; credentials = dom.RequestCredentials.`same-origin`; body = params }).toFuture
            .flatMap(r => r.json().toFuture.map(json => (r.ok, json.asInstanceOf[js.Object]))).map { case (ok, json) =>
              busy.setState(false).runNow()
              if ok then done(json) else message.setState(AuthClient.text(json, "error", "操作できませんでした")).runNow()
            }.recover { case _ => busy.setState(false).runNow(); message.setState("通信できませんでした").runNow() }
        }
      def revoked(id: String): List[CapRow] => List[CapRow] = _.map(row => if row.id == id && row.active then row.copy(revokedAt = Some(now)) else row)
      val issue = send("/auth/cap/issue", Seq("name" -> name.value.trim) ++ (if strong.value then Seq("strong" -> "1") else Nil)) { json =>
        val row = CapRow(AuthClient.text(json, "id"), name.value.trim, strong.value, now, None, Nil)
        caps.modState(_ :+ row).runNow()
        issued.setState(Some(Issued(row.label, AuthClient.text(json, "password")))).runNow()
        name.setState("").runNow()
      }
      def reissue(row: CapRow): Callback = send("/auth/cap/reissue", Seq("id" -> row.id)) { json =>
        val next = row.copy(id = AuthClient.text(json, "id"), issuedAt = now, revokedAt = None, posts = Nil)
        caps.modState(list => revoked(row.id)(list) :+ next).runNow()
        issued.setState(Some(Issued(next.label, AuthClient.text(json, "password")))).runNow()
      }
      def revoke(row: CapRow): Callback = send("/auth/cap/revoke", Seq("id" -> row.id)) { _ =>
        caps.modState(revoked(row.id)).runNow()
        message.setState("キャップを無効にしました").runNow()
      }
      val active = caps.value.filter(_.active)
      val inactive = caps.value.filterNot(_.active)
      val canIssue = allowed && (admin || active.isEmpty)
      <.section(^.id := "cap", ^.className := "my-4 border border-[#d6dce3] bg-white p-4",
        <.h2(^.className := "mb-2 font-bold", "■ キャップ"),
        if !allowed && caps.value.isEmpty then <.p("削除・規制などの権限を持つ人だけが発行できます。")
        else <.p(^.className := "mb-3", "メール欄に「#パスワード」と入れて書き込むと、名前欄が「名前＠キャップ名 ★」になります。パスワードは発行したときにしか表示されません。"),
        active.toVdomArray { row =>
          <.div(^.key := row.id, ^.className := "my-2 border-b border-[#d6dce3] py-3",
            <.div(^.className := "flex flex-wrap items-center justify-between gap-2",
              <.div(<.b(row.label), <.small(^.className := "ml-2 text-[#697785]", s"${dateTime(row.issuedAt)}に発行")),
              <.div(^.className := "flex gap-2",
                <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> reissue(row), "パスワードを再発行"),
                <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value, ^.onClick --> revoke(row), "無効にする"))),
            row.posts.headOption.fold[VdomNode](<.p(^.className := "mt-2 text-xs text-[#697785]", "まだ使われていません"))(AccountPostView(_)),
            if row.posts.size > 1 then <.details(^.className := "mt-2", <.summary(^.className := "cursor-pointer text-xs", s"最近の書き込み（${row.posts.size}件）"),
              row.posts.zipWithIndex.toVdomArray { case (p, index) => <.div(^.key := index, AccountPostView(p)) }) else EmptyVdom)
        },
        if active.nonEmpty then <.p(^.className := "text-xs text-[#697785]", "心当たりの無い書き込みがあれば、パスワードを再発行するか、キャップを無効にしてください。") else EmptyVdom,
        if canIssue then <.div(^.className := "mt-3 flex flex-wrap items-center gap-2",
          <.label(^.htmlFor := "cap-name", "キャップ名"),
          <.input(^.id := "cap-name", ^.`type` := "text", ^.maxLength := CapName.MaxLength, ^.className := "border border-[#d6dce3] px-2 py-1",
            ^.value := name.value, ^.onChange ==> ((e: ReactEventFromInput) => name.setState(e.target.value))),
          if admin then <.label(<.input(^.`type` := "checkbox", ^.className := "mr-1", ^.checked := strong.value,
            ^.onChange ==> ((e: ReactEventFromInput) => strong.setState(e.target.checked))), "強キャップ") else EmptyVdom,
          <.button(^.`type` := "button", ^.className := button, ^.disabled := busy.value || name.value.trim.isEmpty, ^.onClick --> issue, "キャップを発行"))
        else EmptyVdom,
        if canIssue then <.p(^.className := "my-2 text-xs text-[#697785]",
          if admin then "管理者は何個でも発行できます。" else s"自分用のキャップを1個だけ発行できます。名前は${CapName.MaxLength}文字までです。") else EmptyVdom,
        issued.value.toVdomArray { value => <.div(^.key := "issued", ^.className := "mt-3 border border-[#f66] bg-[#fff7f3] p-3",
          <.b(s"${value.name} のパスワード（今だけ表示されます）"), <.pre(^.className := "my-2 select-all whitespace-pre-wrap break-all", value.password),
          <.button(^.`type` := "button", ^.className := button, ^.onClick --> Callback {
            dom.window.navigator.clipboard.writeText(value.password).toFuture.map(_ => message.setState("コピーしました").runNow())
              .recover { case _ => message.setState("パスワードを選択してコピーしてください").runNow() }
          }, "コピー"),
          <.p(^.className := "mt-2 text-xs", "メール欄にそのまま入れて書き込んでください。")) },
        if inactive.nonEmpty then <.details(^.className := "my-3 text-xs", <.summary(^.className := "cursor-pointer", "無効にしたキャップ"),
          inactive.toVdomArray { row => <.div(^.key := row.id, ^.className := "my-2 border-t border-[#d6dce3] pt-2",
            <.b(row.label), s" ${row.revokedAt.fold("")(dateTime)}に無効化") }) else EmptyVdom,
        <.p(^.role := "status", ^.className := "my-2", message.value))
    }
  }
}
