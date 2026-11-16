package io._3ch.bbs.external.hono

import io._3ch.bbs.external.cloudflare.WorkerEnv

import scala.scalajs.js

/** 3ch のレンダラ（app/routes/_renderer.tsx）が受け取る props */
trait RenderProps extends js.Object {
  var title: js.UndefOr[String] = js.undefined
  var authModal: js.UndefOr[Boolean] = js.undefined
}

/** 3ch の Hono Context */
type HonoContextFacade = HonoContext[WorkerEnv, RenderProps]

object HonoContextFacade {
  export io._3ch.bbs.external.hono.HonoContext.renderPromise
}
