package io._3ch.codegen.framework

import io._3ch.codegen.framework.CodegenError.Result

import java.nio.file.{Path, Paths}

/**
 * 設定を `--key value` の引数で受け取る汎用の入口
 * `app` で route / island の TS ファイル、`island-components` で SSR 用 Scala API を生成する。キーは docs/codegen.md を参照
 */
object CodegenMain {
  enum Command {
    case App, IslandComponents
  }

  private val Repeatable = Set("server-sources", "client-sources", "banner")
  private val Defaults = Map("server-module" -> "server", "client-module" -> "client", "virtual-module-prefix" -> "scalajs:")
  private val Keys = Set(
    "project-root", "server-sources", "client-sources", "server-module", "client-module", "route-dir", "island-dir",
    "island-id-prefix", "scala-output", "scala-package", "scala-object", "island-bridge", "annotation-package",
    "virtual-module-prefix", "banner", "generated-marker"
  )

  def main(args: Array[String]): Unit = {
    parse(args.toList) match {
      case Left(errors) =>
        errors.foreach(error => System.err.println(s"[codegen] error: ${error.render}"))
        throw new CodegenFailure(s"invalid arguments: ${errors.size} error(s)")
      case Right((Command.App, config)) => CodegenReport.exitOnError(Codegen.appFiles(config), "route / island files")
      case Right((Command.IslandComponents, config)) => CodegenReport.exitOnError(Codegen.islandComponents(config), "island components")
    }
  }

  /** 引数を解釈する。`server-sources` / `client-sources` はカンマ区切りか繰り返し、`banner` は 1 行ずつ繰り返す */
  def parse(args: List[String], workingDirectory: Path = Paths.get(System.getProperty("user.dir"))): Result[(Command, CodegenConfig)] = {
    def error(message: String) = CodegenError("<args>", None, message)
    val command = args.headOption match {
      case Some("app") => Right(Command.App)
      case Some("island-components") => Right(Command.IslandComponents)
      case other => Left(List(error(s"expected command app or island-components, found ${other.getOrElse("nothing")}")))
    }
    val pairs = args.drop(1).grouped(2).toList.map {
      case List(key, value) if key.startsWith("--") && Keys(key.drop(2)) => Right(key.drop(2) -> value)
      case List(key, _) => Left(List(error(s"unknown option $key")))
      case List(key) => Left(List(error(s"missing value for $key")))
      case _ => Left(List(error("invalid arguments")))
    }
    (command, CodegenError.sequence(pairs)) match {
      case (Left(a), Left(b)) => Left(a ++ b)
      case (Left(a), Right(_)) => Left(a)
      case (Right(_), Left(b)) => Left(b)
      case (Right(cmd), Right(parsed)) => toConfig(cmd, parsed.groupMap(_._1)(_._2), workingDirectory)
    }
  }

  private def toConfig(command: Command, values: Map[String, List[String]], workingDirectory: Path): Result[(Command, CodegenConfig)] = {
    def error(message: String) = CodegenError("<args>", None, message)
    val repeated = values.collect { case (key, list) if list.size > 1 && !Repeatable(key) => error(s"option --$key is given more than once") }.toList
    def one(key: String): Result[String] =
      values.get(key).flatMap(_.headOption).orElse(Defaults.get(key)).toRight(List(error(s"missing option --$key")))
    def many(key: String): List[String] = values.getOrElse(key, Nil).flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)
    val projectRoot = values.get("project-root").flatMap(_.headOption).fold(workingDirectory)(Paths.get(_)).toAbsolutePath.normalize()
    val required = CodegenError.sequence(List("server-module", "client-module", "route-dir", "island-dir", "island-id-prefix",
      "scala-package", "scala-object", "island-bridge", "annotation-package", "virtual-module-prefix", "generated-marker").map(key => one(key).map(key -> _)))
    val lists = CodegenError.sequence(List("server-sources", "client-sources", "banner").map { key =>
      val list = if (key == "banner") values.getOrElse(key, Nil) else many(key)
      if (list.isEmpty) Left(List(error(s"missing option --$key"))) else Right(key -> list)
    })
    (required, lists) match {
      case (Right(single), Right(multi)) if repeated.isEmpty =>
        val s = single.toMap
        val m = multi.toMap
        Right(command -> CodegenConfig(
          projectRoot = projectRoot,
          serverSources = m("server-sources"),
          clientSources = m("client-sources"),
          serverModule = s("server-module"),
          clientModule = s("client-module"),
          routeDir = s("route-dir"),
          islandDir = s("island-dir"),
          islandIdPrefix = s("island-id-prefix"),
          scalaOutput = values.get("scala-output").flatMap(_.headOption).map(Paths.get(_)),
          scalaPackage = s("scala-package"),
          scalaObject = s("scala-object"),
          islandBridge = s("island-bridge"),
          annotationPackage = s("annotation-package"),
          virtualModulePrefix = s("virtual-module-prefix"),
          banner = m("banner"),
          generatedMarker = s("generated-marker")
        ))
      case _ => Left(repeated ++ required.left.getOrElse(Nil) ++ lists.left.getOrElse(Nil))
    }
  }
}
