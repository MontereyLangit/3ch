package ch3s.codegen

import ch3s.common.routes.RouteTable.*
import scala.meta.*
import scala.collection.mutable.ListBuffer
import java.util.Locale

/** Main の export 宣言に付いたリテラルアノテーションを読む。 */
object AnnotatedAppEntries {
  final case class Entries(routes: List[RouteEntry], islands: List[IslandEntry])

  def parse(path: String, source: String, module: String): Entries = {
    require(Set("server", "client")(module), s"$path: unsupported module $module")
    val tree = dialects.Scala3(Input.VirtualFile(path, source)).parse[Source].get
    val routeHandlers = ListBuffer.empty[(String, String, String)]
    val islandEntries = ListBuffer.empty[IslandEntry]
    val main = tree.collect { case obj: Defn.Object if obj.name.value == "Main" => obj }
    require(main.size == 1, s"$path: expected one Main object")
    main.head.templ.body.stats.foreach { member =>
      val mods = member match {
        case d: Defn.Def => d.mods
        case v: Defn.Val => v.mods
        case _ => Nil
      }
      val annotations = mods.collect { case a: Mod.Annot => a }
      def named(a: Mod.Annot, name: String): Boolean =
        a.init.tpe.syntax == name || a.init.tpe.syntax == s"ch3s.common.routes.$name"
      val markers = annotations.filter(a => named(a, "route") || named(a, "islands"))
      if (markers.nonEmpty) {
        val exports = annotations.filter(a => Set("JSExportTopLevel", "scala.scalajs.js.annotation.JSExportTopLevel")(a.init.tpe.syntax))
        require(exports.size == 1, s"$path: annotated member needs exactly one JSExportTopLevel")
        val exportArgs = literalArgs(exports.head, List("name", "moduleID"), path)
        require(exportArgs.size == 2 && exportArgs(1) == module, s"$path: expected literal export module $module")
        val name = exportArgs.head
        require(name.matches("[A-Za-z_$][A-Za-z0-9_$]*"), s"$path: invalid export identifier $name")
        markers.foreach { marker =>
          if (named(marker, "route")) {
            require(module == "server", s"$path: @route belongs in bbs.Main")
            val args = literalArgs(marker, List("filePath", "method"), path)
            require(args.nonEmpty && args.size <= 2, s"$path: @route needs filePath and optional method")
            val file = safePath(args.head, ".ts", path)
            val method = args.lift(1).getOrElse("GET")
            require(Set("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")(method), s"$path: unsupported HTTP method $method")
            routeHandlers += ((file, method, name))
          } else {
            require(module == "client", s"$path: @islands belongs in bbs-client.Main")
            val args = literalArgs(marker, List("filePath"), path)
            require(args.size == 1, s"$path: @islands needs filePath")
            val file = safePath(args.head, ".tsx", path)
            require(file.split("/").last.matches("[A-Za-z0-9-]+\\.tsx"), s"$path: island basename must match HonoX discovery")
            islandEntries += IslandEntry(file, name, s"$module.js")
          }
        }
      }
    }
    val routePaths = routeHandlers.map(_._1).distinct
    require(routePaths.map(_.toLowerCase(Locale.ROOT)).distinct.size == routePaths.size,
      s"$path: route paths must not differ only by case")
    require(routeHandlers.map(x => (x._1.toLowerCase(Locale.ROOT), x._2)).distinct.size == routeHandlers.size,
      s"$path: duplicate route method")
    require(islandEntries.map(_.filePath.toLowerCase(Locale.ROOT)).distinct.size == islandEntries.size,
      s"$path: duplicate island path")
    val routes = routeHandlers.toList.groupBy(_._1).toList.sortBy(_._1).map { case (file, handlers) =>
      RouteEntry(file, RouteKind.MultiMethodPassThrough(handlers.map(x => x._2 -> x._3)), Nil)
    }
    Entries(routes, islandEntries.toList)
  }

  private def literalArgs(annotation: Mod.Annot, names: List[String], path: String): List[String] = {
    annotation.init.argClauses.flatMap(_.values).toList.zipWithIndex.map { case (arg, index) =>
      val value = arg match {
        case Term.Assign(Term.Name(name), rhs) =>
          require(names.lift(index).contains(name), s"$path: annotation arguments must follow ${names.mkString(", ")}")
          rhs
        case other => other
      }
      value match {
        case Lit.String(text) => text
        case _ => throw new IllegalArgumentException(s"$path: annotation arguments must be string literals")
      }
    }
  }

  private def safePath(value: String, extension: String, source: String): String = {
    require(value.endsWith(extension) && value.split("/", -1).forall(segment =>
      segment.nonEmpty && segment != "." && segment != ".." && segment.matches("[A-Za-z0-9_.$\\[\\]-]+")
    ), s"$source: unsafe output path $value")
    value
  }
}
