package io._3ch.common.routes

import scala.annotation.StaticAnnotation

/** 同じ宣言の JSExportTopLevel を app/routes の指定ファイルへ公開する */
final class route(val filePath: String, val method: String = "GET") extends StaticAnnotation

/** 同じ宣言の JSExportTopLevel を app/islands の指定ファイルへ公開する */
final class islands(val filePath: String) extends StaticAnnotation
