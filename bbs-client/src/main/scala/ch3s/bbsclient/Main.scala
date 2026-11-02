package ch3s.bbsclient

import ch3s.bbsclient.islands.{BoardDetailsIsland, PostingFormIsland, SiteHeaderIsland, ThreadListIsland}
import ch3s.common.routes.islands
import japgolly.scalajs.react.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** client.jsの処理のexport。bbsClientで独立してリンクする。 */
object Main {

  @islands("site-header.tsx")
  @JSExportTopLevel("SiteHeaderJs", "client")
  val siteHeader: js.Any = SiteHeaderIsland.Component.cmapCtorProps[js.Object](_ => ()).toJsComponent.raw

  @islands("board-details.tsx")
  @JSExportTopLevel("BoardDetailsJs", "client")
  val boardDetails: js.Any = BoardDetailsIsland.Component.cmapCtorProps[js.Object](BoardDetailsIsland.Props.fromJs).toJsComponent.raw

  @islands("posting-form.tsx")
  @JSExportTopLevel("PostingFormJs", "client")
  val postingForm: js.Any = PostingFormIsland.Component.cmapCtorProps[js.Object](PostingFormIsland.Props.fromJs).toJsComponent.raw

  @islands("thread-list.tsx")
  @JSExportTopLevel("ThreadListJs", "client")
  val threadList: js.Any = ThreadListIsland.Component.cmapCtorProps[js.Object](ThreadListIsland.Props.fromJs).toJsComponent.raw
}
