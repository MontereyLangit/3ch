package io._3ch.common.golden

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.annotation.tailrec

/**
 * `tests/golden` 配下の JSON ファイルを読むための JVM 側実装。
 *
 * テスト専用のコード（`common/src/test/scala-jvm`）であり、`common/src/main` の
 * 「純粋関数のみ・I/O禁止」方針とは無関係（本番の Sjis/UnixCrypt/SaltChar/HexToBinary は
 * このファイルに依存しない）。
 *
 * sbt を実行するカレントディレクトリは通常リポジトリルートだが、確実性のため
 * 現在のディレクトリから上へ`tests/golden`が見つかるまで辿る。
 */
object GoldenFile {
  def readText(name: String): String = {
    val dir = findGoldenDir()
    val file = new File(dir, name)
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
  }

  @tailrec
  private def findGoldenDir(dir: File = new File(".").getAbsoluteFile, depth: Int = 0): File = {
    val candidate = new File(dir, "tests/golden")
    if (candidate.isDirectory) candidate
    else {
      val parent = dir.getParentFile
      if (parent == null) throw new IllegalStateException("tests/golden ディレクトリが見つからない")
      else if (depth + 1 < 8) findGoldenDir(parent, depth + 1)
      else throw new IllegalStateException("tests/golden ディレクトリが見つからない（8階層まで探索）")
    }
  }
}
