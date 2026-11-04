package io._3ch.bbsclient.islands

import japgolly.scalajs.react.*
import org.scalajs.dom

import scala.scalajs.js

/**
 * 下スクロールで隠れ、上スクロール・フォーカスで現れるヘッダーの状態
 * refをヘッダー要素へ、onFocus / onBlurを同じ要素のイベントへ付ける
 */
final case class AutoHideHeader(hidden: Boolean, ref: Ref.ToVdom[dom.html.Element], onFocus: Callback, onBlur: ReactFocusEvent => Callback)

object AutoHideHeader {

  /**
   * --header-heightを直近の.board-uiへ反映
   * 隠れている間は0となる
   * 高さは計測済みの値を利用
   */
  private def place(board: Option[dom.html.Element], height: Double, hidden: Boolean): Unit = {
    board.foreach(_.style.setProperty("--header-height", s"${(if hidden then 0.0 else height).toInt}px"))
  }

  /**
   * スクロール時のヘッダー表示切替処理のHooks
   * @return HookResult[AutoHideHeader]
   */
  def useAutoHideHeader: HookResult[AutoHideHeader] = {
    for {
      hidden <- useState(false)
      headerRef <- useRefToVdom[dom.html.Element]
      lastY <- useRef(0.0)
      focused <- useRef(false)
      hiddenNow <- useRef(false)
      height <- useRef(0.0)
      board <- useRef(Option.empty[dom.html.Element])
      setHidden = (value: Boolean) => Callback.when(value != hiddenNow.value) {
        Callback { hiddenNow.value = value } >> hidden.setState(value) >> Callback(place(board.value, height.value, value))
      }
      _ <- useEffectOnMount(CallbackTo {
        val measure: () => Unit = () => {
          headerRef.get.runNow().foreach(h => height.value = h.offsetHeight)
          place(board.value, height.value, hiddenNow.value)
        }
        val onScroll: js.Function1[dom.Event, Unit] = _ => {
          val y = dom.window.scrollY
          if math.abs(y - lastY.value) >= 5 then {
            val hide = y > lastY.value && y > height.value && !focused.value
            lastY.value = y
            setHidden(hide).runNow()
          }
        }
        val onResize: js.Function1[dom.Event, Unit] = _ => measure()
        board.value = headerRef.get.runNow().flatMap(h => Option(h.closest(".board-ui")).map(_.asInstanceOf[dom.html.Element]))
        lastY.value = dom.window.scrollY
        dom.window.addEventListener("scroll", onScroll, js.Dynamic.literal(passive = true).asInstanceOf[dom.EventListenerOptions])
        dom.window.addEventListener("resize", onResize)
        measure()
        Callback {
          dom.window.removeEventListener("scroll", onScroll)
          dom.window.removeEventListener("resize", onResize)
        }
      })
    } yield {
      val onFocus = Callback { focused.value = true } >> setHidden(false)

      /** ヘッダー内の要素間でフォーカスが移るときは、ヘッダーのフォーカスが外れたとみなさない */
      def onBlur(e: ReactFocusEvent): Callback = Callback {
        val next = e.relatedTarget
        if next == null || !headerRef.get.runNow().exists(_.contains(next.asInstanceOf[dom.Node])) then focused.value = false
      }

      AutoHideHeader(hidden.value, headerRef, onFocus, onBlur)
    }
  }
}
