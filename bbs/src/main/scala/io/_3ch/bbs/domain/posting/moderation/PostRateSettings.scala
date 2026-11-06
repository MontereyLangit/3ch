package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, ThreadSettings}
import io._3ch.common.domain.BbsSettings

/** 連投規制の段階（BBS_FR_LEVEL）。Log は判定と記録だけして拒否しない */
enum PostRateLevel {
  case Off
  case Log
  /** 拒否する。number は旧 3ch の広域連投規制の数値で、今は使わない */
  case Enforce(number: Option[Int])

  /** 設定に書く形の値。RPC でもこの形で渡す */
  def value: String = this match {
    case Off => "off"
    case Log => "log"
    case Enforce(number) => number.fold("")(_.toString)
  }
}

object PostRateLevel {
  /** 設定の値を読む。キーが無い・空・数値・知らない値はどれも Enforce にする */
  def parse(value: Option[String]): PostRateLevel = value.map(_.trim) match {
    case Some("off") => Off
    case Some("log") => Log
    case Some(text) if text.matches("[0-9]+") => Enforce(text.toIntOption.filter(_ > 0))
    case _ => Enforce(None)
  }
}

/** Thread.addRes に渡す連投規制の材料と設定。identity はハッシュ済み */
final case class PostRateCheck(level: PostRateLevel, identity: PostRateIdentity, board: BoardSettings, thread: ThreadSettings) {
  /** 判定で拒否するか。偽なら記録だけする */
  def enforce: Boolean = level match {
    case PostRateLevel.Enforce(_) => true
    case PostRateLevel.Off | PostRateLevel.Log => false
  }
}

/** 連投規制の設定。不正な値は拒否せず既定値で動き、BBS_FR_LEVEL だけは規制側に倒す */
final case class PostRateSettings(level: PostRateLevel, pass: BypassLevel, live: Boolean, board: BoardSettings, thread: ThreadSettings) {
  /** この投稿を判定するか。off・BBS_LIVE・回避レベルで免除される投稿者は判定も記録もしない */
  def applies(cap: Boolean, authenticated: Boolean): Boolean =
    level != PostRateLevel.Off && !live && !pass.exempts(cap, authenticated)
}

object PostRateSettings {
  /** スレ立て時にスレ設定へ書き写さないキー */
  val NotCopiedToThread: Set[String] =
    Set("BBS_SAMBA24", "BBS_THREAD_RENTOU", "BBS_THREAD_RENTOU_RESET", "BBS_RENTOU_PASS", "BBS_RENTOU_KEYS", "BBS_FR_LEVEL")

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
