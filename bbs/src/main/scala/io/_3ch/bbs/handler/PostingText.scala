package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.{BrokenLogKind, FailureSite, ModerationDenial, PostField, PostingError, PostingStage}
import io._3ch.bbs.domain.posting.moderation.{PostRateRejection, ThreadCreationRejection, ThreadCreationSettingError}
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.posting.ThreadWriteRejection

/** 投稿処理の既定の板設定と、拒否理由の文言 */
object PostingText {
  val settingTxtDefault: String = BbsSettings.defaultText

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

  /** スレ立て設定の誤りを管理画面と投稿エラーの文言にする */
  def creationSettingsMessage(reason: ThreadCreationSettingError): String = reason match {
    case ThreadCreationSettingError.NonNegativeIntegerRequired(setting) => s"$setting は0以上の整数で指定してください。"
    case ThreadCreationSettingError.AboveMaximum(setting, maximum) => s"$setting は $maximum 以下で指定してください。"
    case ThreadCreationSettingError.InvalidFormat(setting) => s"$setting の形式が不正です。"
    case ThreadCreationSettingError.PositiveAsnRequired => "BBS_THREAD_JUNBAN_AS_ONLY のASNは1以上で指定してください。"
  }

  private def stageLabel(stage: PostingStage): String = stage match {
    case PostingStage.BoardSettings => "getBoardSettings"
    case PostingStage.ThreadDataAndSettings => "getThreadDataAndThreadSettings"
    case PostingStage.ThreadWrite => "updateThreadFile"
    case PostingStage.Subback => "updateSubback"
    case PostingStage.Headline => "updateHeadline"
  }

  /** Thread DO が書き込みを拒否した理由。内部エラーの原因の欄に Error: を付けて出す */
  private def threadWriteMessage(rejection: ThreadWriteRejection): String = rejection match {
    case ThreadWriteRejection.Unavailable => "このスレッドは停止・移動または削除されています。"
    case ThreadWriteRejection.Closed => "このスレッドは停止されています。"
    case ThreadWriteRejection.DuplicateKey => "同じkeyのスレッドが存在します。"
    case ThreadWriteRejection.Missing => "スレッドがありません。"
    case ThreadWriteRejection.Full => "このスレッドは1000を超えたのでもう書けません。"
  }

  private def internal(stage: PostingStage, site: FailureSite, cause: String): String = {
    val label = stageLabel(stage)
    val description = if stage == PostingStage.ThreadWrite then "ログファイルに書けませんでした。" else "不明なエラーが発生しました。"
    s"$label error. $description(${site.clock.label}:${site.time} bbs:${site.bbs} key:${site.key} number:${site.number} errmsg:$label->$cause)<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。"
  }

  /** 拒否理由をエラー画面の文言にする */
  def message(error: PostingError): String = error match {
    case PostingError.Authentication(reason) => reason match {
      case io._3ch.bbs.domain.account.AuthenticationFailure.LoginRequired => "この板（スレ）に書き込むには認証が必要です。"
      case io._3ch.bbs.domain.account.AuthenticationFailure.TokenInsecure => "HTTPSで接続してください。"
      case io._3ch.bbs.domain.account.AuthenticationFailure.TokenNotDedicated => "専用ブラウザから認証キーを使用してください。"
      case io._3ch.bbs.domain.account.AuthenticationFailure.Account(io._3ch.common.domain.account.AccountRejection.Agreement) => "アカウント設定で利用規約への同意を確認してください。"
      case _ => "認証を確認できませんでした。認証・登録から再度お試しください。"
    }
    case PostingError.AuthenticationLevel(required) => s"この板（スレ）に書き込むには認証が必要です（必要Lv:$required）。"
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
    case PostingError.InvalidCreationSettings(reason) => creationSettingsMessage(reason)
    case PostingError.ThreadCreation(ThreadCreationRejection.Broad(_)) => threadCreationBroad
    case PostingError.ThreadCreation(_) => threadCreationNarrow
    case PostingError.PostRate(PostRateRejection.Gontakure) => "連続投稿ですか？？ しばらく待ってから投稿してください。"
    case PostingError.PostRate(PostRateRejection.Samba24(seconds)) => s"Samba24 で規制中です。(あと $seconds 秒) しばらく待ってから投稿してください。"
    case PostingError.PostRate(PostRateRejection.Fr(seconds)) => s"広域連投規制中です。(あと $seconds 秒) しばらく待ってから投稿してください。"
    case PostingError.PostRate(PostRateRejection.ThreadRentou) => "このスレッドへの連続投稿が多すぎます。しばらく待ってから投稿してください。"
    case PostingError.ArchiveConflict(target, site) =>
      s"updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(${site.clock.label}:${site.time} bbs:${site.bbs} key:${site.key} number:${site.number} errmsg:updateSubback->${target}_update_conflicted!)"
    case PostingError.ThreadRefused(rejection, site) => internal(PostingStage.ThreadWrite, site, "Error: " + threadWriteMessage(rejection))
    case PostingError.Internal(stage, site, cause) => internal(stage, site, cause)
  }
}
