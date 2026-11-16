package io._3ch.codegen.framework

import io._3ch.codegen.framework.CodegenError.Result

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** 出力ファイル 1 件。relativePath は root からの `/` 区切りの相対パス */
final case class GeneratedFile(root: Path, relativePath: String, content: String)

final case class WriteSummary(written: List[Path], unchanged: List[Path], deleted: List[Path]) {
  def render: String = s"wrote ${written.size}, unchanged ${unchanged.size}, deleted ${deleted.size}"
}

/**
 * 生成ファイルを書き出す。誤りはすべて書き込みの前に集めて返す
 * managed 配下にある生成ファイルのうち今回出力しないものは消す。生成マーカーの無いファイルは上書きも削除もしない
 */
object OutputWriter {
  private val GeneratedExtensions = List(".ts", ".tsx", ".scala")

  def write(files: List[GeneratedFile], managed: List[Path], marker: String): Result[WriteSummary] =
    plan(files, managed.map(_.toAbsolutePath.normalize()), marker).map { case (resolved, stale) =>
      stale.foreach(Files.delete)
      val (unchanged, changed) = resolved.partition { case (path, content) => sameContent(path, content) }
      changed.foreach { case (path, content) =>
        Files.createDirectories(path.getParent)
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      }
      WriteSummary(changed.map(_._1), unchanged.map(_._1), stale)
    }

  /** 内容が同じ（改行コードの違いは無視する）なら書かない。生成マーカーは確かめない */
  def writeIfChanged(path: Path, content: String): Boolean = {
    val changed = !sameContent(path, content)
    if (changed) {
      Files.createDirectories(path.getParent)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    }
    changed
  }

  private def plan(files: List[GeneratedFile], managed: List[Path], marker: String): Result[(List[(Path, String)], List[Path])] = {
    val resolved = CodegenError.sequence(files.map { file =>
      OutputPath.resolve(file.root, file.relativePath)
        .map(path => (file, path))
        .left.map(reason => List(CodegenError(file.root.toString, None, reason)))
    })
    resolved.flatMap { outputs =>
      val outputKeys = outputs.map(_._2.toString).toSet
      val sameKey = outputs.groupBy(_._2.toString.toLowerCase(Locale.ROOT)).values.toList.collect {
        case group if group.size > 1 =>
          CodegenError(group.head._2.toString, None, s"outputs collide (case-insensitive): ${group.map(_._2).mkString(", ")}")
      }
      val existing = outputs.flatMap { case (file, path) =>
        val handwritten = Option.when(Files.exists(path) && !isGenerated(path, marker))(
          CodegenError(path.toString, None, "refusing to overwrite a file without the generated marker"))
        handwritten.toList ++ caseConflicts(file.root, path, outputKeys, managed, marker)
      }
      val stale = managed.filter(Files.isDirectory(_)).flatMap(walkFiles).filter { path =>
        !outputKeys(path.toString) && isGenerated(path, marker)
      }
      val errors = sameKey ++ existing
      if (errors.nonEmpty) Left(errors.distinct) else Right((outputs.map { case (file, path) => path -> file.content }, stale))
    }
  }

  /** 出力パスの各階層で、名前の大文字小文字だけが違う既存のファイル・ディレクトリを探す。消してよい古い生成ファイルは除く */
  private def caseConflicts(root: Path, path: Path, outputKeys: Set[String], managed: List[Path], marker: String): List[CodegenError] = {
    val base = root.toAbsolutePath.normalize()
    val names = base.relativize(path).iterator().asScala.map(_.toString).toList
    names.indices.toList.flatMap { depth =>
      val parent = names.take(depth).foldLeft(base)(_.resolve(_))
      val name = names(depth)
      val siblings = if (Files.isDirectory(parent)) Using.resource(Files.list(parent))(_.iterator().asScala.toList) else Nil
      siblings.filter { sibling =>
        val siblingName = sibling.getFileName.toString
        siblingName != name && siblingName.equalsIgnoreCase(name)
      }.filterNot { sibling =>
        val isLeaf = depth == names.size - 1
        isLeaf && !outputKeys(sibling.toString) && managed.exists(sibling.startsWith(_)) && Files.isRegularFile(sibling) && isGenerated(sibling, marker)
      }.map(sibling => CodegenError(path.toString, None, s"differs only by case from existing $sibling"))
    }
  }

  private def walkFiles(root: Path): List[Path] =
    Using.resource(Files.walk(root)) { paths =>
      paths.iterator().asScala.filter(p => Files.isRegularFile(p) && !Files.isSymbolicLink(p)).map(_.toAbsolutePath.normalize()).toList.sortBy(_.toString)
    }

  private def isGenerated(path: Path, marker: String): Boolean = {
    val name = path.getFileName.toString
    GeneratedExtensions.exists(name.endsWith) && Files.isRegularFile(path) &&
      Files.readString(path, StandardCharsets.UTF_8).startsWith(marker)
  }

  private def sameContent(path: Path, content: String): Boolean =
    Files.isRegularFile(path) && normalize(Files.readString(path, StandardCharsets.UTF_8)) == normalize(content)

  private def normalize(text: String): String = text.replace("\r\n", "\n")
}
