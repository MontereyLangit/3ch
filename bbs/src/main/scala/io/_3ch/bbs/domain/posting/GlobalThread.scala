package io._3ch.bbs.domain.posting

/**
 * 全板共通スレ（スレキー先頭3桁が `924` / `900`）の純粋な規則。背景は `docs/global-thread.md`。
 * 共通スレは板 `924` の Thread / Board DO に保存し、全板の subject.txt の先頭に 924 → 900 の順で載せる
 */
object GlobalThread {
  /** 共通スレの保存先となる板名 */
  val board: String = "924"

  /** 共通スレの種別。prefix はスレキーの先頭 3 桁 */
  enum Kind(val prefix: String) {
    /** ★のみ立てられ、★のみ書き込める */
    case CapOnly extends Kind("924")
    /** ★のみ立てられるが、誰でも書き込める */
    case Open extends Kind("900")
  }

  /** 一覧に並べる順 */
  val kinds: List[Kind] = List(Kind.CapOnly, Kind.Open)

  /** スレキーの先頭 3 桁から共通スレの種別を返す。共通スレでなければ None */
  def kindOf(key: String): Option[Kind] = kinds.find(kind => key.startsWith(kind.prefix))

  /** 本文先頭の `!924` / `!900` を読み、種別と（直後の空白・`<br>` を除いた）残りの本文を返す。`!9240` のように数字が続く場合は命令としない */
  def command(message: String): Option[(Kind, String)] =
    kinds.find(kind => message.startsWith("!" + kind.prefix)).flatMap { kind =>
      val rest = message.drop(kind.prefix.length + 1)
      if rest.headOption.exists(_.isDigit) then None
      else Some((kind, rest.replaceFirst("^(?: |<br>)+", "")))
    }

  /** スレ立て時刻（unixtime 10桁）の先頭3桁を種別の接頭辞に置き換えてスレキーにする */
  def key(kind: Kind, unixtime: String): String = kind.prefix + unixtime.drop(3)

  /** その種別のスレに書き込めるか。924 はキャップだけ書き込める */
  def canPost(kind: Kind, cap: Boolean): Boolean = kind match {
    case Kind.CapOnly => cap
    case Kind.Open => true
  }

  /** 共通スレのスレ立て・返信で最初にあてはまる拒否理由 */
  def postingError(key: String, creatingThread: Boolean, cap: Boolean): Option[PostingError] = kindOf(key).flatMap { kind =>
    if creatingThread && !cap then Some(PostingError.GlobalThreadCreateCapOnly)
    else if !canPost(kind, cap) then Some(PostingError.GlobalThreadCapOnly)
    else None
  }

  /** subject.txt の行（`key.dat<>...`）を結合する。共通スレを 924 → 900 の順に先頭へ置き、板固有の行を続ける。各群の中では元の並びを保つ */
  def mergeSubject(global: Seq[String], own: Seq[String]): Seq[String] = {
    def kindOfLine(line: String): Option[Kind] = kindOf(line.takeWhile(_ != '.'))
    kinds.flatMap(kind => global.filter(line => kindOfLine(line).contains(kind))) ++ own.filter(line => kindOfLine(line).isEmpty)
  }
}
