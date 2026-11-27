package io._3ch.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** DO の TS 型（app/scalajs-durable-objects.generated.d.ts）を生成する（sbt codegen/run）。改行コードだけの違いなら書き直さない */
object GenerateDurableObjectTypesMain {
  def main(args: Array[String]): Unit = {
    val projectRoot = args.toList match {
      case List("--project-root", root) => Paths.get(root).toAbsolutePath.normalize()
      case Nil => Paths.get(System.getProperty("user.dir")).toAbsolutePath
      case _ => throw new IllegalArgumentException("usage: GenerateDurableObjectTypesMain [--project-root <dir>]")
    }
    val durableRoot = projectRoot.resolve("bbs/src/main/scala/io/_3ch/bbs/durable")
    val output = projectRoot.resolve("app/scalajs-durable-objects.generated.d.ts")
    val changed = writeIfChanged(output, GenerateDurableObjectTypes.render(DurableObjectSources.read(durableRoot)))
    println(s"[codegen] Durable Object types: ${if (changed) "wrote" else "unchanged"} $output")
  }

  private def writeIfChanged(path: Path, content: String): Boolean = {
    def normalize(text: String): String = text.replace("\r\n", "\n")
    val unchanged = Files.isRegularFile(path) && normalize(Files.readString(path, StandardCharsets.UTF_8)) == normalize(content)
    if (!unchanged) {
      Files.createDirectories(path.getParent)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    }
    !unchanged
  }
}
