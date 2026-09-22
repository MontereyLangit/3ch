package ch3s.common.golden

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * `tests/golden` 配下の JSON ファイルを読むための JVM 側実装。
 *
 * テスト専用のコード（`common/src/test/scala-jvm`）であり、`common/src/main` の
 * 「純粋関数のみ・I/O禁止」方針とは無関係（本番の Sjis/Md5/UnixCrypt/SaltChar/HexToBinary は
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

  private def findGoldenDir(): File = {
    var dir = new File(".").getAbsoluteFile
    var depth = 0
    while (depth < 8) {
      val candidate = new File(dir, "tests/golden")
      if (candidate.isDirectory) return candidate
      dir = dir.getParentFile
      if (dir == null) throw new IllegalStateException("tests/golden ディレクトリが見つからない")
      depth += 1
    }
    throw new IllegalStateException("tests/golden ディレクトリが見つからない（8階層まで探索）")
  }
}
