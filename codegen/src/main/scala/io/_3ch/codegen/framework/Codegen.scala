package io._3ch.codegen.framework

import io._3ch.codegen.framework.CodegenError.Result

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.Try

/** 設定に従ってソースを読み、route / island の TS ファイルと SSR 用 Scala API を生成する */
object Codegen {

  /** route と island の TS ファイルを書く。古い生成ファイルは消す */
  def appFiles(config: CodegenConfig): Result[WriteSummary] =
    appOutputs(config).flatMap(files => OutputWriter.write(files, List(config.routeRoot, config.islandRoot), config.generatedMarker))

  /** route と island の TS ファイルの内容 */
  def appOutputs(config: CodegenConfig): Result[List[GeneratedFile]] =
    for {
      _ <- validate(config)
      routes <- read(config, config.serverSources).flatMap(AnnotationScanner.routes(_, config))
      islands <- read(config, config.clientSources).flatMap(AnnotationScanner.islands(_, config))
    } yield routes.map(entry => GeneratedFile(config.routeRoot, entry.filePath, AppFileRenderer.route(entry, config))) ++
      islands.map(entry => GeneratedFile(config.islandRoot, entry.filePath, AppFileRenderer.island(entry, config)))

  /** SSR 用 Scala API。入力は client のソースだけ */
  def islandComponents(config: CodegenConfig): Result[WriteSummary] =
    for {
      _ <- validate(config)
      output <- config.scalaOutput.toRight(List(CodegenError("<config>", None, "scalaOutput is not set")))
      islands <- read(config, config.clientSources).flatMap(AnnotationScanner.islands(_, config))
      absolute = config.projectRoot.resolve(output).toAbsolutePath.normalize()
      file = GeneratedFile(absolute.getParent, absolute.getFileName.toString, AppFileRenderer.islandComponents(islands, config))
      summary <- OutputWriter.write(List(file), Nil, config.generatedMarker)
    } yield summary

  private def validate(config: CodegenConfig): Result[Unit] = {
    val identifier = "[A-Za-z_][A-Za-z0-9_]*"
    val qualified = s"$identifier(\\.$identifier)*"
    CodegenError.sequence(List(
      CodegenError.check(config.banner.headOption.exists(_.startsWith(config.generatedMarker)),
        CodegenError("<config>", None, "the first banner line must start with generatedMarker")),
      CodegenError.check(config.banner.forall(line => line.startsWith("//") && !line.contains('\n')),
        CodegenError("<config>", None, "banner lines must be single-line // comments")),
      CodegenError.check(config.scalaPackage.matches(qualified), CodegenError("<config>", None, s"invalid scalaPackage ${config.scalaPackage}")),
      CodegenError.check(config.scalaObject.matches(identifier), CodegenError("<config>", None, s"invalid scalaObject ${config.scalaObject}")),
      CodegenError.check(config.islandBridge.matches(qualified), CodegenError("<config>", None, s"invalid islandBridge ${config.islandBridge}")),
      CodegenError.check(config.annotationPackage.matches(qualified), CodegenError("<config>", None, s"invalid annotationPackage ${config.annotationPackage}")),
      CodegenError.check(config.serverModule != config.clientModule, CodegenError("<config>", None, "serverModule and clientModule must differ"))
    )).map(_ => ())
  }

  private def read(config: CodegenConfig, sources: List[String]): Result[List[(String, String)]] =
    CodegenError.sequence(sources.map { source =>
      Try(Files.readString(config.projectRoot.resolve(source), StandardCharsets.UTF_8)).toEither
        .map(text => source -> text)
        .left.map(error => List(CodegenError(source, None, s"cannot read source: ${error.getMessage}")))
    })
}
