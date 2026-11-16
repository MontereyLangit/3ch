package io._3ch.codegen.framework

/** route ファイルに出せる HTTP メソッド。ファイル内の export はこの順に並べる */
enum HttpMethod {
  case GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
}

object HttpMethod {
  def parse(name: String): Option[HttpMethod] = values.find(_.toString == name)
}

final case class RouteHandler(method: HttpMethod, exportName: String)

/**
 * route ファイル 1 つ分
 * @param filePath route 出力ディレクトリからの `/` 区切りの相対パス（例: `"auth/index.ts"`）
 */
final case class RouteEntry(filePath: String, handlers: List[RouteHandler]) {
  def sortedHandlers: List[RouteHandler] = handlers.sortBy(_.method.ordinal)
}

/**
 * island ファイル 1 つ分
 * @param filePath     island 出力ディレクトリからの相対パス（例: `"thread-view.tsx"`）
 * @param exportedName `@JSExportTopLevel` の名前
 * @param sourceModule import 元の仮想モジュール名（例: `"client.js"`）
 * @param propsType    props の型の完全修飾名（`_root_.` 付き）。型引数が無ければ None
 */
final case class IslandEntry(filePath: String, exportedName: String, sourceModule: String, propsType: Option[String] = None)

final case class AppEntries(routes: List[RouteEntry], islands: List[IslandEntry])
