package ch3s.bbsclient.islands

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.scalajs.js

/** 共通ヘッダー */
object SiteHeaderIsland {
  final case class State(hidden: Boolean)

  private val headerClass =
    """site-header sticky top-0 z-10 flex min-h-16 items-center gap-5 border-b-[1.5px] border-[#f66] bg-[#f9f9f9] px-[15px]
      | py-2.5 shadow-[0_1px_4px_#0002] transition-transform duration-200 [&.header-hidden]:-translate-y-full [&.header-hidden]:shadow-none
      | [&_nav]:flex [&_nav]:flex-wrap [&_nav]:items-center [&_nav]:gap-3.5 [&_nav_a]:text-[#333] [&_nav_a]:no-underline max-[767px]:flex-wrap
      | max-[767px]:gap-3 max-[767px]:px-2 max-[767px]:[&_nav]:gap-2 max-[767px]:[&_nav]:text-xs""".stripMargin.replace("\n", "")

  final class Backend($: BackendScope[Unit, State]) {
    private val headerRef = Ref[dom.html.Element]
    private var lastY = 0.0
    private var focused = false
    private val onScroll: js.Function1[dom.Event, Unit] = _ => scroll.runNow()
    private val onResize: js.Function1[dom.Event, Unit] = _ => $.state.flatMap(s => place(s.hidden)).runNow()

    private def header: Option[dom.html.Element] = headerRef.get.runNow()

    /** `--header-height` を直近の `.board-ui` へ反映する。隠れている間は 0。 */
    private def place(hidden: Boolean): Callback = Callback {
      header.foreach { h =>
        val board = h.closest(".board-ui")
        if board != null then {
          val height = if hidden then 0.0 else h.offsetHeight
          board.asInstanceOf[dom.html.Element].style.setProperty("--header-height", s"${height.toInt}px")
        }
      }
    }

    private def setHidden(hidden: Boolean): Callback = $.modState(_.copy(hidden = hidden)) >> place(hidden)

    private def scroll: Callback = Callback.suspend {
      val y = dom.window.scrollY
      if math.abs(y - lastY) < 5 then Callback.empty
      else {
        val hide = y > lastY && header.exists(h => y > h.offsetHeight) && !focused
        lastY = y
        setHidden(hide)
      }
    }

    def mount: Callback = Callback {
      lastY = dom.window.scrollY
      dom.window.addEventListener("scroll", onScroll, js.Dynamic.literal(passive = true).asInstanceOf[dom.EventListenerOptions])
      dom.window.addEventListener("resize", onResize)
    } >> place(false)

    def unmount: Callback = Callback {
      dom.window.removeEventListener("scroll", onScroll)
      dom.window.removeEventListener("resize", onResize)
    }

    private def focusSearch(e: ReactMouseEvent): Callback = {
      Option(dom.document.getElementById("board-search")) match {
        case Some(input: dom.html.Input) =>
          e.preventDefaultCB >> Callback {
            input.focus(js.Dynamic.literal(preventScroll = true).asInstanceOf[dom.FocusOptions])
            input.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "start"))
          }
        case _ => Callback.empty
      }
    }

    def render(state: State): VdomNode = {
      val hiddenClass = if state.hidden then " header-hidden" else ""
      <.header(^.className := s"$headerClass$hiddenClass", ^.onFocus --> (Callback { focused = true } >> setHidden(false)),
        ^.onBlur --> Callback { focused = false },
        <.a(^.href := "/", ^.className := "site-logo ml-[15px] shrink-0", ^.aria.label := "３ちゃんねる ホーム",
          <.img(^.src := "/static/3ch_logo.png", ^.alt := "３ちゃんねる", ^.className := "inline-block h-[30px] align-middle")),
        <.nav(^.aria.label := "メインメニュー",
          <.a(^.href := "/", "ホーム"), <.span("|"), <.a(^.href := "/bbsmenu.html", "板一覧"), <.span("|"),
          <.a(^.href := "#board-search", ^.onClick ==> focusSearch, "検索")),
        <.nav(^.className := "account-nav ml-auto", ^.aria.label := "アカウント",
          <.span(^.title := "準備中", "認証・登録"), <.span("|"), <.span(<.span(^.title := "準備中", "ログイン"),
            <.button(^.`type` := "button", ^.className := "dropBtnSetting settingDrop cursor-pointer border-0 bg-transparent p-0", ^.title := "準備中",
              ^.aria.label := "アカウントメニュー", VdomAttr("aria-expanded") := "false",
              <.img(^.src := "/static/icon_extend.png", ^.alt := "", ^.className := "dropBtnSetting ml-[5px] mb-[2px] inline"))))
      ).withRef(headerRef)
    }
  }

  final val Component = ScalaComponent
    .builder[Unit]("SiteHeader")
    .initialState(State(hidden = false))
    .backend(new Backend(_))
    .renderPS((self, p, s) => self.backend.render(s))
    .componentDidMount(_.backend.mount)
    .componentWillUnmount(_.backend.unmount)
    .build
}
