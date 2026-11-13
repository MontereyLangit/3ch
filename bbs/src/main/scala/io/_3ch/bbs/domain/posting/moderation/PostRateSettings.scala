package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, ThreadSettings}
import io._3ch.common.domain.BbsSettings
import io._3ch.common.domain.identity.ShikibetsuMode

/** 連投規制の段階（BBS_FR_LEVEL）。Log は判定と記録だけして拒否しない */
enum PostRateLevel {
  case Off
  case Log
  /** 拒否する。flyRibbon が None なら広域連投規制（FlyRibbon）を判定しない。数値の強さは今は使わない */
  case Enforce(flyRibbon: Option[Int])

  /** 広域連投規制を判定するか。Log は既定どおり判定して記録する */
  def checksFlyRibbon: Boolean = this match {
    case Off => false
    case Log => true
    case Enforce(number) => number.isDefined
  }

  /** 設定に書く形の値。RPC でもこの形で渡す */
  def value: String = this match {
    case Off => "off"
    case Log => "log"
    case Enforce(number) => number.fold("0")(_.toString)
  }
}

object PostRateLevel {
  /** キーが無いときの段階。規制し、広域連投規制も判定する */
  val Default: PostRateLevel = Enforce(Some(1))

  /**
    * 設定の値を読む。キーが無ければ Default、空と 0 は広域連投規制だけを外す。
    * 負数・大文字・知らない値などの不正な値は Default にする
    */
  def parse(value: Option[String]): PostRateLevel = value.map(_.trim) match {
    case None => Default
    case Some("off") => Off
    case Some("log") => Log
    case Some("") => Enforce(None)
    case Some(text) if text.matches("[0-9]+") => text.toIntOption.fold(Default)(number => Enforce(Option.when(number > 0)(number)))
    case Some(_) => Default
  }
}

/** Thread.addRes に渡す連投規制の材料と設定。identity はハッシュ済み */
final case class PostRateCheck(level: PostRateLevel, identity: PostRateIdentity, board: BoardSettings, thread: ThreadSettings) {
  /** 判定と履歴の記録をするか */
  def enabled: Boolean = level != PostRateLevel.Off

  /** 判定で拒否するか。偽なら記録だけする */
  def enforce: Boolean = level match {
    case PostRateLevel.Enforce(_) => true
    case PostRateLevel.Off | PostRateLevel.Log => false
  }

  /** Board.reservePost に渡す板単位の設定。広域連投規制を判定しない段階では checksFr を偽にする（fr は履歴の保持のため板設定のまま） */
  def boardSettings: BoardSettings = board.copy(checksFr = level.checksFlyRibbon)
}

/** 連投規制の設定。不正な値は拒否せず既定値で動き、BBS_FR_LEVEL の不正な値は規制と広域連投規制の側に倒す */
final case class PostRateSettings(level: PostRateLevel, pass: BypassLevel, live: Boolean, board: BoardSettings, thread: ThreadSettings) {
  /** この投稿を判定するか。off・BBS_LIVE・回避レベルで免除される投稿者は判定も記録もしない */
  def applies(cap: Boolean, authenticated: Boolean): Boolean =
    level != PostRateLevel.Off && !live && !pass.exempts(cap, authenticated)
}

object PostRateSettings {
  /** BBS_FR_SECOND の既定（秒） */
  val DefaultFrSeconds: Int = 1

  /**
    * 連投規制で shiki の照合値にする文字列。識別子が checked か phoebe のときだけ使い、
    * ken・city・as・raw などは県・市・AS の単位で別人が一致するので空（照合しない）にする。キャップ（★）も空
    */
  def shikiSource(mode: ShikibetsuMode, cap: Boolean, shikibetsu: String): String =
    if (cap || (mode != ShikibetsuMode.Checked && mode != ShikibetsuMode.Phoebe)) "" else shikibetsu

  /** スレ立て時にスレ設定へ書き写さないキー。BBS_PHOEBE はコマンドで書いたときだけスレ設定に置く */
  val NotCopiedToThread: Set[String] =
    Set("BBS_SAMBA24", "BBS_FR_SECOND", "BBS_THREAD_RENTOU", "BBS_THREAD_RENTOU_RESET", "BBS_RENTOU_PASS", "BBS_RENTOU_KEYS", "BBS_FR_LEVEL", "BBS_PHOEBE")

  /**
    * board は板設定、merged は返信ならスレ設定を重ねたもの。
    * 板単位の件数と秒数は板設定だけから、それ以外は merged から読む
    */
  def from(board: BbsSettings, merged: BbsSettings): PostRateSettings = {
    val boardValues = trimmed(board)
    val values = trimmed(merged)
    def integer(source: Map[String, String], name: String, default: Int, maximum: Int): Int =
      source.get(name).map(_.trim).filter(_.matches("[0-9]+")).flatMap(_.toIntOption).filter(_ <= maximum).getOrElse(default)
    val keys = PostRateKey.parse(values.getOrElse("BBS_RENTOU_KEYS", ""))
    PostRateSettings(
      level = PostRateLevel.parse(values.get("BBS_FR_LEVEL")),
      pass = BypassLevel.parse(values.getOrElse("BBS_RENTOU_PASS", "")).getOrElse(BypassLevel.Privileged),
      live = values.get("BBS_LIVE").exists(value => value == "checked" || value == "1"),
      board = BoardSettings(
        timecount = integer(boardValues, "timecount", 18, 1000),
        timeclose = integer(boardValues, "timeclose", 10, 1000),
        samba24 = integer(boardValues, "BBS_SAMBA24", 10, 86400),
        fr = integer(boardValues, "BBS_FR_SECOND", DefaultFrSeconds, 86400),
        keys = keys),
      thread = ThreadSettings(
        count = integer(values, "BBS_THREAD_RENTOU", 40, 1000),
        resetSeconds = integer(values, "BBS_THREAD_RENTOU_RESET", 3600, 315360000).toDouble,
        keys = keys))
  }

  /** スレ立て時に書き写す板設定。NotCopiedToThread のキーを除く */
  def copiedToThread(settings: BbsSettings): BbsSettings =
    BbsSettings(settings.value.filterNot { case (name, _) => NotCopiedToThread.contains(name.trim) })

  private def trimmed(settings: BbsSettings): Map[String, String] =
    settings.value.foldLeft(Map.empty[String, String]) { case (acc, (name, value)) => acc.updated(name.trim, value.trim) }
}
