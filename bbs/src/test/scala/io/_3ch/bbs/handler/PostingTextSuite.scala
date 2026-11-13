package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.domain.posting.{BrokenLogKind, FailureClock, FailureSite, ModerationDenial, PostField, PostingError, PostingStage}
import io._3ch.bbs.domain.posting.moderation.{ThreadCreationRejection, ThreadCreationRateLimit, ThreadCreationSettingError}
import io._3ch.common.domain.posting.ThreadWriteRejection

/** 拒否理由ごとの文言が従来どおりか */
class PostingTextSuite extends FunSuite {
  private val site = FailureSite(FailureClock.Unixtime, "undefined", "sample", "1700000000", "1")

  private val expected: Seq[(PostingError, String)] = Seq(
    PostingError.MethodNotPost -> "POST メソッドを使ってください。<br>\nRecieve GET METHOD",
    PostingError.BrokenBody("text/plain") -> "送信された内容が破損しています。->text/plain<br>\nBroken request body. ",
    PostingError.InvalidReferer("https://evil.example/") -> "ERROR: あなた騙されてますよ？->https://evil.example/<br>\nReferer is invalid.",
    PostingError.InvalidUserAgent("short") -> "ERROR: ブラウザ変ですよん。(ua)<br>UserAgent: short<br>\nUserAgent is invalid.",
    PostingError.ProxyHeader("1.2.3.4") -> "変な情報が送られて来ました。<br>Client_IP: 1.2.3.4",
    PostingError.NoSubmit -> "書き込みボタンを押忍べし<br>\nNo submit",
    PostingError.SjisFromWeb -> "書き込みボタンを押忍べし<br>2ch専用ブラウザからの書き込みでこのエラーが出た場合、お使いの専用ブラウザはMonazilla/1.00で始まるUserAgentを使用していません。<br>認証時の問題などもありますので、2ch専用ブラウザではMonazilla/1.00で始まるUserAgentを使用してください。<br>(Monazilla/1.00以外のUserAgentでsubmit=%E6%9B%B8…でない場合にこのエラーが出ます。)<br>\n&apos;Submit&apos; must require &apos;書&apos; with Web browser&apos;s post.",
    PostingError.ForbiddenBoard -> "板の指定が不正です。<br>\nThe board directory is incorrect...",
    PostingError.EmptyMessage -> "本文がありません！",
    PostingError.MissingSubject("") -> "subjectが存在しないかキー情報が不正です。key=<br>\nThe subject is empty and key is wrong.",
    PostingError.InvalidTime("") -> "ブラウザ変ですよん。(time)<br>\ntime is empty. time=",
    PostingError.InvalidKey -> "キー情報が不正です。",
    PostingError.FieldTooLong(PostField.Subject, 65, "64") -> "サブジェクトが長すぎます！(65/64)",
    PostingError.FieldTooLong(PostField.Name, 65, "64") -> "名前が長すぎます！(65/64)",
    PostingError.FieldTooLong(PostField.Mail, 65, "64") -> "メールアドレスが長すぎます！(65/64)",
    PostingError.FieldTooLong(PostField.Message, 4097, "4096") -> "本文が長すぎます！(4097/4096)",
    PostingError.TooManyLines(61, "60") -> "改行が多すぎます！(61/60)",
    PostingError.Closed -> "この板・スレッドは停止されています。",
    PostingError.ThreadMissing("sample/thread/1699/1699000000.cgi") -> "該当するスレッドがありません。sample/thread/1699/1699000000.cgi",
    PostingError.BrokenLog(BrokenLogKind.Empty) -> "スレッドのログファイルが壊れています。(空です)",
    PostingError.BrokenLog(BrokenLogKind.NoTitle) -> "スレッドのログファイルが壊れています。(タイトル取得失敗)",
    PostingError.ThreadFull -> "このスレッドは1000を超えたのでもう書けません。",
    PostingError.DuplicateKey -> "別の人が同時刻にスレッドを立てようとしています。ごめんなさい。",
    PostingError.GlobalThreadCreateCapOnly -> "全板共通スレッド(!924 / !900)は★(キャップ)のみ立てられます。",
    PostingError.GlobalThreadCapOnly -> "このスレッドは★(キャップ)のみ書き込めます。",
    PostingError.Moderated(ModerationDenial.Tor) -> "変なホスト規制中！！ Tor is not allowed.",
    PostingError.Moderated(ModerationDenial.BrokenRules) -> "規制設定に問題があります。管理者にお問い合わせください。",
    PostingError.Moderated(ModerationDenial.NgWord) -> "NGワード禁止！",
    PostingError.Moderated(ModerationDenial.TateDame) -> "このホストではスレッドを立てられません。",
    PostingError.Moderated(ModerationDenial.Host) -> "アクセス規制中です！！",
    PostingError.InvalidCreationSettings(ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_TATESUGI")) -> "BBS_THREAD_TATESUGI は0以上の整数で指定してください。",
    PostingError.ThreadCreation(ThreadCreationRejection.Narrow(ThreadCreationRateLimit.Reason.Ip)) -> "スレッドを立てすぎです。しばらく待ってから投稿してください。",
    PostingError.ThreadCreation(ThreadCreationRejection.Capacity) -> "スレッドを立てすぎです。しばらく待ってから投稿してください。",
    PostingError.ThreadCreation(ThreadCreationRejection.Broad(ThreadCreationRateLimit.Reason.NetworkGroup)) -> "この接続環境ではスレッドを立てすぎです。しばらく待ってから投稿してください。",
    PostingError.ArchiveConflict("SubbackFIle", site) -> "updateSubback error. 板飛びそうなので、またの機会にどうぞ。。。(unixtime:undefined bbs:sample key:1700000000 number:1 errmsg:updateSubback->SubbackFIle_update_conflicted!)",
    PostingError.ThreadRefused(ThreadWriteRejection.Closed, site) ->
      "updateThreadFile error. ログファイルに書けませんでした。(unixtime:undefined bbs:sample key:1700000000 number:1 errmsg:updateThreadFile->Error: このスレッドは停止されています。)<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。",
    PostingError.Internal(PostingStage.BoardSettings, FailureSite(FailureClock.Time, "1700000000000", "sample", "1700000000", "0"), "Error: boom") ->
      "getBoardSettings error. 不明なエラーが発生しました。(time:1700000000000 bbs:sample key:1700000000 number:0 errmsg:getBoardSettings->Error: boom)<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。"
  )

  test("全拒否理由の文言が従来と一致する") {
    expected.foreach((error, message) => assertEquals(PostingText.message(error), message, error.toString))
  }

  test("拒否コードは英小文字と区切り記号だけで、理由ごとに異なる") {
    val codes = expected.map(_._1).filterNot(_.isInstanceOf[PostingError.ThreadCreation]).map(_.code)
    codes.foreach(code => assert(code.matches("[a-z:-]+"), code))
    assertEquals(codes.distinct.size, codes.size)
  }

  test("スレ立て設定の失敗理由を管理画面と投稿画面で同じ文言にする") {
    val cases = Seq(
      ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_TATESUGI_RESET") -> "BBS_THREAD_TATESUGI_RESET は0以上の整数で指定してください。",
      ThreadCreationSettingError.AboveMaximum("BBS_THREAD_JUNBAN", 1000) -> "BBS_THREAD_JUNBAN は 1000 以下で指定してください。",
      ThreadCreationSettingError.InvalidFormat("BBS_THREAD_TATESUGI") -> "BBS_THREAD_TATESUGI の形式が不正です。",
      ThreadCreationSettingError.PositiveAsnRequired -> "BBS_THREAD_JUNBAN_AS_ONLY のASNは1以上で指定してください。"
    )
    cases.foreach { (reason, text) =>
      assertEquals(PostingText.creationSettingsMessage(reason), text)
      assertEquals(PostingText.message(PostingError.InvalidCreationSettings(reason)), text)
      assertEquals(PostingError.InvalidCreationSettings(reason).code, "thread-creation:settings")
    }
  }

  test("内部エラーの段階ごとに診断名・説明・コードを保つ") {
    val cases = Seq(
      (PostingStage.BoardSettings, "getBoardSettings", "不明なエラーが発生しました。", "internal:get-board-settings"),
      (PostingStage.ThreadDataAndSettings, "getThreadDataAndThreadSettings", "不明なエラーが発生しました。", "internal:get-thread-data-and-thread-settings"),
      (PostingStage.ThreadWrite, "updateThreadFile", "ログファイルに書けませんでした。", "internal:update-thread-file"),
      (PostingStage.Subback, "updateSubback", "不明なエラーが発生しました。", "internal:update-subback"),
      (PostingStage.Headline, "updateHeadline", "不明なエラーが発生しました。", "internal:update-headline")
    )
    assertEquals(cases.map(_._1).toSet, PostingStage.values.toSet)
    cases.foreach { (stage, label, description, code) =>
      val error = PostingError.Internal(stage, site, "Error: boom")
      assertEquals(error.code, code)
      assertEquals(PostingText.message(error), s"$label error. $description(unixtime:undefined bbs:sample key:1700000000 number:1 errmsg:$label->Error: boom)<br>このメッセージをコピペして、運用情報板で報告していただけるとありがたいです。")
    }
  }

  test("Thread DO の拒否は従来の DO の例外文言を原因に出し、コードは DO が返したもの") {
    val cases = Seq(
      ThreadWriteRejection.Unavailable -> "このスレッドは停止・移動または削除されています。",
      ThreadWriteRejection.Closed -> "このスレッドは停止されています。",
      ThreadWriteRejection.DuplicateKey -> "同じkeyのスレッドが存在します。",
      ThreadWriteRejection.Missing -> "スレッドがありません。",
      ThreadWriteRejection.Full -> "このスレッドは1000を超えたのでもう書けません。"
    )
    assertEquals(cases.map(_._1).toSet, ThreadWriteRejection.values.toSet)
    cases.foreach { (rejection, cause) =>
      val refused = PostingError.ThreadRefused(rejection, site)
      assertEquals(refused.code, rejection.code)
      assertEquals(PostingText.message(refused), PostingText.message(PostingError.Internal(PostingStage.ThreadWrite, site, "Error: " + cause)))
    }
  }
}
