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

  test("空・空白・コメント記号だけの生成マーカーは拒否し、手書きファイルを消さない") {
    withTempDir { root =>
      Files.writeString(root.resolve("manual.ts"), "export default manual;\n")
      List("", "   ", "//", " // ", "// generated\nsecond").foreach { blank =>
        val result = OutputWriter.write(List(GeneratedFile(root, "fresh.ts", s"$blank\n")), List(root), blank)
        assert(result.left.exists(_.exists(_.message.contains("generatedMarker"))), s"$blank: $result")
      }
      assertEquals(names(root), List("manual.ts"))
    }
  }

  test("ファイルとディレクトリの衝突は書き込みの前に拒否し、古い生成ファイルも消さない") {
    withTempDir { root =>
      Files.writeString(root.resolve("stale.ts"), generated)
      Files.writeString(root.resolve("plain"), "not a directory")
      Files.createDirectories(root.resolve("dir.ts"))
      val cases = List(
        List("a.ts" -> generated, "a.ts/b.ts" -> generated) -> "is also used as a directory",
        List("plain/b.ts" -> generated) -> "is not a directory",
        List("dir.ts" -> generated) -> "existing directory"
      )
      cases.foreach { case (files, message) =>
        val result = write(root, files*)
        assert(result.left.exists(_.exists(_.message.contains(message))), s"$files: $result")
      }
      assertEquals(names(root), List("dir.ts", "plain", "stale.ts"))
    }
  }

  test("書き込みに失敗したら誤りを返して古い生成ファイルを残し、直してから再実行すると収束する") {
    withTempDir { root =>
      Files.writeString(root.resolve("stale.ts"), generated)
      val locked = root.resolve("locked.ts")
      Files.writeString(locked, s"$marker\nold\n")
      locked.toFile.setWritable(false)
      assume(!Files.isWritable(locked), "cannot make a file read-only here")
      val failed = write(root, "locked.ts" -> generated)
      locked.toFile.setWritable(true)
      assert(failed.left.exists(_.exists(_.message.startsWith("cannot write file"))), failed)
      assert(Files.exists(root.resolve("stale.ts")), "書き込みが済むまで古い生成ファイルは消さない")
      assertEquals(write(root, "locked.ts" -> generated).map(_.render), Right("wrote 1, unchanged 0, deleted 1"))
      assertEquals(write(root, "locked.ts" -> generated).map(_.render), Right("wrote 0, unchanged 1, deleted 0"))
    }
  }

  private def symlink(link: Path, target: Path): Unit =
    try { Files.createSymbolicLink(link, target) }
    catch {
      case e @ (_: java.io.IOException | _: UnsupportedOperationException | _: SecurityException) =>
        assume(false, s"symbolic links are not available here: ${e.getMessage}")
    }

  private def junction(link: Path, target: Path): Unit = {
    assume(System.getProperty("os.name").toLowerCase.contains("windows"), "junctions exist only on Windows")
    val process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString, target.toString).redirectErrorStream(true).start()
    process.getInputStream.readAllBytes()
    assertEquals(process.waitFor(), 0, "mklink /J failed")
  }

  /** root と、その外のディレクトリ outside（生成マーカー付きのファイルを 1 つ置く）を用意する */
  private def withOutside(test: (Path, Path) => Unit): Unit =
    withTempDir { tmp =>
      val root = Files.createDirectories(tmp.resolve("root"))
      val outside = Files.createDirectories(tmp.resolve("outside"))
      Files.writeString(outside.resolve("target.ts"), generated)
      test(root, outside)
    }

  private def assertRefusesLink(result: CodegenError.Result[WriteSummary]): Unit =
    assert(result.left.exists(_.exists(_.message.contains("symbolic link or junction"))), result)

  test("親ディレクトリ・出力先・出力ルートがシンボリックリンクなら辿らずに拒否する") {
    withOutside { (root, outside) =>
      symlink(root.resolve("parent"), outside)
      assertRefusesLink(write(root, "parent/new.ts" -> generated))
      Files.delete(root.resolve("parent"))
      symlink(root.resolve("leaf.ts"), outside.resolve("target.ts"))
      assertRefusesLink(write(root, "leaf.ts" -> s"$marker\nreplaced\n"))
      Files.delete(root.resolve("leaf.ts"))
      symlink(root.resolve("dangling.ts"), outside.resolve("missing.ts"))
      assertRefusesLink(write(root, "dangling.ts" -> generated))
      val linkedRoot = root.getParent.resolve("linked-root")
      symlink(linkedRoot, outside)
      assertRefusesLink(OutputWriter.write(List(GeneratedFile(linkedRoot, "new.ts", generated)), Nil, marker))
      assertEquals(names(outside), List("target.ts"))
      assertEquals(Files.readString(outside.resolve("target.ts")), generated)
    }
  }

  test("出力ディレクトリの中にシンボリックリンクがあれば、古い生成ファイルを探すときも辿らずに拒否する") {
    withOutside { (root, outside) =>
      symlink(root.resolve("linked"), outside)
      assertRefusesLink(write(root, "fresh.ts" -> generated))
      assertEquals(names(outside), List("target.ts"))
      assertEquals(names(root), List("linked"))
    }
  }

  test("Windows のジャンクションも親ディレクトリ・出力ルート・古い生成ファイルの探索で拒否する") {
    withOutside { (root, outside) =>
      junction(root.resolve("joined"), outside)
      assertRefusesLink(write(root, "joined/new.ts" -> generated))
      assertRefusesLink(write(root, "fresh.ts" -> generated))
      val joinedRoot = root.getParent.resolve("joined-root")
      junction(joinedRoot, outside)
      assertRefusesLink(OutputWriter.write(List(GeneratedFile(joinedRoot, "new.ts", generated)), List(joinedRoot), marker))
      assertEquals(names(outside), List("target.ts"))
      assertEquals(names(root), List("joined"))
    }
  }
}
