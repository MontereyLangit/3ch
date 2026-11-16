package io._3ch.codegen.framework

import io._3ch.codegen.framework.CodegenError.Result

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, LinkOption, Path}
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** 出力ファイル 1 件。relativePath は root からの `/` 区切りの相対パス */
final case class GeneratedFile(root: Path, relativePath: String, content: String)

final case class WriteSummary(written: List[Path], unchanged: List[Path], deleted: List[Path]) {
  def render: String = s"wrote ${written.size}, unchanged ${unchanged.size}, deleted ${deleted.size}"
}

/**
 * 生成ファイルを書き出す。設定・パス・既存ファイルとの衝突の誤りは書き込みの前に集めて返し、I/O の失敗も例外にせず返す
 * managed 配下にある生成ファイルのうち今回出力しないものは、書き込みが済んでから消す。生成マーカーの無いファイルは上書きも削除もしない
 * シンボリックリンクとジャンクションは辿らずに誤りにする
 */
object OutputWriter {
  private val GeneratedExtensions = List(".ts", ".tsx", ".scala")

  /** 書き込みの計画。deleteFirst は出力と大文字小文字だけ違う古い生成ファイルで、同じ実体を上書きしないよう先に消す */
  private final case class Plan(changed: List[(Path, String)], unchanged: List[Path], deleteFirst: List[Path], deleteAfter: List[Path])

  /** 生成マーカーとして使えない値なら理由を返す。空や `//` だけのマーカーはどのファイルにも当てはまってしまう */
  def markerProblem(marker: String): Option[String] = {
    if (marker.trim.isEmpty) Some("generatedMarker must not be empty or blank")
    else if (marker.exists(c => c == '\n' || c == '\r')) Some("generatedMarker must be a single line")
    else if (marker.trim.stripPrefix("//").trim.isEmpty) Some("generatedMarker needs text after the comment prefix")
    else None
  }

  def write(files: List[GeneratedFile], managed: List[Path], marker: String): Result[WriteSummary] =
    for {
      _ <- markerProblem(marker).toLeft(()).left.map(reason => List(CodegenError("<config>", None, reason)))
      planned <- attempt("<output>", "read existing files")(plan(files, managed.map(_.toAbsolutePath.normalize()), marker)).flatten
      summary <- execute(planned)
    } yield summary

  /** 内容が同じ（改行コードの違いは無視する）なら書かない。生成マーカーは確かめない */
  def writeIfChanged(path: Path, content: String): Boolean = {
    val changed = !sameContent(path, content)
    if (changed) {
      Files.createDirectories(path.getParent)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    }
    changed
  }

  /** 先に消す古いファイル → 書き込み → 残りの古いファイルの削除、の順に行う。途中で失敗しても再実行で収束する */
  private def execute(plan: Plan): Result[WriteSummary] = {
    def each[A](items: List[A], action: String)(path: A => Path)(run: A => Unit): Result[Unit] =
      items.foldLeft[Result[Unit]](Right(())) { (done, item) => done.flatMap(_ => attempt(path(item).toString, action)(run(item))) }
    for {
      _ <- each(plan.deleteFirst, "delete stale file")(identity)(Files.delete)
      _ <- each(plan.changed, "write file")(_._1) { case (path, content) =>
        Files.createDirectories(path.getParent)
        Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      }
      _ <- each(plan.deleteAfter, "delete stale file")(identity)(Files.delete)
    } yield WriteSummary(plan.changed.map(_._1), plan.unchanged, plan.deleteFirst ++ plan.deleteAfter)
  }

  private def plan(files: List[GeneratedFile], managed: List[Path], marker: String): Result[Plan] = {
    val resolved = CodegenError.sequence(files.map { file =>
      OutputPath.resolve(file.root, file.relativePath)
        .map(path => (file, path))
        .left.map(reason => List(CodegenError(file.root.toString, None, reason)))
    })
    for {
      outputs <- resolved
      _ <- structure(outputs)
      walked <- CodegenError.sequence(managed.map(managedFiles))
      result <- contents(outputs, walked.flatten, managed, marker)
    } yield result
  }

  /** リンク・ファイルとディレクトリの衝突を、既存ファイルを読む前に確かめる */
  private def structure(outputs: List[(GeneratedFile, Path)]): Result[Unit] = {
    val links = outputs.flatMap { case (file, path) => chain(file.root, path) }.distinct.collect {
      case p if exists(p) && isLink(p) => CodegenError(p.toString, None, "refusing to follow a symbolic link or junction")
    }
    val types = outputs.flatMap { case (file, path) =>
      val parents = chain(file.root, path).init.collect {
        case p if exists(p) && !isLink(p) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) =>
          CodegenError(path.toString, None, s"parent $p is not a directory")
      }
      val leaf = Option.when(exists(path) && !isLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))(
        CodegenError(path.toString, None, "output path is an existing directory"))
      parents ++ leaf
    }
    val keys = outputs.map { case (_, path) => path -> path.toString.toLowerCase(Locale.ROOT) }
    val nested = for {
      (parent, parentKey) <- keys
      (child, childKey) <- keys
      if childKey.startsWith(s"$parentKey${java.io.File.separator}")
    } yield CodegenError(child.toString, None, s"output $parent is also used as a directory")
    val errors = (links ++ types ++ nested).distinct
    if (errors.nonEmpty) Left(errors) else Right(())
  }

  private def contents(outputs: List[(GeneratedFile, Path)], walked: List[Path], managed: List[Path], marker: String): Result[Plan] = {
    val outputKeys = outputs.map(_._2.toString).toSet
    val sameKey = outputs.groupBy(_._2.toString.toLowerCase(Locale.ROOT)).values.toList.collect {
      case group if group.sizeIs > 1 =>
        CodegenError(group.head._2.toString, None, s"outputs collide (case-insensitive): ${group.map(_._2).mkString(", ")}")
    }
    val existing = outputs.flatMap { case (file, path) =>
      val handwritten = Option.when(Files.exists(path) && !isGenerated(path, marker))(
        CodegenError(path.toString, None, "refusing to overwrite a file without the generated marker"))
      handwritten.toList ++ caseConflicts(file.root, path, outputKeys, managed, marker)
    }
    val errors = sameKey ++ existing
    if (errors.nonEmpty) Left(errors.distinct)
    else {
      val stale = walked.filter(path => !outputKeys(path.toString) && isGenerated(path, marker))
      def lower(path: Path): String = path.toString.toLowerCase(Locale.ROOT)
      val lowerOutputs = outputKeys.map(_.toLowerCase(Locale.ROOT))
      val (deleteFirst, deleteAfter) = stale.partition(path => lowerOutputs(lower(path)))
      val renamed = deleteFirst.map(lower).toSet
      val (unchanged, changed) = outputs.map { case (file, path) => path -> file.content }.partition { case (path, content) =>
        !renamed(lower(path)) && sameContent(path, content)
      }
      Right(Plan(changed, unchanged.map(_._1), deleteFirst, deleteAfter))
    }
  }

  /** root から path までの各階層（root と path を含む） */
  private def chain(root: Path, path: Path): List[Path] = {
    val base = root.toAbsolutePath.normalize()
    base.relativize(path).iterator().asScala.toList.scanLeft(base)(_.resolve(_))
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
        isLeaf && !outputKeys(sibling.toString) && managed.exists(sibling.startsWith) && Files.isRegularFile(sibling) && isGenerated(sibling, marker)
      }.map(sibling => CodegenError(path.toString, None, s"differs only by case from existing $sibling"))
    }
  }

  /** managed 配下の通常ファイル。リンクやジャンクションがあれば辿らずに誤りにする */
  private def managedFiles(root: Path): Result[List[Path]] = {
    def walk(dir: Path): Result[List[Path]] =
      attempt(dir.toString, "list directory")(Using.resource(Files.list(dir))(_.iterator().asScala.toList.sortBy(_.toString))).flatMap { children =>
        CodegenError.sequence(children.map { child =>
          if (isLink(child)) Left(List(CodegenError(child.toString, None, "refusing to follow a symbolic link or junction")))
          else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) walk(child)
          else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) Right(List(child.toAbsolutePath.normalize()))
          else Right(Nil)
        }).map(_.flatten)
      }
    if (!exists(root)) Right(Nil)
    else if (isLink(root)) Left(List(CodegenError(root.toString, None, "refusing to follow a symbolic link or junction")))
    else if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) walk(root)
    else Right(Nil)
  }

  private def exists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

  /** シンボリックリンク、または Windows のジャンクションなどの再解析ポイント（Java では isOther になる） */
  private def isLink(path: Path): Boolean = {
    val attributes = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    attributes.isSymbolicLink || attributes.isOther
  }

  private def isGenerated(path: Path, marker: String): Boolean = {
    val name = path.getFileName.toString
    GeneratedExtensions.exists(name.endsWith) && Files.isRegularFile(path) &&
      Files.readString(path, StandardCharsets.UTF_8).startsWith(marker)
  }

  private def sameContent(path: Path, content: String): Boolean =
    Files.isRegularFile(path) && normalize(Files.readString(path, StandardCharsets.UTF_8)) == normalize(content)

  private def normalize(text: String): String = text.replace("\r\n", "\n")

  private def attempt[A](source: String, action: String)(body: => A): Result[A] =
    Try(body).toEither.left.map(error => List(CodegenError(source, None, s"cannot $action: ${error.getMessage}")))
}
