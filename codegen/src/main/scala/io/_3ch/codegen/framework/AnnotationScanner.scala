package io._3ch.codegen.framework

import io._3ch.codegen.framework.CodegenError.{Result, at, check}
import scala.meta.*
import java.util.Locale

/** トップレベル object のメンバに付いた `@route` / `@islands` と `@JSExportTopLevel` を読む */
object AnnotationScanner {

  /** 1 つのアノテーションから読んだ出力 1 件。位置は重複の報告に使う */
  private enum Found {
    case Route(file: String, method: HttpMethod, exportName: String, tree: Tree)
    case Island(entry: IslandEntry, tree: Tree)
  }

  private final case class Scanned(source: String, found: List[Found], exports: List[(String, Tree)])

  private val ExportAnnotations = Set("JSExportTopLevel", "scala.scalajs.js.annotation.JSExportTopLevel")
  private val IdentifierPattern = "[A-Za-z_$][A-Za-z0-9_$]*"
  private val IslandBasename = "[A-Za-z0-9-]+\\.tsx"

  /** server 側のソース群から route を読む。sources は (表示用パス, 本文) */
  def routes(sources: List[(String, String)], config: CodegenConfig): Result[List[RouteEntry]] =
    scanAll(sources, config.serverModule, config).flatMap { scanned =>
      val found = scanned.flatMap(s => s.found.collect { case r: Found.Route => s.source -> r })
      val errors = routeConflicts(found)
      if (errors.nonEmpty) Left(errors)
      else Right(found.map(_._2).groupBy(_.file).toList.sortBy(_._1).map { case (file, handlers) =>
        RouteEntry(file, handlers.map(h => RouteHandler(h.method, h.exportName)).sortBy(_.method.ordinal))
      })
    }

  /** client 側のソース群から island を読む */
  def islands(sources: List[(String, String)], config: CodegenConfig): Result[List[IslandEntry]] =
    scanAll(sources, config.clientModule, config).flatMap { scanned =>
      val found = scanned.flatMap(s => s.found.collect { case i: Found.Island => s.source -> i })
      val errors = duplicates(found.map { case (source, i) => (source, i.tree, i.entry.filePath.toLowerCase(Locale.ROOT)) },
        key => s"duplicate island path (case-insensitive) $key")
      if (errors.nonEmpty) Left(errors) else Right(found.map(_._2.entry).sortBy(_.filePath))
    }

  private def scanAll(sources: List[(String, String)], module: String, config: CodegenConfig): Result[List[Scanned]] =
    CodegenError.sequence(sources.map { case (path, source) => scan(path, source, module, config) }).flatMap { scanned =>
      val exports = scanned.flatMap(s => s.exports.map { case (name, tree) => (s.source, tree, name) })
      val errors = duplicates(exports, name => s"export name $name is declared more than once")
      if (errors.nonEmpty) Left(errors) else Right(scanned)
    }

  private def scan(path: String, source: String, module: String, config: CodegenConfig): Result[Scanned] =
    dialects.Scala3(Input.VirtualFile(path, source)).parse[Source] match {
      case error: Parsed.Error => Left(List(CodegenError.position(path, error.pos, error.message)))
      case Parsed.Success(tree) =>
        val members = topLevelObjects(tree).flatMap(_.templ.body.stats)
        CodegenError.sequence(members.map(member => scanMember(path, member, module, config))).map { results =>
          Scanned(path, results.flatMap(_._1), results.flatMap(_._2))
        }
    }

  private def topLevelObjects(tree: Source): List[Defn.Object] = {
    def walk(stats: List[Stat]): List[Defn.Object] = stats.flatMap {
      case obj: Defn.Object => List(obj)
      case pkg: Pkg => walk(pkg.body.stats)
      case _ => Nil
    }
    walk(tree.stats)
  }

  private def scanMember(path: String, member: Stat, module: String, config: CodegenConfig): Result[(List[Found], List[(String, Tree)])] = {
    val annotations = member match {
      case d: Defn.Def => d.mods.collect { case a: Mod.Annot => a }
      case v: Defn.Val => v.mods.collect { case a: Mod.Annot => a }
      case _ => Nil
    }
    def named(a: Mod.Annot, name: String): Boolean = {
      val base = baseType(a.init.tpe).syntax
      base == name || base == s"${config.annotationPackage}.$name" || base == s"_root_.${config.annotationPackage}.$name"
    }
    val markers = annotations.filter(a => named(a, "route") || named(a, "islands"))
    if (markers.isEmpty) Right((Nil, Nil))
    else exportName(path, member, annotations, module).flatMap { name =>
      CodegenError.sequence(markers.map { marker =>
        if (named(marker, "route")) route(path, marker, name, module, config) else island(path, marker, name, module, config)
      }).map(found => (found, List(name -> member)))
    }
  }

  private def exportName(path: String, member: Stat, annotations: List[Mod.Annot], module: String): Result[String] = {
    annotations.filter(a => ExportAnnotations(a.init.tpe.syntax)) match {
      case List(exported) =>
        literalArgs(path, exported, List("name", "moduleID")).flatMap {
          case List(name, exportModule) =>
            for {
              _ <- check(exportModule == module, at(path, exported, s"expected @JSExportTopLevel moduleID $module, found $exportModule"))
              _ <- check(name.matches(IdentifierPattern), at(path, exported, s"invalid export identifier $name"))
            } yield name
          case _ => Left(List(at(path, exported, s"@JSExportTopLevel needs a literal name and moduleID $module")))
        }
      case _ => Left(List(at(path, member, "annotated member needs exactly one @JSExportTopLevel")))
    }
  }

  private def route(path: String, marker: Mod.Annot, name: String, module: String, config: CodegenConfig): Result[Found] = {
    for {
      _ <- check(module == config.serverModule, at(path, marker, s"@route belongs to members exported to ${config.serverModule}"))
      _ <- check(typeArgs(marker).isEmpty, at(path, marker, "@route takes no type argument"))
      args <- literalArgs(path, marker, List("filePath", "method"))
      _ <- check(args.nonEmpty && args.size <= 2, at(path, marker, "@route needs filePath and an optional method"))
      file <- outputPath(path, marker, args.head, ".ts")
      basename = file.split("/").last
      _ <- check(!basename.startsWith("_"), at(path, marker, s"route basename $basename is reserved by HonoX (leading _)"))
      method <- HttpMethod.parse(args.lift(1).getOrElse("GET")).toRight(List(at(path, marker, s"unsupported HTTP method ${args(1)}")))
    } yield Found.Route(file, method, name, marker)
  }

  private def island(path: String, marker: Mod.Annot, name: String, module: String, config: CodegenConfig): Result[Found] = {
    for {
      _ <- check(module == config.clientModule, at(path, marker, s"@islands belongs to members exported to ${config.clientModule}"))
      args <- literalArgs(path, marker, List("filePath"))
      _ <- check(args.size == 1, at(path, marker, "@islands needs filePath"))
      file <- outputPath(path, marker, args.head, ".tsx")
      _ <- check(file.split("/").last.matches(IslandBasename), at(path, marker, s"island basename of $file must match $IslandBasename for HonoX discovery"))
      propsType <- islandPropsType(path, marker)
    } yield Found.Island(IslandEntry(file, name, s"$module.js", propsType), marker)
  }

  private def islandPropsType(path: String, marker: Mod.Annot): Result[Option[String]] = typeArgs(marker) match {
    case Nil => Right(None)
    case List(tpe: Type.Select) => Right(Some(if (tpe.syntax.startsWith("_root_.")) tpe.syntax else s"_root_.${tpe.syntax}"))
    case List(tpe) => Left(List(at(path, tpe,
      s"props type ${tpe.syntax} must be a fully qualified class name (for example _root_.pkg.${tpe.syntax})")))
    case _ => Left(List(at(path, marker, "@islands takes at most one type argument")))
  }

  private def baseType(tpe: Type): Type = tpe match {
    case applied: Type.Apply => applied.tpe
    case other => other
  }

  private def typeArgs(annotation: Mod.Annot): List[Type] = annotation.init.tpe match {
    case applied: Type.Apply => applied.argClause.values
    case _ => Nil
  }

  private def literalArgs(path: String, annotation: Mod.Annot, names: List[String]): Result[List[String]] =
    CodegenError.sequence(annotation.init.argClauses.flatMap(_.values).toList.zipWithIndex.map { case (arg, index) =>
      val value = arg match {
        case Term.Assign(Term.Name(name), rhs) if names.lift(index).contains(name) => Right(rhs)
        case Term.Assign(_, _) => Left(List(at(path, arg, s"annotation arguments must follow ${names.mkString(", ")}")))
        case other => Right(other)
      }
      value.flatMap {
        case Lit.String(text) => Right(text)
        case other => Left(List(at(path, other, "annotation arguments must be string literals")))
      }
    })

  private def outputPath(path: String, tree: Tree, value: String, extension: String): Result[String] = {
    if (!value.endsWith(extension)) Left(List(at(path, tree, s"output path $value must end with $extension")))
    else OutputPath.segments(value).left.map(reason => List(at(path, tree, reason))).map(_ => value)
  }

  private def routeConflicts(found: List[(String, Found.Route)]): List[CodegenError] = {
    val byPath = found.groupBy(_._2.file.toLowerCase(Locale.ROOT)).values.toList.flatMap { group =>
      val spellings = group.map(_._2.file).distinct.sorted
      if (spellings.size > 1) group.map { case (source, r) => at(source, r.tree, s"route paths differ only by case: ${spellings.mkString(", ")}") }
      else Nil
    }
    byPath ++ duplicates(found.map { case (source, r) => (source, r.tree, s"${r.method} ${r.file}") }, key => s"duplicate route method $key")
  }

  private def duplicates(items: List[(String, Tree, String)], message: String => String): List[CodegenError] =
    items.groupBy(_._3).toList.sortBy(_._1).flatMap { case (key, group) =>
      if (group.size > 1) group.map { case (source, tree, _) => at(source, tree, message(key)) } else Nil
    }
}
