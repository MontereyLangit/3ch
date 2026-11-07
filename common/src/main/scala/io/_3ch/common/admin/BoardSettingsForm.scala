package io._3ch.common.admin

/** 板設定の項目の入力の種類 */
enum SettingKind {
  case Text
  /** 0 以上の整数か空 */
  case Number
  /** 値の候補。空も含める */
  case Choice(values: List[String])
  /** checked か空 */
  case Check
}

/** 板設定の項目。default はキーが無いときにコードが使う値（表示用） */
final case class SettingField(key: String, label: String, kind: SettingKind, default: String)

/** 項目の値。explicit が偽ならキーが無く、value は既定値 */
final case class SettingValue(value: String, explicit: Boolean)

/** 板設定画面の値。fields はフォームの項目、extra はそれ以外の行（未知のキーと = の無い行） */
final case class BoardSettingsValues(fields: Map[String, SettingValue], extra: List[String])

/** 板設定画面の項目と SETTING.TXT の相互変換。値の検証はサーバで行う */
object BoardSettingsForm {
  private val Bypass = SettingKind.Choice(List("", "admin", "staff", "privileged", "l10", "login"))

  /** フォームに出す項目。v6 が読むキーだけ */
  val fields: List[SettingField] = List(
    SettingField("BBS_TITLE", "掲示板タイトル", SettingKind.Text, ""),
    SettingField("BBS_NONAME_NAME", "名前未入力時の名前", SettingKind.Text, "名無しさん＠３ちゃんねる"),
    SettingField("BBS_MAX_MENU_THREAD", "subject.txtに載せるスレ数", SettingKind.Number, "700"),
    SettingField("BBS_FORCE_SAGE", "強制sageまでの秒数", SettingKind.Number, ""),
    SettingField("BBS_SUBJECT_COUNT", "スレッドタイトルの文字数", SettingKind.Number, "64"),
    SettingField("BBS_NAME_COUNT", "名前の文字数", SettingKind.Number, "64"),
    SettingField("BBS_MAIL_COUNT", "メールの文字数", SettingKind.Number, "64"),
    SettingField("BBS_MESSAGE_COUNT", "本文の文字数", SettingKind.Number, "4096"),
    SettingField("BBS_LINE_NUMBER", "投稿行数", SettingKind.Number, "30"),
    SettingField("BBS_ID_SHIKIBETSU", "県名・回線表示", SettingKind.Choice(List("", "checked", "raw", "ken", "city", "as", "phoebe", "d")), "checked"),
    SettingField("BBS_ID_GROUP", "識別子のグループ", SettingKind.Text, ""),
    SettingField("BBS_ID_RESET", "識別子のリセット周期（秒）", SettingKind.Number, "86400"),
    SettingField("BBS_NO_MADAKANA", "規制の代わりに注意表示", SettingKind.Check, ""),
    SettingField("BBS_NGWORD_PASS", "NGワードを適用しない", SettingKind.Check, ""),
    SettingField("BBS_LIVE", "実況モード", SettingKind.Choice(List("", "checked", "1")), ""),
    SettingField("BBS_HEISA", "一般からの投稿を停止", SettingKind.Check, ""),
    SettingField("BBS_THREAD_TATESUGI", "通常規制の件数", SettingKind.Text, "1"),
    SettingField("BBS_THREAD_TATESUGI_RESET", "通常規制の有効期間（秒）", SettingKind.Text, "3600"),
    SettingField("BBS_THREAD_JUNBAN", "広域規制の件数", SettingKind.Text, "1"),
    SettingField("BBS_THREAD_JUNBAN_RESET", "広域規制の有効期間（秒）", SettingKind.Text, "3600"),
    SettingField("BBS_THREAD_JUNBAN_AS_ONLY", "県で分けずAS全体を対象にするASN", SettingKind.Text, ""),
    SettingField("timecount", "連続投稿規制の判定件数", SettingKind.Number, "18"),
    SettingField("timeclose", "連続投稿規制の上限件数", SettingKind.Number, "10"),
    SettingField("BBS_SAMBA24", "短時間投稿規制秒数", SettingKind.Number, "10"),
    SettingField("BBS_FR_SECOND", "広域連投規制秒数", SettingKind.Number, "1"),
    SettingField("BBS_THREAD_RENTOU", "スレッド内の連続投稿の上限", SettingKind.Number, "40"),
    SettingField("BBS_THREAD_RENTOU_RESET", "スレッド内の連続投稿の期間（秒）", SettingKind.Number, "3600"),
    SettingField("BBS_FR_LEVEL", "連投規制の段階", SettingKind.Text, "1"),
    SettingField("BBS_RENTOU_KEYS", "連投規制の照合キー", SettingKind.Text, ""),
    SettingField("BBS_RENTOU_PASS", "連投規制の免除", Bypass, "privileged"),
    SettingField("BBS_PHOEBE", "書き込みに必要な最低Lv", SettingKind.Number, "0"),
    SettingField("BBS_DISABLE_NUSI", "スレ主の機能を止める", SettingKind.Check, ""),
    SettingField("BBS_THREAD_PASS", "スレ立て規制の免除", Bypass, "privileged")
  )

  /** フォームの項目のキー */
  val keys: Set[String] = fields.map(_.key).toSet

  private def lines(text: String): List[String] =
    text.split("\n", -1).toList.map(_.stripSuffix("\r")).filter(_.trim.nonEmpty)

  /** = の前の部分を前後の空白を除いて返す。= が無ければ None */
  private def keyOf(line: String): Option[String] = {
    val at = line.indexOf('=')
    Option.when(at >= 0)(line.substring(0, at).trim)
  }

  /** SETTING.TXT を読む。キー・値は前後の空白を除き、同じキーは後勝ち。空行は捨てる */
  def read(text: String): BoardSettingsValues = {
    val (entries, extra) = lines(text).partitionMap { line =>
      keyOf(line).filter(keys.contains) match {
        case Some(key) => Left(key -> line.substring(line.indexOf('=') + 1).trim)
        case None => Right(line)
      }
    }
    val explicit = entries.toMap
    BoardSettingsValues(fields.map(f => f.key -> explicit.get(f.key).fold(SettingValue(f.default, explicit = false))(SettingValue(_, explicit = true))).toMap, extra)
  }

  /**
    * original の行順を保って SETTING.TXT にする。フォームの項目はキーの最初の行の位置に、その他の行は original のその他の行の位置に順に入れ、
    * 余った行と original に無い項目は末尾に足す。未設定の項目と空行は書かない
    */
  def render(original: String, values: BoardSettingsValues): String = {
    def entry(key: String): Option[String] = values.fields.get(key).filter(_.explicit).map(v => s"$key=${v.value}")
    val (placed, rest, written) = lines(original).foldLeft((Vector.empty[String], values.extra.filter(_.trim.nonEmpty), Set.empty[String])) {
      case ((out, extra, done), line) => keyOf(line).filter(keys.contains) match {
        case Some(key) if done.contains(key) => (out, extra, done)
        case Some(key) => (out ++ entry(key), extra, done + key)
        case None => extra match {
          case head :: tail => (out :+ head, tail, done)
          case Nil => (out, Nil, done)
        }
      }
    }
    val added = fields.map(_.key).filterNot(written.contains).flatMap(entry)
    (placed ++ rest ++ added).map(_ + "\n").mkString
  }

  /** その他の行のうち、フォームの項目と同じキーを持つ行のキー */
  def duplicatedKeys(extra: List[String]): List[String] = extra.flatMap(keyOf).filter(keys.contains).distinct
}
