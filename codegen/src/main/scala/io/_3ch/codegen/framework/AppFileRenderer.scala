package io._3ch.codegen.framework

/** route / island の TS ファイルと、SSR 用 Scala API を描画する */
object AppFileRenderer {

  private def lines(values: List[String]): String = values.mkString("", "\n", "\n")

  /** GET は `export default`、それ以外は `export const <METHOD>` にする */
  def route(entry: RouteEntry, config: CodegenConfig): String = {
    val module = config.virtualModule(config.serverModule)
    val handlers = entry.sortedHandlers
    val exportLines = handlers.map { handler =>
      if (handler.method == HttpMethod.GET) s"export default createRoute((c) => ${handler.exportName}(c));"
      else s"export const ${handler.method} = createRoute((c) => ${handler.exportName}(c));"
    }
    lines(config.banner ++ List(
      "import { createRoute } from \"honox/factory\";",
      s"// @ts-expect-error $module は仮想モジュール（vite.config.ts参照）。",
      s"""import { ${handlers.map(_.exportName).distinct.mkString(", ")} } from "$module";""",
      ""
    ) ++ exportLines)
  }

  /** client の export を HonoX が見つけられる default export として再公開する */
  def island(entry: IslandEntry, config: CodegenConfig): String = {
    val module = s"${config.virtualModulePrefix}${entry.sourceModule}"
    lines(config.banner ++ List(
      s"// @ts-expect-error $module は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。",
      s"""import { ${entry.exportedName} } from "$module";""",
      s"export default ${entry.exportedName};"
    ))
  }

  /** island ごとに、client の export と HonoX の識別子を結んだ SSR 用メソッドを持つ object を出す。並びはファイルパス順 */
  def islandComponents(entries: List[IslandEntry], config: CodegenConfig): String = {
    val sorted = entries.sortBy(_.filePath)
    val header = lines(config.banner :+ s"package ${config.scalaPackage}")
    if (sorted.isEmpty) header + s"\nobject ${config.scalaObject} {}\n"
    else {
      val imports =
        """
          |import japgolly.scalajs.react.facade.{HasMutableDisplayName, React}
          |import scala.scalajs.js
          |import scala.scalajs.js.annotation.JSImport
          |""".stripMargin
      def props(entry: IslandEntry): String = entry.propsType.getOrElse("_root_.scala.scalajs.js.Object")
      val components = sorted.zipWithIndex.map { case (entry, index) =>
        s"""
           |@js.native
           |@JSImport("${config.virtualModulePrefix}${entry.sourceModule}", "${entry.exportedName}")
           |private object IslandComponent$index extends js.Function1[${props(entry)}, React.Node] with HasMutableDisplayName {
           |  def apply(props: ${props(entry)}): React.Node = js.native
           |}
           |""".stripMargin
      }.mkString
      val methods = sorted.zipWithIndex.map { case (entry, index) =>
        s"""  def `${entry.exportedName}`(props: ${props(entry)}): _root_.japgolly.scalajs.react.vdom.VdomElement = {
           |    _root_.${config.islandBridge}(_root_.${config.scalaPackage}.IslandComponent$index, props, "${config.islandIdPrefix}${entry.filePath}")
           |  }
           |
           |""".stripMargin
      }.mkString
      header + imports + components + s"\nobject ${config.scalaObject} {\n" + methods + "}\n"
    }
  }
}
