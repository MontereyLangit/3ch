package io._3ch.bbs.handler

import io._3ch.bbs.domain.posting.moderation.{ThreadCreationRateLimit, ThreadCreationSettingError}
import io._3ch.common.domain.BbsSettings

/** スレ立て設定を検証する。旧形式の件数/秒も受け付ける。 */
object ThreadCreationSettings {
  final case class Parsed(limits: ThreadCreationRateLimit.Settings, asOnly: Set[String])

  private def integer(value: String, name: String, maximum: Long): Either[ThreadCreationSettingError, Long] = {
    if (!value.matches("[0-9]+")) Left(ThreadCreationSettingError.NonNegativeIntegerRequired(name))
    else value.toLongOption.filter(_ <= maximum).toRight(ThreadCreationSettingError.AboveMaximum(name, maximum))
  }

  def parseAsOnly(value: String): Either[ThreadCreationSettingError, Set[String]] = {
    value.trim.split("[,\\s]+", -1).filter(_.nonEmpty).foldLeft[Either[ThreadCreationSettingError, Set[String]]](Right(Set.empty)) { (acc, token) =>
      val digits = token.toUpperCase.stripPrefix("AS")
      for {
        result <- acc
        asn <- integer(digits, "BBS_THREAD_JUNBAN_AS_ONLY", 4294967295L)
        _ <- Either.cond(asn > 0, (), ThreadCreationSettingError.PositiveAsnRequired)
      } yield result + asn.toString
    }
  }

  /** SETTING.TXT 本文を読む。値は最初の `=` 以降の全体、キー・値は前後空白を除き、重複キーは後勝ち。 */
  def settingsMap(text: String): Map[String, String] = text.split("\\r?\\n", -1).flatMap { line =>
    val parts = line.split("=", 2)
    if (parts.length == 2) Some(parts(0).trim -> parts(1).trim) else None
  }.toMap

  def parseText(text: String): Either[ThreadCreationSettingError, Parsed] = parse(settingsMap(text))

  /** 設定名の前後空白を除き、重複は後勝ちで読む。 */
  def fromSettings(settings: BbsSettings): Either[ThreadCreationSettingError, Parsed] =
    parse(settings.value.foldLeft(Map.empty[String, String]) { case (acc, (k, v)) => acc.updated(k.trim, v) })

  def parse(values: Map[String, String]): Either[ThreadCreationSettingError, Parsed] = {
    def limit(name: String, default: Int): Either[ThreadCreationSettingError, (Int, Double)] = {
      val parts = values.get(name).filter(_.nonEmpty).getOrElse(default.toString).split("/", -1)
      if (parts.length > 2) Left(ThreadCreationSettingError.InvalidFormat(name))
      else for {
        count <- integer(parts(0), name, 1000)
        reset <- integer(values.get(name + "_RESET").filter(_.nonEmpty).getOrElse(parts.lift(1).getOrElse("3600")), name + "_RESET", 315360000L)
      } yield (count.toInt, reset.toDouble)
    }
    for {
      normal <- limit("BBS_THREAD_TATESUGI", 1)
      broad <- limit("BBS_THREAD_JUNBAN", 1)
      asOnly <- parseAsOnly(values.getOrElse("BBS_THREAD_JUNBAN_AS_ONLY", ""))
    } yield Parsed(ThreadCreationRateLimit.Settings(normal._1, normal._2, broad._1, broad._2), asOnly)
  }
}
