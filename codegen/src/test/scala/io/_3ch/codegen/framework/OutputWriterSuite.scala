package io._3ch.codegen.framework

import io._3ch.codegen.framework.TestConfig.withTempDir
import munit.FunSuite

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class OutputWriterSuite extends FunSuite {
  private val marker = "// generated-by-test"
  private val generated = s"$marker\nexport default 1;\n"

  private def names(root: Path): List[String] =
    Using.resource(Files.list(root))(_.iterator().asScala.map(_.getFileName.toString).toList.sorted)

  private def write(root: Path, files: (String, String)*) =
    OutputWriter.write(files.toList.map { case (path, content) => GeneratedFile(root, path, content) }, List(root), marker)

  test("再生成は古い生成ファイルを削除し、手書きファイルは残し、件数を返す") {
    withTempDir { root =>
      Files.writeString(root.resolve("stale.ts"), generated)
      Files.writeString(root.resolve("index.ts"), "export default manual;\n")
      Files.writeString(root.resolve("notes.md"), s"$marker\n")
      val summary = write(root, "fresh.ts" -> generated).toOption.get
      assert(!Files.exists(root.resolve("stale.ts")))
      assertEquals(Files.readString(root.resolve("index.ts")), "export default manual;\n")
      assert(Files.exists(root.resolve("notes.md")), "生成対象の拡張子でないファイルは消さない")
      assertEquals(Files.readString(root.resolve("fresh.ts")), generated)
      assertEquals((summary.written.size, summary.unchanged.size, summary.deleted.size), (1, 0, 1))
    }
  }

  test("内容が同じなら書き直さず更新時刻も変えない。改行コードの違いは同じ内容とみなす") {
    withTempDir { root =>
      val path = root.resolve("same.ts")
      val crlf = root.resolve("crlf.ts")
      Files.writeString(path, generated)
      Files.writeString(crlf, generated.replace("\n", "\r\n"))
      val old = FileTime.fromMillis(1_000_000_000_000L)
      Files.setLastModifiedTime(path, old)
      Files.setLastModifiedTime(crlf, old)
      val summary = write(root, "same.ts" -> generated, "crlf.ts" -> generated).toOption.get
      assertEquals(summary.unchanged.size, 2)
      assertEquals(summary.written, Nil)
      assertEquals(Files.getLastModifiedTime(path), old)
      assertEquals(Files.getLastModifiedTime(crlf), old)
    }
  }

  test("手書きファイルとの衝突は削除・書き込みの前に失敗する") {
    withTempDir { root =>
      Files.writeString(root.resolve("stale.ts"), generated)
      Files.writeString(root.resolve("index.ts"), "handwritten")
      assert(write(root, "index.ts" -> generated).isLeft)
      assertEquals(Files.readString(root.resolve("index.ts")), "handwritten")
      assert(Files.exists(root.resolve("stale.ts")))
    }
  }

  test("大文字小文字だけ違うパスは、出力どうしでも既存の手書きファイル・ディレクトリとの間でも拒否する") {
    withTempDir { root =>
      assert(write(root, "a.ts" -> generated, "A.ts" -> generated).isLeft)
      Files.writeString(root.resolve("index.ts"), "handwritten")
      Files.createDirectories(root.resolve("auth"))
      Files.writeString(root.resolve("auth/manual.ts"), "handwritten")
      assert(write(root, "Index.ts" -> generated).isLeft)
      assert(write(root, "Auth/page.ts" -> generated).isLeft)
      assertEquals(Files.readString(root.resolve("index.ts")), "handwritten")
    }
  }

  test("大文字小文字だけ違う古い生成ファイルは消して新しい名前で書く") {
    withTempDir { root =>
      Files.writeString(root.resolve("page.ts"), generated)
      write(root, "Page.ts" -> generated).toOption.get
      assertEquals(names(root), List("Page.ts"))
    }
  }

  test("出力ルートの外や予約名へのパスは書く前に拒否する") {
    withTempDir { root =>
      List("../x.ts", "a/../../x.ts", "/x.ts", "C:/x.ts", "a\\x.ts", "NUL.ts", "aux/x.ts", "x.ts.", "x .ts", "").foreach { path =>
        assert(write(root, path -> generated).isLeft, path)
      }
      assertEquals(names(root), Nil)
    }
  }

  test("生成マーカーの無い既存の出力先は上書きしない。以前のバナーでもマーカーが同じなら置き換える") {
    withTempDir { root =>
      Files.writeString(root.resolve("legacy.ts"), s"$marker (old tool)\nold\n")
      write(root, "legacy.ts" -> generated).toOption.get
      assertEquals(Files.readString(root.resolve("legacy.ts")), generated)
    }
  }
}
