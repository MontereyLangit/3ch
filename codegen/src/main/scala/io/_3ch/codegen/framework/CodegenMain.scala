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

  /**
   * 引数を解釈し、設定を確かめる。省いたキーは CodegenConfig.default の値にする
   * `server-sources` / `client-sources` はカンマ区切りか繰り返し、`banner` は 1 行ずつ繰り返す
   */
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
    def single(key: String): Option[String] = values.get(key).flatMap(_.headOption)
    def many(key: String): List[String] = values.getOrElse(key, Nil).flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)
    val projectRoot = single("project-root").fold(workingDirectory)(Paths.get(_)).toAbsolutePath.normalize()
    val required = CodegenError.sequence(List("scala-package", "island-bridge", "annotation-package").map { key =>
      single(key).map(key -> _).toRight(List(error(s"missing option --$key")))
    })
    val sources = CodegenError.sequence(List("server-sources", "client-sources").map { key =>
      val list = many(key)
      if (list.isEmpty) Left(List(error(s"missing option --$key"))) else Right(key -> list)
    })
    (required, sources) match {
      case (Right(r), Right(s)) if repeated.isEmpty =>
        val req = r.toMap
        val src = s.toMap
        val base = CodegenConfig.default(projectRoot, src("server-sources"), src("client-sources"), req("scala-package"), req("island-bridge"),
          req("annotation-package"))
        val config = base.copy(
          serverModule = single("server-module").getOrElse(base.serverModule),
          clientModule = single("client-module").getOrElse(base.clientModule),
          routeDir = single("route-dir").getOrElse(base.routeDir),
          islandDir = single("island-dir").getOrElse(base.islandDir),
          islandIdPrefix = single("island-id-prefix").getOrElse(base.islandIdPrefix),
          scalaOutput = single("scala-output").map(Paths.get(_)),
          scalaObject = single("scala-object").getOrElse(base.scalaObject),
          virtualModulePrefix = single("virtual-module-prefix").getOrElse(base.virtualModulePrefix),
          banner = values.getOrElse("banner", base.banner),
          generatedMarker = single("generated-marker").getOrElse(base.generatedMarker)
        )
        Codegen.validate(config).map(_ => command -> config)
      case _ => Left(repeated ++ required.left.getOrElse(Nil) ++ sources.left.getOrElse(Nil))
    }
  }
}
