package io._3ch.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.meta.*

/** DO の Scala ソースを読み、`@JSExportTopLevel("名前", "server")` 付きクラスを取り出す。TS 型生成と Scala Stub 生成が共用する。 */
object DurableObjectSources {

  /** `dir` 配下の `.scala` をパス順に読む（パス, 本文）。 */
  def read(dir: Path): Seq[(String, String)] = {
    val files = Files.walk(dir)
    try {
      files.iterator().asScala.filter(_.toString.endsWith(".scala")).toList.sortBy(_.toString).map { path =>
        path.toString -> Files.readString(path, StandardCharsets.UTF_8)
      }
    } finally {
      files.close()
    }
  }

  def parse(path: String, source: String): Source =
    dialects.Scala3(Input.VirtualFile(path, source)).parse[Source].get

  /** `server` モジュールへ export された DO クラスを (export 名, クラス) で返す。他モジュールの export は無視する。 */
  def serverExports(path: String, tree: Source): Seq[(String, Defn.Class)] =
    tree.collect { case cls: Defn.Class => cls }.flatMap { cls =>
      cls.mods.collect {
        case annotation: Mod.Annot if Set("JSExportTopLevel", "scala.scalajs.js.annotation.JSExportTopLevel")(
            annotation.init.tpe.syntax
          ) => annotation
      }.flatMap { annotation =>
        annotation.init.argClauses.flatMap(_.values) match {
          case List(Lit.String(name), Lit.String("server")) =>
            require(cls.templ.inits.exists(parent => Set(
              "DurableObject", "io._3ch.bbs.external.cloudflare.DurableObject"
            )(parent.tpe.syntax)), s"$path: exported DO must directly extend DurableObject")
            require(cls.tparamClause.values.isEmpty, s"$path: generic DO classes are not supported")
            Some(name -> cls)
          case List(_, Lit.String(module)) if module != "server" => None
          case _ => throw new IllegalArgumentException(s"$path: expected literal export name and module")
        }
      }
    }
}
