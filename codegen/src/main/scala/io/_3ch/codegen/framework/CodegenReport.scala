package io._3ch.codegen.framework

/** 生成の失敗。sbt の run は同じ JVM で動くので System.exit せず、この例外で非ゼロ終了にする */
final class CodegenFailure(message: String) extends RuntimeException(message, null, false, false)

/** main 向けに結果をログへ出す */
object CodegenReport {
  def exitOnError(result: CodegenError.Result[WriteSummary], label: String): Unit = result match {
    case Right(summary) =>
      (summary.written.map(p => s"wrote $p") ++ summary.deleted.map(p => s"deleted $p")).foreach(line => println(s"[codegen] $line"))
      println(s"[codegen] $label: ${summary.render}")
    case Left(errors) =>
      errors.foreach(error => System.err.println(s"[codegen] error: ${error.render}"))
      throw new CodegenFailure(s"$label: ${errors.size} error(s)")
  }
}
