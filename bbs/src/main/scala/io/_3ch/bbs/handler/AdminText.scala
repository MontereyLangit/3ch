package io._3ch.bbs.handler

import io._3ch.bbs.application.admin.{AdminFailure, AdminFile}
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleError, ModerationRuleProblem}

object AdminText {
  /** 管理操作が拒否された理由の文言 */
  def failure(value: AdminFailure): String = value match {
    case AdminFailure.InvalidBoard(_) => "板名が不正です。"
    case AdminFailure.InvalidKey(_) => "keyは10桁の数字で指定してください。"
    case AdminFailure.CreationLineBreak => "スレ立て規制の入力に改行は使用できません。"
    case AdminFailure.CreationSettings(reason) => PostingText.creationSettingsMessage(reason)
    case AdminFailure.Settings(_) => "入力内容を確認してください。"
    case AdminFailure.Rules(error) => ruleError(error)
    case AdminFailure.Conflict(file) => conflict(file)
    case AdminFailure.ThreadMissing => "スレッドがありません。"
    case AdminFailure.ThreadRefused(message) => message
  }

  private def conflict(file: AdminFile): String = file match {
    case AdminFile.Settings => "他の操作で板設定が更新されました。画面を開き直してから保存してください。"
    case AdminFile.Head => "他の操作でhead.txtが更新されました。画面を開き直してから保存してください。"
    case AdminFile.Rules => "他の操作で規制ルールが更新されました。画面を開き直してから保存してください。"
    case AdminFile.Menu => "他の操作で板一覧が更新されました。画面を開き直してから保存してください。"
  }

  /** 規制ルールの書式の誤りを、行番号付きの文言にする */
  def ruleError(error: ModerationRuleError): String = s"${error.line}行目: " + (error.problem match {
    case ModerationRuleProblem.Format => "板<>対象<>NGワード<>動作 の形式で入力してください。"
    case ModerationRuleProblem.Action => "動作が不正です。"
    case ModerationRuleProblem.Pattern => "正規表現が不正です。"
    case ModerationRuleProblem.Bypass => "回避レベルは admin・staff・privileged・l10・login のいずれかにしてください。"
  })
  def rules(access: Boolean): String = "<h1>" + (if access then "アクセス規制登録" else "NGワード設定") + "</h1><p>1行に「板の正規表現&lt;&gt;投稿者情報の正規表現&lt;&gt;NGワードの正規表現&lt;&gt;動作」。空欄は全対象。# で始まる行はコメントです。</p><p>動作: deny（拒否）、mark（注意表示）、TateDame（スレ立て禁止）、md5（拒否）。5列目に回避レベル（admin・staff・privileged・l10・login）を書くと、その投稿者にはこの行を適用しません。省略すると privileged（権限者とキャップ）です。対象例: IP:192\\.0\\.2\\.1(?: |$)、AS1234-。NGは名前・メール・本文・タイトルに適用します。</p><p>例: " + (if access then "^test$&lt;&gt;IP:192\\.0\\.2\\.1(?: |$)&lt;&gt;&lt;&gt;deny" else "^test$&lt;&gt;&lt;&gt;禁止語&lt;&gt;deny") + "</p>"
  def page(content: String): String = "<!doctype html><html lang=\"ja\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>3ch 管理画面</title><style>body{font:16px sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172435;background:#f6f8fb}nav{display:flex;gap:1rem;flex-wrap:wrap}form,details{background:white;padding:1rem;margin:1rem 0;border:1px solid #ccd4de;border-radius:6px}label{display:block;margin:.5rem 0}textarea{display:block;width:100%;box-sizing:border-box;font:14px monospace}input{padding:.4rem}button{padding:.6rem 1rem;cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere}summary{cursor:pointer}</style><nav><a href=\"/admin/\">管理画面top</a><a href=\"/admin/access\">アクセス規制</a><a href=\"/admin/ngwords\">NGワード</a><a href=\"/admin/menu\">板一覧編集</a></nav><hr>" + content + "</html>"
}
