package io._3ch.codegen.framework

import java.nio.file.Path

/**
 * codegen の設定。相対パスは projectRoot から解決する
 * @param serverSources       `@route` を探すソースファイル
 * @param clientSources       `@islands` を探すソースファイル
 * @param serverModule        server 側の `@JSExportTopLevel` の moduleID
 * @param clientModule        client 側の `@JSExportTopLevel` の moduleID
 * @param routeDir            route ファイルの出力ディレクトリ
 * @param islandDir           island ファイルの出力ディレクトリ
 * @param islandIdPrefix      SSR で HonoX に渡す island 識別子の接頭辞
 * @param scalaOutput         SSR 用 Scala API の出力ファイル。None なら出さない
 * @param scalaPackage        SSR 用 Scala API のパッケージ名
 * @param scalaObject         SSR 用 Scala API の object 名
 * @param islandBridge        island の hydration 境界を付ける関数の完全修飾名
 * @param annotationPackage   `route` / `islands` アノテーションのパッケージ
 * @param virtualModulePrefix Scala.js のリンク成果物を指す仮想モジュールの接頭辞
 * @param banner              生成ファイルの先頭行。1 行目は generatedMarker で始める
 * @param generatedMarker     生成ファイルかどうかを見分ける先頭行の接頭辞
 */
final case class CodegenConfig(
  projectRoot: Path,
  serverSources: List[String],
  clientSources: List[String],
  serverModule: String,
  clientModule: String,
  routeDir: String,
  islandDir: String,
  islandIdPrefix: String,
  scalaOutput: Option[Path],
  scalaPackage: String,
  scalaObject: String,
  islandBridge: String,
  annotationPackage: String,
  virtualModulePrefix: String,
  banner: List[String],
  generatedMarker: String
) {
  def routeRoot: Path = projectRoot.resolve(routeDir).normalize()
  def islandRoot: Path = projectRoot.resolve(islandDir).normalize()
  def virtualModule(module: String): String = s"$virtualModulePrefix$module.js"
}
