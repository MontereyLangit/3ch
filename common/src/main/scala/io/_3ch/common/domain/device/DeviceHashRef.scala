package io._3ch.common.domain.device

/** 粒度と端末ハッシュの保存値。旧16桁と公開鍵付きの保存値も読める */
final case class DeviceHashRef(granularity: DeviceGranularity, identifier: String, pubKeyHash: String = "") {
  /** 粒度1文字と端末部。旧保存値の公開鍵があればコロンで続ける */
  def format: String = s"${granularity.code}$identifier" + (if (pubKeyHash.isEmpty) "" else s":$pubKeyHash")

  /** 規制照合に使う端末部。粒度による除外はしない */
  def individualIdentifier: String = identifier
}

object DeviceHashRef {
  private def identifierLength(value: String): Boolean = value.length == 16 || value.length == DeviceFingerprintHasher.Length
  private def hex(value: String): Boolean = value.nonEmpty && value.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))

  /** 端末情報が1軸も採れなければ保存値を作らない */
  def of(fingerprint: Option[ClientFingerprint], pubKeyHash: String = "", hints: ClientHints = ClientHints()): Option[DeviceHashRef] = {
    val identifier = DeviceFingerprintHasher.hash(fingerprint, hints.model)
    Option.when(identifier.nonEmpty)(DeviceHashRef(DeviceGranularity.detect(fingerprint, hints), identifier, pubKeyHash))
  }

  /** 粒度付きの端末部だけなら粒度を除く。別の書式は変えない */
  def normalizeIdentifier(value: String): String = {
    if (value.nonEmpty && DeviceGranularity.fromCode(value.head).nonEmpty && identifierLength(value.tail) && hex(value.tail)) value.tail
    else value
  }

  /** 保存値を読む。粒度なしの旧形式は粒度不明とし、不正な桁数・文字種は受け付けない */
  def parse(stored: String): Option[DeviceHashRef] = {
    val raw = Option(stored).getOrElse("").trim
    val separator = raw.indexOf(':')
    if (separator < 0) {
      val granularity = raw.headOption.flatMap(DeviceGranularity.fromCode)
      val identifier = if (granularity.nonEmpty) raw.tail else raw
      Option.when(identifierLength(identifier) && hex(identifier))(
        DeviceHashRef(granularity.getOrElse(DeviceGranularity.Unknown), identifier))
    } else if (separator == 0 || separator == raw.length - 1) None
    else {
      val head = raw.substring(0, separator)
      val pubKeyHash = raw.substring(separator + 1)
      val granularity = DeviceGranularity.fromCode(head.head)
      val identifier = if (granularity.nonEmpty) head.tail else head
      Option.when(pubKeyHash.length == 64 && hex(pubKeyHash) && identifierLength(identifier) && hex(identifier))(
        DeviceHashRef(granularity.getOrElse(DeviceGranularity.Unknown), identifier, pubKeyHash))
    }
  }
}
