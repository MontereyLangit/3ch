package io._3ch.common.routes

import scala.annotation.StaticAnnotation

/** 同じ宣言の JSExportTopLevel を app/routes の指定ファイルへ公開する。1 つの宣言に複数付けられる */
final class route(val filePath: String, val method: String = "GET") extends StaticAnnotation

/** 同じ宣言の JSExportTopLevel を app/islands の指定ファイルへ公開する。P は props の型の完全修飾名で、省くと js.Object */
final class islands[P](val filePath: String) extends StaticAnnotation
