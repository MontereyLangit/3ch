package io._3ch.admin.components

import io._3ch.admin.SaveState
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*

/** 保存・操作の結果を画面上部に帯で出す。reload があれば競合時に「開き直す」を出す */
object StatusBanner {
  private val base = "mb-3 flex flex-wrap items-center gap-2.5 border px-3 py-2"

  def apply(state: SaveState, reload: Option[Callback] = None): VdomNode = state match {
    case SaveState.Idle | SaveState.Saving => EmptyVdom
    case SaveState.Saved(message) => <.p(^.role := "status", ^.className := s"$base border-[#b5dfc3] bg-[#e7f5ec] text-[#1a7f37]", message)
    case SaveState.Conflict(message) => <.div(^.role := "alert", ^.className := s"$base border-[#f3c9a8] bg-[#fff8f1]", <.span(message),
      reload.fold(EmptyVdom)(cb => <.button(^.`type` := "button", ^.className := Styles.button, ^.onClick --> cb, "開き直す")))
    case SaveState.Invalid(message, _) => <.p(^.role := "alert", ^.className := s"$base border-[#e8a0a0] bg-[#fff6f6] text-[#c00]", message)
    case SaveState.Failed(message) => <.p(^.role := "alert", ^.className := s"$base border-[#e8a0a0] bg-[#fff6f6] text-[#c00]", message)
  }
}
