package io._3ch.common.routes

/** 公開パスの互換パターン。配線の生成は codegen の framework パッケージが持つ */
object RouteTable {

  // Preserve v5 prefix matches (including trailing path characters) and evaluation order.
  val publicPatterns: List[(String, String)] = List(
    "dat" -> "^/([a-zA-Z0-9\\-_]+)/dat/([0-9]{10})\\.dat",
    "subject" -> "^/([a-zA-Z0-9\\-_]+)/subject\\.txt",
    "archive" -> "^/([a-zA-Z0-9\\-_]+)/kako/([0-9]{4})/subject\\.txt",
    "archives" -> "^/([a-zA-Z0-9\\-_]+)/kako/subject\\.txt",
    "head" -> "^/([a-zA-Z0-9\\-_]+)/head\\.txt",
    "settings" -> "^/([a-zA-Z0-9\\-_]+)/SETTING\\.TXT"
  )

}
