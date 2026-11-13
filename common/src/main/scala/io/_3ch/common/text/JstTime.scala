package io._3ch.common.text

/** UNIX 秒を日本時間の表示にする。実行環境のタイムゾーンに依存させず SSR と hydration で一致させる */
object JstTime {
  private val Offset = 9L * 3600

  private def two(n: Long): String = if (n < 10) s"0$n" else n.toString

  private def civil(seconds: Double): (Long, Long, Long, Long, Long) = {
    val local = math.floor(seconds).toLong + Offset
    val days = Math.floorDiv(local, 86400L)
    val secondOfDay = Math.floorMod(local, 86400L)
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + (if (month <= 2) 1 else 0)
    (year, month, day, secondOfDay / 3600, secondOfDay % 3600 / 60)
  }

  /** yyyy/MM/dd HH:mm */
  def dateTime(seconds: Double): String = {
    val (year, month, day, hour, minute) = civil(seconds)
    s"$year/${two(month)}/${two(day)} ${two(hour)}:${two(minute)}"
  }

  /** yyyy/MM/dd */
  def date(seconds: Double): String = dateTime(seconds).take(10)
}
