package io._3ch.codegen.framework

import scala.meta.{Position, Tree}

/** codegen の入力・出力の誤り。line は 1 始まりで、位置が分からないときは None */
final case class CodegenError(source: String, line: Option[Int], message: String) {
  def render: String = line.fold(s"$source: $message")(n => s"$source:$n: $message")
}

object CodegenError {
  type Result[A] = Either[List[CodegenError], A]

  def at(source: String, tree: Tree, message: String): CodegenError = position(source, tree.pos, message)

  def position(source: String, pos: Position, message: String): CodegenError =
    CodegenError(source, Option.when(pos != Position.None)(pos.startLine + 1), message)

  /** すべての誤りを集め、誤りが無ければ値の一覧を返す */
  def sequence[A](results: List[Result[A]]): Result[List[A]] = {
    val errors = results.collect { case Left(e) => e }.flatten
    if (errors.nonEmpty) Left(errors) else Right(results.collect { case Right(value) => value })
  }

  def check(condition: Boolean, error: => CodegenError): Result[Unit] =
    if (condition) Right(()) else Left(List(error))
}
