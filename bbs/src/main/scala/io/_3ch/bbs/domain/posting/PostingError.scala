package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.{PostRateRejection, ThreadCreationRejection, ThreadCreationSettingError}
import io._3ch.common.domain.posting.ThreadWriteRejection

/** 文字数を検査する入力欄 */
enum PostField(val code: String) {
  case Subject extends PostField("subject")
  case Name extends PostField("name")
  case Mail extends PostField("mail")
  case Message extends PostField("message")
}

/** スレのログが壊れている理由 */
enum BrokenLogKind(val code: String) {
  case Empty extends BrokenLogKind("empty")
  case NoTitle extends BrokenLogKind("title")
}

/** deny.cgi・ngwords.cgi などの規制で拒否した理由 */
enum ModerationDenial(val code: String) {
  case Tor extends ModerationDenial("tor")
  case BrokenRules extends ModerationDenial("rules")
  case NgWord extends ModerationDenial("ngword")
  case TateDame extends ModerationDenial("tatedame")
  case Host extends ModerationDenial("host")
}

/** 内部エラーが起きた投稿処理の段階 */
enum PostingStage(val code: String) {
  case BoardSettings extends PostingStage("get-board-settings")
  case ThreadDataAndSettings extends PostingStage("get-thread-data-and-thread-settings")
  case ThreadWrite extends PostingStage("update-thread-file")
  case Subback extends PostingStage("update-subback")
  case Headline extends PostingStage("update-headline")
}

/** 内部エラーの文言に出す時刻の種類 */
enum FailureClock(val label: String) {
  case Time extends FailureClock("time")
  case Unixtime extends FailureClock("unixtime")
}

/** 内部エラーが起きたときの時刻・板・スレキー・レス番号。時刻が未設定なら undefined */
final case class FailureSite(clock: FailureClock, time: String, bbs: String, key: String, number: String)

/** 投稿の拒否理由。文言は持たず、code で種別を英小文字の識別子にする */
enum PostingError {
  case MethodNotPost
  case BrokenBody(contentType: String)
  case InvalidReferer(referer: String)
  case InvalidUserAgent(userAgent: String)
  /** Client-IP ヘッダが付いている。clientIp はその値 */
  case ProxyHeader(clientIp: String)
  case NoSubmit
  case SjisFromWeb
  case ForbiddenBoard
  case EmptyMessage
  case MissingSubject(key: String)
  case InvalidTime(time: String)
  case InvalidKey
  /** limit は文言に出す上限で、設定値の文字列そのもの（無ければ既定値） */
  case FieldTooLong(field: PostField, length: Int, limit: String)
  /** 本文の行数（<br> で数える）が多すぎる。limit は文言に出す上限 */
  case TooManyLines(lines: Int, limit: String)
  case Closed
  /** 返信先のスレが無い。logFile は文言に出す旧ログのパス */
  case ThreadMissing(logFile: String)
  case BrokenLog(kind: BrokenLogKind)
  case ThreadFull
  /** 同じ key のスレが同時に立った */
  case DuplicateKey
  case GlobalThreadCreateCapOnly
  case GlobalThreadCapOnly
  case Moderated(denial: ModerationDenial)
  case InvalidCreationSettings(reason: ThreadCreationSettingError)
  case ThreadCreation(rejection: ThreadCreationRejection)
  /** 連投規制で拒否した */
  case PostRate(rejection: PostRateRejection)
  /** 過去ログ目次の書き込みが ETag の競合で失敗した。target は文言に出す対象名 */
  case ArchiveConflict(target: String, site: FailureSite)
  /** Thread DO が書き込みを拒否した。文言は内部エラーと同じ形 */
  case ThreadRefused(rejection: ThreadWriteRejection, site: FailureSite)
  /** 段の途中で起きた例外。cause は例外の文字列 */
  case Internal(stage: PostingStage, site: FailureSite, cause: String)

  /** 拒否理由の識別子。種別:詳細 の形で、設計書 §8.9 のエラーコードに合わせる */
  def code: String = this match {
    case MethodNotPost => "request:method"
    case BrokenBody(_) => "request:content-type"
    case InvalidReferer(_) => "request:referer"
    case InvalidUserAgent(_) => "request:user-agent"
    case ProxyHeader(_) => "request:client-ip"
    case NoSubmit => "form:no-submit"
    case SjisFromWeb => "form:sjis-from-web"
    case ForbiddenBoard => "form:board"
    case EmptyMessage => "form:empty-message"
    case MissingSubject(_) => "form:subject"
    case InvalidTime(_) => "form:time"
    case InvalidKey => "form:key"
    case FieldTooLong(field, _, _) => s"size:${field.code}"
    case TooManyLines(_, _) => "size:lines"
    case Closed => "thread:closed"
    case ThreadMissing(_) => "thread:missing"
    case BrokenLog(kind) => s"thread:broken-log:${kind.code}"
    case ThreadFull => "thread:full"
    case DuplicateKey => "thread:duplicate-key"
    case GlobalThreadCreateCapOnly => "global-thread:create-cap-only"
    case GlobalThreadCapOnly => "global-thread:cap-only"
    case Moderated(denial) => s"moderation:${denial.code}"
    case InvalidCreationSettings(_) => "thread-creation:settings"
    case ThreadCreation(rejection) => rejection.code
    case PostRate(rejection) => rejection.code
    case ArchiveConflict(_, _) => "internal:archive-conflict"
    case ThreadRefused(rejection, _) => rejection.code
    case Internal(stage, _, _) => "internal:" + stage.code
  }
}
