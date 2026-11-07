package io._3ch.bbs.application.admin

import io._3ch.bbs.application.admin.ports.{AdminFiles, AdminThreads, WriteCondition, WriteResult}
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleSet, ThreadCreationSettings}
import io._3ch.common.admin.{BoardSettingsSaved, ResView, RuleName, Saved, TextFile, ThreadAction, ThreadDetail, ThreadList, ThreadSummary}
import io._3ch.common.domain.BbsSettings

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 板設定・スレ操作・規制ルール・板一覧の管理操作。HTML の画面と API が共用する */
final class AdminService(files: AdminFiles, threads: AdminThreads) {
  import AdminService.*

  private def text(path: String, fallback: => String = ""): Future[String] = files.read(path).map(_.fold(fallback)(_.text))

  /** 板の head.txt と SETTING.TXT。無ければ空と既定の内容で、ETag は None */
  def boardTexts(bbs: String): Future[(TextFile, TextFile)] = for {
    head <- files.read(headPath(bbs))
    settings <- files.read(settingsPath(bbs))
  } yield (head.getOrElse(TextFile("", None)), settings.getOrElse(TextFile(defaultSettings(bbs), None)))

  /**
    * SETTING.TXT、head.txt の順に条件付きで保存する。スレ立て規制の設定が不正か SETTING.TXT が競合したら何も書かない。
    * head.txt だけが競合したら SETTING.TXT は保存したまま partial を返す
    */
  def saveBoardTexts(bbs: String, head: String, settings: String, settingsCondition: WriteCondition, headCondition: WriteCondition)
    : Future[Either[AdminFailure, BoardSettingsSaved]] =
    ThreadCreationSettings.parseText(settings) match {
      case Left(reason) => Future.successful(Left(AdminFailure.CreationSettings(reason)))
      case Right(_) => files.write(settingsPath(bbs), settings, settingsCondition).flatMap {
        case WriteResult.Conflict => Future.successful(Left(AdminFailure.Conflict(AdminFile.Settings)))
        case WriteResult.Written(settingsEtag) => files.write(headPath(bbs), head, headCondition).map {
          case WriteResult.Written(headEtag) => Right(BoardSettingsSaved(settingsEtag, headEtag, partial = false))
          case WriteResult.Conflict => Right(BoardSettingsSaved(settingsEtag, None, partial = true))
        }
      }
    }

  /** スレ立て規制の項目だけを置き換える。values は (キー, 値) の列で、読込後に他の操作で保存・作成されていれば書かない */
  def saveCreationLimits(bbs: String, values: List[(String, String)]): Future[Either[AdminFailure, Unit]] =
    if (values.exists((_, v) => v.contains('\r') || v.contains('\n'))) Future.successful(Left(AdminFailure.CreationLineBreak))
    else files.read(settingsPath(bbs)).flatMap { stored =>
      val current = stored.fold(defaultSettings(bbs))(_.text)
      val names = values.map(_._1).toSet
      val other = current.split("\\r?\\n", -1).filterNot(line => names.contains(line.takeWhile(_ != '=').trim))
      val settings = (other.filter(_.nonEmpty).toSeq ++ values.map((k, v) => s"$k=${v.trim}")).mkString("\n") + "\n"
      ThreadCreationSettings.parseText(settings) match {
        case Left(reason) => Future.successful(Left(AdminFailure.CreationSettings(reason)))
        case Right(_) =>
          val condition = AdminService.condition(stored.map(_.etag.getOrElse("")))
          files.write(settingsPath(bbs), settings, condition).map {
            case WriteResult.Written(_) => Right(())
            case WriteResult.Conflict => Left(AdminFailure.Conflict(AdminFile.Settings))
          }
      }
    }

  /** 公開中と削除・移動済みのスレ */
  def threadList(bbs: String): Future[ThreadList] = for {
    subject <- threads.subject(bbs)
    removed <- threads.removed(bbs)
  } yield ThreadList(bbs, subject.split("\n", -1).toList.filter(_.nonEmpty).map(line => ThreadSummary(line.split("\\.", -1)(0), line)), removed)

  /** スレの状態と dat・ログ。dat が無ければ ThreadMissing */
  def thread(bbs: String, key: String): Future[Either[AdminFailure, ThreadDetail]] = threads.inspect(bbs, key).map { r =>
    if (r.dat.isEmpty) Left(AdminFailure.ThreadMissing)
    else Right(ThreadDetail(bbs, key, r.hidden, r.stopped, r.movedTo, r.movingTo, ResView.fromDat(r.dat), r.dat, r.log, r.legacyLog, r.settings))
  }

  /** 旧データを取り込んでから Thread DO に操作を渡す。移動先の板名と key は先に検証する */
  def moderate(bbs: String, key: String, action: ThreadAction): Future[Either[AdminFailure, Unit]] = {
    val target = action match {
      case ThreadAction.Move(targetBoard, targetKey) =>
        validBoard(targetBoard, "targetBoard").flatMap(_ => validKey(targetKey, "targetKey")).map(_ => ())
      case _ => Right(())
    }
    target match {
      case Left(failure) => Future.successful(Left(failure))
      case Right(_) => threads.inspect(bbs, key).flatMap(_ => threads.moderate(bbs, key, action)).map(_.left.map(AdminFailure.ThreadRefused(_)))
    }
  }

  /** 規制ルールのファイル */
  def rules(name: RuleName): Future[TextFile] = files.read(name.file).map(_.getOrElse(TextFile("", None)))

  /** 規制ルールを検証して条件付きで保存する */
  def saveRules(name: RuleName, rules: String, condition: WriteCondition): Future[Either[AdminFailure, Saved]] = ModerationRuleSet.parse(rules) match {
    case Left(error) => Future.successful(Left(AdminFailure.Rules(error)))
    case Right(_) => saved(files.write(name.file, rules, condition), AdminFile.Rules)
  }

  /** bbsmenu.html */
  def menu(): Future[TextFile] = files.read(MenuPath).map(_.getOrElse(TextFile("", None)))

  def saveMenu(html: String, condition: WriteCondition): Future[Either[AdminFailure, Saved]] = saved(files.write(MenuPath, html, condition), AdminFile.Menu)

  private def saved(result: Future[WriteResult], file: AdminFile): Future[Either[AdminFailure, Saved]] = result.map {
    case WriteResult.Written(etag) => Right(Saved(etag))
    case WriteResult.Conflict => Left(AdminFailure.Conflict(file))
  }
}

object AdminService {
  val MenuPath = "bbsmenu.html"

  def settingsPath(bbs: String): String = s"$bbs/SETTING.TXT"
  def headPath(bbs: String): String = s"$bbs/head.txt"
  def defaultSettings(bbs: String): String = BbsSettings.defaultText.replace("<DIR_NAME>", bbs)

  /** 読込時の ETag による条件。None なら読込時にファイルが無かったので、無いときだけ作る */
  def condition(etag: Option[String]): WriteCondition = etag.fold(WriteCondition.Absent)(WriteCondition.Matches(_))

  /** 管理対象の板名。英数字・_・- の 1〜80 文字 */
  def validBoard(value: String, field: String = "bbs"): Either[AdminFailure, String] =
    Either.cond(value.matches("[a-zA-Z0-9_-]{1,80}"), value, AdminFailure.InvalidBoard(field))

  /** スレの key。10 桁の数字 */
  def validKey(value: String, field: String = "key"): Either[AdminFailure, String] =
    Either.cond(value.matches("[0-9]{10}"), value, AdminFailure.InvalidKey(field))
}
