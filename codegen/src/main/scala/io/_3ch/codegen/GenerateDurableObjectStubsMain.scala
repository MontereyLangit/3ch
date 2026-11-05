package io._3ch.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** `bbs / Compile / sourceGenerators` から呼ばれる。引数は DO ソースディレクトリと出力ファイル。内容が同じなら書き直さない。 */
object GenerateDurableObjectStubsMain {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "usage: GenerateDurableObjectStubsMain <durable source dir> <output file>")
    val content = GenerateDurableObjectStubs.render(DurableObjectSources.read(Paths.get(args(0))))
    val output = Paths.get(args(1))
    val unchanged = Files.exists(output) && Files.readString(output, StandardCharsets.UTF_8) == content
    if !unchanged then {
      Files.createDirectories(output.getParent)
      Files.write(output, content.getBytes(StandardCharsets.UTF_8))
      println(s"[codegen] wrote $output")
    }
  }
}
