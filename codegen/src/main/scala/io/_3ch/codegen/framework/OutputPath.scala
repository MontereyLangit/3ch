package io._3ch.codegen.framework

import java.nio.file.Path
import java.util.Locale

/** 生成ファイルの相対パスの規則。どの OS でも出力ルートの外や予約名に書かないようにする */
object OutputPath {
  private val SegmentPattern = "[A-Za-z0-9_.$\\[\\]-]+"
  private val Reserved = Set("CON", "PRN", "AUX", "NUL") ++ (1 to 9).flatMap(n => List(s"COM$n", s"LPT$n"))

  /** `/` 区切りの相対パスを区切りごとに確かめる。誤りなら理由を返す */
  def segments(relative: String): Either[String, List[String]] = {
    val parts = relative.split("/", -1).toList
    parts.collectFirst(Function.unlift(problem)) match {
      case Some(reason) => Left(s"unsafe output path $relative: $reason")
      case None => Right(parts)
    }
  }

  private def problem(segment: String): Option[String] = {
    if (segment.isEmpty) Some("empty segment")
    else if (segment == "." || segment == "..") Some(s"relative segment $segment")
    else if (!segment.matches(SegmentPattern)) Some(s"unsupported characters in $segment")
    else if (segment.endsWith(".")) Some(s"trailing dot in $segment")
    else if (Reserved(segment.takeWhile(_ != '.').toUpperCase(Locale.ROOT))) Some(s"reserved device name $segment")
    else None
  }

  /** root 配下へ解決する。正規化したパスが root の外に出るなら誤り */
  def resolve(root: Path, relative: String): Either[String, Path] = {
    segments(relative).flatMap { parts =>
      val base = root.toAbsolutePath.normalize()
      val path = parts.foldLeft(base)(_.resolve(_)).normalize()
      if (path.startsWith(base) && path != base) Right(path) else Left(s"output path $relative escapes $base")
    }
  }
}
