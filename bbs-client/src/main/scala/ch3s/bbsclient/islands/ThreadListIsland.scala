package ch3s.bbsclient.islands

import ch3s.common.domain.{BbsSubject, BbsSubjects, SubjectText}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.URIUtils

/** 板topのスレ一覧（subback）とスレッドフィードの枠 */
object ThreadListIsland {

  /** `subjectText` は Board DO の subject（`key.dat<>title (n)` の行）、`now` はサーバー描画時刻（ミリ秒）。 */
  final case class Props(bbs: String, viewType: String, subjectText: String, query: String, sort: String, now: Double)

  object Props {
    def fromJs(raw: js.Object): Props = {
      val o = raw.asInstanceOf[js.Dynamic]
      def text(name: String): String = o.selectDynamic(name).asInstanceOf[js.UndefOr[String]].getOrElse("")
      Props(text("bbs"), text("type"), text("subjectText"), text("query"), text("sort"),
        o.selectDynamic("now").asInstanceOf[js.UndefOr[Double]].getOrElse(0.0))
    }
  }

  /** `seen` は行キー（`板/スレキー`）ごとに localStorage から読んだ既読レス数の文字列。 */
  final case class State(subjects: BbsSubjects, now: Double, loading: Boolean, status: String, seen: Map[String, String])

  private val SeenPrefix = "3ch:threadSeen:"

  private val rowGrid =
    """grid grid-cols-[minmax(0,1fr)_110px_85px_60px_50px_60px] items-center gap-2.5 border-b border-[#dce1e6]
      | max-[660px]:grid-cols-[minmax(0,1fr)_40px_50px] max-[660px]:gap-2 max-[660px]:px-[9px]""".stripMargin.replace("\n", "")

  private def isCommon(thread: BbsSubject): Boolean = {
    val key = thread.key.toString
    key.startsWith("900") || key.startsWith("924")
  }

  private def speed(thread: BbsSubject, now: Double): Double = {
    if isCommon(thread) then 0.0
    else thread.resNum / math.max(1.0 / 24, (now / 1000 - thread.key) / 86400)
  }

  private def rowKey(bbs: String, thread: BbsSubject): String = s"${if isCommon(thread) then "924" else bbs}/${thread.key}"

  /** スレ立て時刻（JST、`yyyy/MM/dd HH:mm`）。実行環境の Intl に依存させず SSR と一致させる。 */
  private def created(thread: BbsSubject): String = {
    val d = new js.Date(thread.key.toDouble * 1000 + 9 * 3600 * 1000)
    def two(n: Int): String = if n < 10 then s"0$n" else n.toString
    s"${d.getUTCFullYear().toInt}/${two(d.getUTCMonth().toInt + 1)}/${two(d.getUTCDate().toInt)} ${two(d.getUTCHours().toInt)}:${two(d.getUTCMinutes().toInt)}"
  }

  private def loadSeen(bbs: String, subjects: BbsSubjects): Map[String, String] = {
    subjects.value.flatMap { thread =>
      val key = rowKey(bbs, thread)
      BrowserStorage.read(s"$SeenPrefix$key").map(key -> _)
    }.toMap
  }

  /** 既読レス数より増えていれば `+N`。保存が無い・数値でない場合は出さない。 */
  private def badge(stored: Option[String], count: Int): String = {
    stored.map(s => js.Dynamic.global.Number(s).asInstanceOf[Double]).filter(n => !n.isNaN && !n.isInfinity && count > n)
      .fold("")(n => s"+${count - n}")
  }

  private val heading: VdomElement = {
    <.div(^.className := s"list-head $rowGrid px-4 py-3 text-xs", <.span("スレッド名"), <.span(^.className := "max-[660px]:hidden", "スレ立て"),
      <.span(^.className := "when max-[660px]:hidden", "最終更新"),
      <.span(^.className := "speed numeric text-right max-[660px]:hidden", "勢い"), <.span(^.className := "numeric text-right", "新着"),
      <.span(^.className := "numeric text-right", "レス"))
  }

  /** 他タブの localStorage 変更を `seen` へ反映する。`key == null`（全消去）は `seen` を空にする。 */
  private def withStorageChange(state: State, storageKey: String, newValue: String): State = {
    if storageKey == null then state.copy(seen = Map.empty)
    else if !storageKey.startsWith(SeenPrefix) then state
    else {
      val rowKey = storageKey.substring(SeenPrefix.length)
      state.copy(seen = if newValue == null then state.seen - rowKey else state.seen.updated(rowKey, newValue))
    }
  }

  final class Backend($: BackendScope[Props, State]) {
    private var mounted = false
    private var request: Option[dom.AbortController] = None

    private val onStorage: js.Function1[dom.StorageEvent, Unit] = event => {
      $.modState(withStorageChange(_, event.key, event.newValue)).runNow()
    }

    def mount: Callback = Callback {
      mounted = true
      dom.window.addEventListener("storage", onStorage)
    } >> $.props.flatMap { p =>
      $.state.map(s => loadSeen(p.bbs, s.subjects)).flatMap(seen => $.modState(_.copy(seen = seen)))
    }

    def unmount: Callback = Callback {
      mounted = false
      dom.window.removeEventListener("storage", onStorage)
      request.foreach(_.abort())
      request = None
    }

    /** 表示中の既読数を更新し、反映後にlocalStorageへ保存する */
    private def markSeen(key: String, count: Int): Callback = {
      $.modState(s => s.copy(seen = s.seen.updated(key, count.toString)), Callback(BrowserStorage.write(s"$SeenPrefix$key", count.toString)))
    }

    private def row(thread: BbsSubject, props: Props, state: State, common: Boolean): VdomElement = {
      val rate = if common then "—" else f"${speed(thread, state.now)}%.1f"
      val threadBoard = if common then "924" else props.bbs
      val key = rowKey(props.bbs, thread)
      val newClass = if thread.key.toString.startsWith("924") then "new numeric cap-update text-right font-bold text-[#d00]"
      else "new numeric text-right font-bold text-black"
      <.div(^.key := thread.key.toString, ^.className := s"list-row relative cursor-pointer $rowGrid px-4 py-[11px] text-[13px] hover:bg-[#fafdff]",
        VdomAttr("data-thread-key") := key, VdomAttr("data-res") := thread.resNum.toString,
        VdomAttr("data-common") := common.toString,
        <.div(<.a(^.href := s"/test/read.cgi/${URIUtils.encodeURIComponent(threadBoard)}/${thread.key}/",
          ^.className := "thread-link text-black no-underline after:absolute after:inset-0 after:content-[''] hover:text-[#2464b5]",
          ^.onClick --> markSeen(key, thread.resNum),
          VdomAttr.Event.mouse("onAuxClick") ==> (
            (e: ReactMouseEventFromHtml) => if e.button == 1 then markSeen(key, thread.resNum) else Callback.empty),
          SubjectText.decode(thread.title.value)),
          <.div(^.className := "sub hidden text-xs text-[#697785] max-[660px]:block", s"勢い $rate")),
        <.span(^.className := "created tabular-nums text-[#aaa] max-[660px]:hidden", if common then "—" else created(thread)),
        <.span(^.className := "when modified text-[#ff9025] max-[660px]:hidden", "—"),
        <.span(^.className := "speed numeric text-right tabular-nums text-[#cc3d3d] max-[660px]:hidden", rate),
        <.span(^.className := newClass, badge(state.seen.get(key), thread.resNum)),
        <.span(^.className := "numeric text-right tabular-nums", thread.resNum)
      )
    }

    private def subback(props: Props, state: State): VdomElement = {
      val query = props.query.toLowerCase
      val filtered = state.subjects.value.filter(t => SubjectText.decode(t.title.value).toLowerCase.contains(query))
      val sorted = props.sort match {
        case "res" => filtered.sortBy(t => -t.resNum)
        case "speed" => filtered.sortBy(t => -speed(t, state.now))
        case _ => filtered
      }
      val (commonThreads, threads) = sorted.partition(isCommon)
      <.div(^.className := "subback",
        <.details(^.className :=
          """common-threads my-2 border-t border-[#dce1e6] [&>summary]:px-4 [&>summary]:py-[9px]
            | [&>summary>span]:ml-2.5 [&>summary>span]:text-xs [&>summary>span]:text-[#4d4d4d] max-[660px]:[&>summary]:px-[9px]""".stripMargin.replace("\n", ""),
          <.summary(<.b("全板共通スレ"), <.span(s"${commonThreads.size}件")),
          heading, commonThreads.toVdomArray(row(_, props, state, true)),
          if commonThreads.isEmpty then <.p(^.className := "empty-state m-2 text-xs text-[#697785]", "全板共通スレはありません。") else EmptyVdom),
        <.div(^.className := "thread-list mt-[5px] border-t border-[#dcdcdc]", heading,
          <.div(^.className := s"list-row $rowGrid px-4 py-[11px] text-[13px] hover:bg-[#fafdff]", <.a(^.className := "text-[#06f] hover:underline",
            ^.href := s"/${URIUtils.encodeURIComponent(props.bbs)}/kako/", "↩過去ログ倉庫")),
          threads.toVdomArray(row(_, props, state, false)),
          if threads.isEmpty then <.p(^.className := "empty-state m-2 text-xs text-[#697785]", "該当するスレッドはありません。") else EmptyVdom),
        <.p(^.className := "list-note m-2 text-xs text-[#697785]", "(新着順、最終更新日時は準備中)")
      )
    }

    private def decodeShiftJis(buffer: js.Any): String = {
      val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("shift_jis")
      decoder.decode(buffer).asInstanceOf[String]
    }

    /** `/<板>/subject.txt`（Shift_JIS）を取り直して一覧を差し替える。アンマウントで中断する。 */
    private def reload: Callback = $.props.flatMap { p =>
      $.modState(_.copy(loading = true, status = "更新中…")) >> Callback {
        request.foreach(_.abort())
        val controller = new dom.AbortController()
        request = Option(controller)
        val init = new dom.RequestInit {}
        init.cache = dom.RequestCache.`no-store`
        init.credentials = dom.RequestCredentials.`same-origin`
        init.signal = controller.signal
        Future.delegate(dom.fetch(s"/${URIUtils.encodeURIComponent(p.bbs)}/subject.txt", init).toFuture)
          .flatMap { r =>
            if !r.ok then Future.failed(new RuntimeException(s"HTTP ${r.status}"))
            else r.arrayBuffer().toFuture.map(decodeShiftJis)
          }
          .onComplete { result =>
            if mounted && !controller.signal.aborted then {
              if request.contains(controller) then request = None
              result match {
                case scala.util.Success(text) =>
                  val subjects = BbsSubjects.create(text)
                  val seen = loadSeen(p.bbs, subjects)
                  $.modState(_.copy(subjects = subjects, now = js.Date.now(), loading = false, status = "", seen = seen)).runNow()
                case scala.util.Failure(_) =>
                  $.modState(_.copy(loading = false, status = "更新できませんでした")).runNow()
              }
            }
          }
      }
    }

    def render(props: Props, state: State): VdomNode = {
      <.div(^.id := "thread-list",
        <.div(^.className := "newposts border-b-2 border-[#e0e0e0] py-0.5 text-center",
          <.button(^.className :=
            """inline-flex cursor-pointer items-center gap-0.5 border-0 bg-transparent p-1 text-base text-[#0187e8]
              | hover:underline disabled:cursor-wait""".stripMargin,
            ^.`type` := "button", VdomAttr("data-board-reload") := "", ^.disabled := state.loading, ^.onClick --> reload,
            "リロード",
            <.img(^.src := "/static/reload.svg", ^.alt := "", ^.className := (if state.loading then "inline size-4 animate-spin" else "inline size-4"))),
          <.span(^.className := "block text-xs text-[#777]", VdomAttr("data-board-reload-status") := "", VdomAttr("aria-live") := "polite", state.status)),
        if props.viewType == "subback" then subback(props, state)
        else <.div(^.className := "thread-feed", <.p(^.className := "coming-soon m-2 py-6 text-center text-[#697785]", "Coming Soon…"))
      )
    }
  }

  final val Component = ScalaComponent
    .builder[Props]("ThreadList")
    .initialStateFromProps(p => State(BbsSubjects.create(p.subjectText), p.now, loading = false, status = "", seen = Map.empty))
    .backend(new Backend(_))
    .renderPS((self, p, s) => self.backend.render(p, s))
    .componentDidMount(_.backend.mount)
    .componentWillUnmount(_.backend.unmount)
    .build
}
