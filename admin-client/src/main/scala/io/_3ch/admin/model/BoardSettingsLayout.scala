package io._3ch.admin.model

/** 項目の入力の形 */
enum SettingControl {
  /** 1 行の入力欄。units は欄の後ろに付ける単位 */
  case Inputs(units: List[String])
  /** 値の候補から 1 つ選ぶ。options は (値, 表示) */
  case Radios(options: List[(String, String)])
  /** checked か空 */
  case Check(label: String)
  /** 回避レベルの選択 */
  case Bypass
  /** BBS_FR_LEVEL（off・log・0・1 以上） */
  case FrLevel
  /** BBS_RENTOU_KEYS（カンマ区切りの ip・shiki・aid） */
  case RentouKeys
}

/** 板設定画面の 1 行。keys は行に並べる項目のキー */
final case class SettingRow(label: String, note: String, keys: List[String], control: SettingControl)

/** 板設定画面の項目のまとまり。accent は左端の色線のクラス */
final case class SettingGroup(title: String, accent: String, rows: List[SettingRow])

/** 板設定画面の項目の並び。左列が基本の設定、右列が規制 */
object BoardSettingsLayout {
  import SettingControl.*

  private def input(label: String, key: String, note: String = "", unit: String = ""): SettingRow =
    SettingRow(label, note, List(key), Inputs(List(unit).filter(_.nonEmpty)))
  private val Enabled = List("" -> "有効", "checked" -> "無効")

  val left: List[SettingGroup] = List(
    SettingGroup("基本", "border-l-[#f66]", List(
      input("掲示板タイトル", "BBS_TITLE"),
      input("名前未入力時の名前", "BBS_NONAME_NAME"),
      input("スレ一覧に載せるスレ数", "BBS_MAX_MENU_THREAD", "超えた分は一覧から外れるだけで、過去ログにはなりません"),
      input("強制sageまでの秒数", "BBS_FORCE_SAGE", "スレ立てから指定秒を過ぎると、上がらなくなります", "秒"))),
    SettingGroup("文字数・行数", "border-l-[#b9c4d0]", List(
      SettingRow("タイトル・名前・メールの文字数", "", List("BBS_SUBJECT_COUNT", "BBS_NAME_COUNT", "BBS_MAIL_COUNT"), Inputs(Nil)),
      input("本文の文字数", "BBS_MESSAGE_COUNT", ">>1は65536文字まで"),
      input("本文の行数", "BBS_LINE_NUMBER", ">>2以降。この値の2倍までの改行"))),
    SettingGroup("識別子", "border-l-[#7aa7e0]", List(
      SettingRow("名前欄の識別子", "県・回線・端末", List("BBS_ID_SHIKIBETSU"), Radios(List(
        "checked" -> "簡易表示（ハッシュ）", "raw" -> "県＋AS", "ken" -> "県", "city" -> "県＋市", "as" -> "AS", "phoebe" -> "新書式（Lv付き）",
        "d" -> "表示しない"))),
      SettingRow("識別子のグループ・リセット周期", "グループ未設定時は板名", List("BBS_ID_GROUP", "BBS_ID_RESET"), Inputs(List("", "秒"))))),
    SettingGroup("規制・スイッチ", "border-l-[#e0a16b]", List(
      SettingRow("規制に該当したとき", "", List("BBS_NO_MADAKANA"), Radios(List("" -> "拒否する", "checked" -> "注意表示を付けて許可"))),
      SettingRow("NGワード規制", "", List("BBS_NGWORD_PASS"), Radios(List("" -> "適用する", "checked" -> "適用しない"))),
      SettingRow("実況モード", "9割の書き込みを強制sage。連投規制も適用しない", List("BBS_LIVE"), Radios(List("" -> "無効", "checked" -> "有効"))),
      SettingRow("スレ主の機能", "名前欄の「(主)」と、!バルサン等のスレ主コマンド", List("BBS_DISABLE_NUSI"), Radios(Enabled)),
      SettingRow("書き込みを停止", "新規スレ作成・返信とも拒否。キャップも拒否", List("BBS_HEISA"), Check("停止する"))))
  )

  val right: List[SettingGroup] = List(
    SettingGroup("スレ立て規制", "border-l-[#d9b44a]", List(
      SettingRow("同一IP・タイトル", "直近n件のスレ立てをs秒照合。0件で無効", List("BBS_THREAD_TATESUGI", "BBS_THREAD_TATESUGI_RESET"), Inputs(List("件", "秒"))),
      SettingRow("同一UA・回線（広域）", "AS＋県（海外は海外全体）。0件で無効", List("BBS_THREAD_JUNBAN", "BBS_THREAD_JUNBAN_RESET"), Inputs(List("件", "秒"))),
      input("県で分けずAS全体を対象にするASN", "BBS_THREAD_JUNBAN_AS_ONLY", "空白・カンマ区切り"),
      SettingRow("スレ立て規制の免除", "", List("BBS_THREAD_PASS"), Bypass))),
    SettingGroup("連投規制", "border-l-[#b28be0]", List(
      SettingRow("動作", "未設定は1。空の値は0と同じ", List("BBS_FR_LEVEL"), FrLevel),
      SettingRow("板全体の連続投稿", "直近n件中m件以上で規制（24時間以内）", List("timecount", "timeclose"), Inputs(List("件中", "件"))),
      input("短時間投稿（Samba24）", "BBS_SAMBA24", unit = "秒"),
      input("広域連投（FlyRibbon）", "BBS_FR_SECOND", "同じ識別子の連投を規制。0秒で無効", "秒"),
      SettingRow("スレ内の連続投稿", "", List("BBS_THREAD_RENTOU", "BBS_THREAD_RENTOU_RESET"), Inputs(List("件 /", "秒"))),
      SettingRow("同一とみなす条件", "", List("BBS_RENTOU_KEYS"), RentouKeys),
      SettingRow("連投規制の免除", "", List("BBS_RENTOU_PASS"), Bypass))),
    SettingGroup("PHOEBE（Lv）", "border-l-[#5fb38a]", List(
      input("書き込みに必要な最低Lv", "BBS_PHOEBE", "Web版は常にログインが必要。この値は専ブラの未ログインとLvに効きます")))
  )

  /** 回避レベルの選択肢 */
  val bypassOptions: List[(String, String)] = List(
    "admin" -> "管理者だけ", "staff" -> "権限者（管理・削除・規制）", "privileged" -> "権限者とキャップ",
    "l10" -> "権限者・キャップ・Lv10", "login" -> "権限者・キャップ・ログイン済み")

  /** 連投規制の照合キー */
  val rentouKeys: List[(String, String)] = List("ip" -> "IP", "shiki" -> "識別子", "aid" -> "アカウント")

  /** BBS_RENTOU_KEYS の値から選ばれているキー。空はすべて */
  def selectedKeys(value: String): Set[String] = {
    val keys = value.split(",").map(_.trim).filter(k => rentouKeys.exists(_._1 == k)).toSet
    if (keys.isEmpty) rentouKeys.map(_._1).toSet else keys
  }

  /** 照合キーの選択を BBS_RENTOU_KEYS の値にする */
  def keysValue(selected: Set[String]): String = rentouKeys.map(_._1).filter(selected.contains).mkString(",")

  /** 画面に出す項目のキー（全項目を一度ずつ） */
  def keys: List[String] = (left ++ right).flatMap(_.rows).flatMap(_.keys)
}
