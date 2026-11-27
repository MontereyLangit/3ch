package io._3ch.bbs.service.posting

import io._3ch.bbs.service.posting.ports.{ArchiveIndexStore, EdgeRuleStore, PhoebeRuleStore, CapStore, Clock, Hasher, PostRateLog, PostingAccounts, PostingUserSource, Random, RuleStore, SettingsStore, ThreadStore, UserAgentTableStore}

/** 投稿処理が使う、外部の読み書き、時計、乱数、記録の口。accounts は認証を有効にした環境にだけ渡す。agents は識別子の phoebe 書式が読む UA 解析表。edge はエッジ防御の規則、phoebe は Lv の上限ルール */
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
  rateLog: PostRateLog,
  accounts: Option[PostingAccounts] = None,
  agents: UserAgentTableStore = UserAgentTableStore.empty,
  edge: EdgeRuleStore = EdgeRuleStore.empty,
  phoebe: PhoebeRuleStore = PhoebeRuleStore.empty
)
