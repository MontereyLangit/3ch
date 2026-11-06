package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.{ArchiveIndexStore, CapStore, Clock, Hasher, PostRateLog, PostingUserSource, Random, RuleStore, SettingsStore, ThreadStore}

/** 投稿処理が使う外部の読み書き・時計・乱数・記録 */
final case class PostingPorts(
  threads: ThreadStore,
  settings: SettingsStore,
  rules: RuleStore,
  caps: CapStore,
  archive: ArchiveIndexStore,
  users: PostingUserSource,
  hasher: Hasher,
  clock: Clock,
  random: Random,
  rateLog: PostRateLog
)
