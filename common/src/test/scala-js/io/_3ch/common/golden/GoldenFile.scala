package io._3ch.common.golden

import scala.annotation.tailrec
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/**
 * `tests/golden` 配下の JSON ファイルを読むための Scala.js(Node) 側実装。
 *
 * テスト専用のコード（`common/src/test/scala-js`）であり、`common/src/main` の
 * 「純粋関数のみ・I/O禁止」方針とは無関係。`common`のテストはNode上のScala.jsとして
 * 実行される（sbt-scalajsの既定のTest実行環境）ため、Node組み込みの`node:fs`を
 * 直接importして使う。
 */
@js.native
@JSImport("node:fs", JSImport.Namespace)
private object NodeFs extends js.Object {
  def readFileSync(path: String, encoding: String): String = js.native
  def existsSync(path: String): Boolean = js.native
  def statSync(path: String): js.Dynamic = js.native
}

@js.native
@JSImport("node:path", JSImport.Namespace)
private object NodePath extends js.Object {
  def join(paths: String*): String = js.native
  def resolve(paths: String*): String = js.native
}

object GoldenFile {
  def readText(name: String): String = {
    val dir = findGoldenDir()
    NodeFs.readFileSync(NodePath.join(dir, name), "utf8")
  }

  @tailrec
  private def findGoldenDir(dir: String = NodePath.resolve("."), depth: Int = 0): String = {
    val candidate = NodePath.join(dir, "tests", "golden")
    if (NodeFs.existsSync(candidate) && NodeFs.statSync(candidate).isDirectory().asInstanceOf[Boolean]) candidate
    else {
      val resolvedParent = NodePath.resolve(NodePath.join(dir, ".."))
      if (resolvedParent == dir) throw new IllegalStateException("tests/golden ディレクトリが見つからない")
      else if (depth + 1 < 8) findGoldenDir(resolvedParent, depth + 1)
      else throw new IllegalStateException("tests/golden ディレクトリが見つからない（8階層まで探索）")
    }
  }
}
