package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.{BrokenLogKind, ModerationDenial, PostField, PostingError}
import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection

/** 投稿処理の既定の板設定と、拒否理由の文言。 */
object PostingText {
  val settingTxtDefault: String = "BBS_TITLE=<DIR_NAME>＠3ch掲示板\nBBS_NONAME_NAME=名無しさん＠３ちゃんねる\nBBS_MAX_MENU_THREAD=300\nBBS_FORCE_SAGE=\nBBS_ADD_THREAD=on\nBBS_SHIKIBETSU=checked\nBBS_ID_GROUP=\nBBS_ID_RESET=86400\nBBS_ID_SHIKIBETSU=checked\nBBS_SUBJECT_COUNT=64\nBBS_NAME_COUNT=64\nBBS_MAIL_COUNT=64\nBBS_MESSAGE_COUNT=4096\nBBS_LINE_NUMBER=30\nBBS_THREAD_TATESUGI=1\nBBS_THREAD_TATESUGI_RESET=3600\nBBS_THREAD_JUNBAN=1\nBBS_THREAD_JUNBAN_RESET=3600\nBBS_THREAD_JUNBAN_AS_ONLY=\nBBS_CREATETHREAD_INTERVAL=5\nBBS_COPIPE=3/86400\ntimecount=18\ntimeclose=10\ntimecover=3600\nBBS_COOK82=10\nBBS_COOK82_POST_LIMIT=60\nBBS_LIVE=\nBBS_KISEIOFF=\nBBS_NO_MADAKANA=\nBBS_THREAD_SETTINGS=on\nBBS_DISABLE_NUSI=\nBBS_FORCE_NONAME=\nBBS_ARR=\nBBS_HEISA=\n"

  val threadCreationNarrow: String = "スレッドを立てすぎです。しばらく待ってから投稿してください。"
  val threadCreationBroad: String = "この接続環境ではスレッドを立てすぎです。しばらく待ってから投稿してください。"

  val globalThreadCreateCapOnly: String = "全板共通スレッド(!924 / !900)は★(キャップ)のみ立てられます。"
  val globalThreadCapOnly: String = "このスレッドは★(キャップ)のみ書き込めます。"

  private val boardIncorrect = "板の指定が不正です。<br>\nThe board directory is incorrect..."

  private def fieldLabel(field: PostField): String = field match {
    case PostField.Subject => "サブジェクト"
    case PostField.Name => "名前"
    case PostField.Mail => "メールアドレス"
    case PostField.Message => "本文"
  }

  /** 拒否理由をエラー画面の文言にする。 */
  def message(error: PostingError): String = error match {
    case PostingError.MethodNotPost => "POST メソッドを使ってください。<br>\nRecieve GET METHOD"
    case PostingError.BrokenBody(contentType) => "送信された内容が破損しています。->" + contentType + "<br>\nBroken request body. "
    case PostingError.InvalidReferer(referer) => "ERROR: あなた騙されてますよ？->" + referer + "<br>\nReferer is invalid."
    case PostingError.InvalidUserAgent(ua) => "ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: " + ua + "<br>\nUserAgent is invalid."
    case PostingError.ProxyHeader(clientIp) => "変な情報が送られて来ました。<br>Client_IP: " + clientIp
    case PostingError.NoSubmit => "書き込みボタンを押忍べし<br>\nNo submit"
    case PostingError.SjisFromWeb => "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post."
    case PostingError.ForbiddenBoard => boardIncorrect
    case PostingError.EmptyMessage => "本文がありません！"
    case PostingError.MissingSubject(key) => "subjectが存在しないかキー情報が不正です。key=" + key + "<br>\nThe subject is empty and key is wrong."
    case PostingError.InvalidTime(time) => "ブラウザ変ですよん。(time)<br>\ntime is empty. time=" + time
    case PostingError.InvalidKey => "キー情報が不正です。"
    case PostingError.FieldTooLong(field, length, limit) => s"${fieldLabel(field)}が長すぎます！($length/$limit)"
    case PostingError.TooManyLines(lines, limit) => s"改行が多すぎます！($lines/$limit)"
    case PostingError.Closed => "この板・スレッドは停止されています。"
    case PostingError.ThreadMissing(logFile) => "該当するスレッドがありません。" + logFile
    case PostingError.BrokenLog(BrokenLogKind.Empty) => "スレッドのログファイルが壊れています。(空です)"
    case PostingError.BrokenLog(BrokenLogKind.NoTitle) => "スレッドのログファイルが壊れています。(タイトル取得失敗)"
    case PostingError.ThreadFull => "このスレッドは1000を超えたのでもう書けません。"
    case PostingError.DuplicateKey => "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。"
    case PostingError.Moderated(ModerationDenial.Tor) => "変なホスト規制中！！ Tor is not allowed."
    case PostingError.Moderated(ModerationDenial.BrokenRules) => "規制設定に問題があります。管理者にお問い合わせください。"
    case PostingError.Moderated(ModerationDenial.NgWord) => "NGワード禁止！"
    case PostingError.Moderated(ModerationDenial.TateDame) => "このホストではスレッドを立てられません。"
    case PostingError.Moderated(ModerationDenial.Host) => "アクセス規制中です！！"
    case PostingError.GlobalThreadCreateCapOnly => globalThreadCreateCapOnly
    case PostingError.GlobalThreadCapOnly => globalThreadCapOnly
    case PostingError.InvalidCreationSettings(detail) => detail
    case PostingError.ThreadCreation(ThreadCreationRejection.Broad(_)) => threadCreationBroad
    case PostingError.ThreadCreation(_) => threadCreationNarrow
    case PostingError.ArchiveConflict(target, site) =>
      s"updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(${site.clock}:${site.time} bbs:${site.bbs} key:${site.key} number:${site.number} errmsg:updateSubback->${target}_update_conflicted!)"
    case PostingError.Internal(stage, description, site, cause) =>
      s"$stage error. $description(${site.clock}:${site.time} bbs:${site.bbs} key:${site.key} number:${site.number} errmsg:$stage->$cause)<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。"
  }
}
