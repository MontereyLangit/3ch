package io._3ch.common.admin

import io._3ch.common.text.JsText

/** 管理 API のエラーの種類。value は応答の code */
enum AdminErrorCode(val value: String, val status: Int) {
  case Unauthorized extends AdminErrorCode("admin:unauthorized", 401)
  case Unavailable extends AdminErrorCode("admin:unavailable", 503)
  case ContentType extends AdminErrorCode("admin:content-type", 415)
  case Method extends AdminErrorCode("admin:method", 405)
  case Invalid extends AdminErrorCode("admin:invalid", 400)
  case Conflict extends AdminErrorCode("admin:conflict", 409)
  case ThreadState extends AdminErrorCode("admin:thread-state", 409)
  case TooLarge extends AdminErrorCode("admin:too-large", 413)
  case NotFound extends AdminErrorCode("admin:not-found", 404)
  case Internal extends AdminErrorCode("admin:internal", 500)
}

/** エラーの応答。fields は項目ごとの理由 */
final case class AdminError(code: AdminErrorCode, message: String, fields: Map[String, String] = Map.empty)

/** 板名の一覧 */
final case class BoardList(boards: List[String])

/** 板設定画面の内容。rules はこの板に当てはまる規制ルールの行 */
final case class BoardSettingsView(bbs: String, values: BoardSettingsValues, head: String, settingsEtag: Option[String], headEtag: Option[String],
  rules: BoardRules)

/** deny.cgi・ngwords.cgi のうち、ある板に当てはまる行 */
final case class BoardRules(access: List[String], ngwords: List[String])

/** 板設定の保存。ETag が None なら読込時にファイルが無かった */
final case class BoardSettingsUpdate(values: BoardSettingsValues, head: String, settingsEtag: Option[String], headEtag: Option[String])

/** 板設定の保存結果。partial は head.txt だけが競合して保存しなかったこと */
final case class BoardSettingsSaved(settingsEtag: Option[String], headEtag: Option[String], partial: Boolean)

/** 公開中のスレ。line は subject.txt の行 */
final case class ThreadSummary(key: String, line: String)

/** 板のスレ一覧。removed は削除・移動済みの key */
final case class ThreadList(bbs: String, subject: List[ThreadSummary], removed: List[String])

/** 編集用のテキスト */
final case class ResEditFields(name: String, mail: String, message: String)

/** dat の 1 行。name・mail・date・message は dat の欄のまま */
final case class ResView(number: Int, name: String, mail: String, date: String, message: String, edit: ResEditFields)

object ResView {
  /** dat の末尾の空白を除いて行に分ける。欠けた欄は空 */
  def fromDat(dat: String): List[ResView] = JsText.trimEnd(dat).split("\n", -1).toList.zipWithIndex.map { (line, i) =>
    val f = line.split("<>", -1)
    def field(index: Int): String = f.lift(index).getOrElse("")
    ResView(i + 1, field(0), field(1), field(2), field(3), ResEditFields(ResEditText.toText(field(0)), ResEditText.toText(field(1)), ResEditText.toText(field(3))))
  }
}

/** スレの詳細。hidden は削除・移動済み、stopped は停止中、movingTo は移動の途中 */
final case class ThreadDetail(bbs: String, key: String, hidden: Boolean, stopped: Boolean, movedTo: Option[String], movingTo: Option[String], res: List[ResView],
  dat: String, log: String, legacyLog: Option[String], settings: String)

/** スレの管理操作 */
enum ThreadAction(val name: String) {
  case Stop extends ThreadAction("stop")
  case Resume extends ThreadAction("resume")
  case Delete extends ThreadAction("delete")
  case Move(targetBoard: String, targetKey: String) extends ThreadAction("move")
  case EditRes(number: Int, resName: String, mail: String, message: String) extends ThreadAction("editRes")
  case DeleteRes(number: Int) extends ThreadAction("deleteRes")
}

/** 全板共通の規制ルールのファイル */
enum RuleName(val value: String, val file: String) {
  case Access extends RuleName("access", "deny.cgi")
  case Ngwords extends RuleName("ngwords", "ngwords.cgi")
}

object RuleName {
  /** API のパスの名前から引く */
  def fromValue(value: String): Option[RuleName] = values.find(_.value == value)
}

/** 本文と ETag。ETag が None ならファイルが無い */
final case class TextFile(text: String, etag: Option[String])

/** 保存の結果の ETag */
final case class Saved(etag: Option[String])
