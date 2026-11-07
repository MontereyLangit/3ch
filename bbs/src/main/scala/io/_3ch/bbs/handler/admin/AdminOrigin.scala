package io._3ch.bbs.handler.admin

import io._3ch.bbs.external.web.URL

/** 管理 API の変更系の要求が、同じオリジンの管理画面から来たかの検査（改修計画 §3.5） */
object AdminOrigin {
  /** 管理画面が変更系の要求に付けるヘッダ */
  val Marker = "x-ch3s-admin"

  /** GET・HEAD 以外は、Origin が要求先と同じオリジンで、X-Ch3s-Admin が 1 のときだけ通す */
  def allows(method: String, requestUrl: String, origin: Option[String], marker: Option[String]): Boolean =
    method == "GET" || method == "HEAD" || (origin.contains(new URL(requestUrl).origin) && marker.contains("1"))
}
