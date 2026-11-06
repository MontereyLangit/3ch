package io._3ch.common.domain.device

/** 認証画面で採取した端末情報。arch は navigator.platform、os は申告 UA */
final case class ClientFingerprint(
  os: String = "",
  cores: Int = 0,
  ram: Int = 0,
  screen: String = "",
  dpr: Double = 1d,
  colorDepth: Int = 24,
  webgl: String = "",
  fonts: String = "",
  touch: Boolean = false,
  arch: String = ""
)
