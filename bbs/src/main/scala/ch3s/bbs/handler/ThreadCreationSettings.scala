package ch3s.bbs.handler

import ch3s.bbs.domain.posting.moderation.ThreadCreationRateLimit

/** スレ立て設定を検証する。旧形式の件数/秒も受け付ける。 */
object ThreadCreationSettings {
  final case class Parsed(limits: ThreadCreationRateLimit.Settings, asOnly: Set[String])

  private def integer(value: String, name: String, maximum: Long): Either[String, Long] = {
    if (!value.matches("[0-9]+")) Left(s"$name は0以上の整数で指定してください。")
    else value.toLongOption.filter(_ <= maximum).toRight(s"$name は $maximum 以下で指定してください。")
  }

  def parseAsOnly(value: String): Either[String, Set[String]] = {
    value.trim.split("[,\\s]+", -1).filter(_.nonEmpty).foldLeft[Either[String, Set[String]]](Right(Set.empty)) { (acc, token) =>
      val digits = token.toUpperCase.stripPrefix("AS")
      for {
        result <- acc
        asn <- integer(digits, "BBS_THREAD_JUNBAN_AS_ONLY", 4294967295L)
        _ <- Either.cond(asn > 0, (), "BBS_THREAD_JUNBAN_AS_ONLY のASNは1以上で指定してください。")
      } yield result + asn.toString
    }
  }

  /** SETTING.TXT 本文を読む。値は最初の `=` 以降の全体、キー・値は前後空白を除き、重複キーは後勝ち。 */
  def settingsMap(text: String): Map[String, String] = text.split("\\r?\\n", -1).flatMap { line =>
    val parts = line.split("=", 2)
    if (parts.length == 2) Some(parts(0).trim -> parts(1).trim) else None
  }.toMap

  def parseText(text: String): Either[String, Parsed] = parse(settingsMap(text))

  def parse(values: Map[String, String]): Either[String, Parsed] = {
    def limit(name: String, default: Int): Either[String, (Int, Double)] = {
      val parts = values.get(name).filter(_.nonEmpty).getOrElse(default.toString).split("/", -1)
      if (parts.length > 2) Left(s"$name の形式が不正です。")
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
