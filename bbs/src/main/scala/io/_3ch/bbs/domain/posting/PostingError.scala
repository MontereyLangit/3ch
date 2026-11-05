package io._3ch.bbs.domain.posting

import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection

/** 文字数を検査する入力欄。 */
enum PostField(val code: String) {
  case Subject extends PostField("subject")
  case Name extends PostField("name")
  case Mail extends PostField("mail")
  case Message extends PostField("message")
}

/** スレのログが壊れている理由。 */
enum BrokenLogKind(val code: String) {
  case Empty extends BrokenLogKind("empty")
  case NoTitle extends BrokenLogKind("title")
}

/** deny.cgi / ngwords.cgi などの規制で拒否した理由。 */
enum ModerationDenial(val code: String) {
  case Tor extends ModerationDenial("tor")
  case BrokenRules extends ModerationDenial("rules")
  case NgWord extends ModerationDenial("ngword")
  case TateDame extends ModerationDenial("tatedame")
  case Host extends ModerationDenial("host")
}

/** 内部エラーが起きた段の状況。`time` は文言に出す時刻の文字列（未設定なら `undefined`）。 */
final case class FailureSite(clock: String, time: String, bbs: String, key: String, number: String)

/** 投稿の拒否理由。文言は持たず、`code` は英小文字の識別子。 */
enum PostingError {
  case MethodNotPost
  case BrokenBody(contentType: String)
  case InvalidReferer(referer: String)
  case InvalidUserAgent(userAgent: String)
  case ProxyHeader(clientIp: String)
  case NoSubmit
  case SjisFromWeb
  case TripKeyTooLong
  case ForbiddenBoard
  case EmptyMessage
  case MissingSubject(key: String)
  case InvalidTime(time: String)
  case InvalidKey
  case FieldTooLong(field: PostField, length: Int, limit: String)
  case TooManyLines(lines: Int, limit: String)
  case Closed
  case ThreadMissing(logFile: String)
  case BrokenLog(kind: BrokenLogKind)
  case ThreadFull
  case DuplicateKey
  case Moderated(denial: ModerationDenial)
  case InvalidCreationSettings(detail: String)
  case ThreadCreation(rejection: ThreadCreationRejection)
  case ArchiveConflict(target: String, site: FailureSite)
  case Internal(stage: String, description: String, site: FailureSite, cause: String)

  def code: String = this match {
    case MethodNotPost => "request:method"
    case BrokenBody(_) => "request:content-type"
    case InvalidReferer(_) => "request:referer"
    case InvalidUserAgent(_) => "request:user-agent"
    case ProxyHeader(_) => "request:client-ip"
    case NoSubmit => "form:no-submit"
    case SjisFromWeb => "form:sjis-from-web"
    case TripKeyTooLong => "form:trip-key-too-long"
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
    case Moderated(denial) => s"moderation:${denial.code}"
    case InvalidCreationSettings(_) => "thread-creation:settings"
    case ThreadCreation(rejection) => rejection.code
    case ArchiveConflict(_, _) => "internal:archive-conflict"
    case Internal(stage, _, _, _) => "internal:" + stage.flatMap(c => if c.isUpper then "-" + c.toLower else c.toString)
  }
}
