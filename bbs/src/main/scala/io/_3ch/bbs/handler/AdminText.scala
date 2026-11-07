package io._3ch.bbs.handler

import io._3ch.bbs.application.admin.{AdminFailure, AdminFile}
import io._3ch.bbs.domain.admin.SettingProblem
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleError, ModerationRuleProblem}

/** 管理 API と管理画面の配信の文言 */
object AdminText {
  /** 管理操作が拒否された理由の文言 */
  def failure(value: AdminFailure): String = value match {
    case AdminFailure.InvalidBoard(_) => "板名が不正です。"
    case AdminFailure.InvalidKey(_) => "keyは10桁の数字で指定してください。"
    case AdminFailure.CreationSettings(reason) => PostingText.creationSettingsMessage(reason)
    case AdminFailure.Settings(_) => apiInvalid
    case AdminFailure.Rules(error) => ruleError(error)
    case AdminFailure.Conflict(file) => conflict(file)
    case AdminFailure.ThreadMissing => "スレッドがありません。"
    case AdminFailure.ThreadRefused(message) => message
  }

  /** 管理 API の項目ごとの理由 */
  def settingProblem(problem: SettingProblem): String = problem match {
    case SettingProblem.LineBreak => "改行は使用できません。"
    case SettingProblem.Number => "0以上の整数で指定してください。"
    case SettingProblem.Choice => "選択肢にない値です。"
    case SettingProblem.Level => "off・log・0以上の整数・空欄のいずれかにしてください。"
    case SettingProblem.Phoebe => "0〜10の整数で指定してください。"
    case SettingProblem.Duplicate(keys) => s"フォームの項目と同じキーは書けません（${keys.mkString("・")}）。"
    case SettingProblem.Creation(reason) => PostingText.creationSettingsMessage(reason)
  }

  val apiInvalid = "入力内容を確認してください。"
  val apiUnauthorized = "管理者認証が必要です。"
  val apiUnavailable = "管理者パスワードが未設定です。"
  val apiNotFound = "APIがありません。"
  val apiMethod = "このメソッドは使えません。"
  val apiContentType = "Content-Type は application/json にしてください。"
  val apiTooLarge = "入力が大きすぎます。"
  val apiMalformed = "JSONとして読めません。"
  val apiField = "形式が不正です。"
  val apiResNumber = "レス番号は1以上の整数で指定してください。"
  val apiInternal = "処理できませんでした。"
  val apiOrigin = "管理画面以外からの変更は受け付けません。"
  val pageMissing = "管理画面のファイルがありません。npm run v6:build を実行してください。"

  private def conflict(file: AdminFile): String = file match {
    case AdminFile.Settings => "他の操作で板設定が更新されました。画面を開き直してから保存してください。"
    case AdminFile.Head => "SETTING.TXTは保存しましたが、head.txtは他の操作で更新されていたため保存していません。画面を開き直してください。"
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
}
