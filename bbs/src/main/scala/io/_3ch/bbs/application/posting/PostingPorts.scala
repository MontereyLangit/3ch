package io._3ch.bbs.application.posting

import io._3ch.bbs.application.posting.ports.{ArchiveIndexStore, EdgeRuleStore, CapStore, Clock, Hasher, PostRateLog, PostingAccounts, PostingUserSource, Random, RuleStore, SettingsStore, ThreadStore, UserAgentTableStore}

/** 投稿処理が使う外部の読み書き・時計・乱数・記録。accounts は認証を有効にした環境だけ渡す。agents は識別子の phoebe 書式で読む。edge はエッジ防御の規則 */
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
  edge: EdgeRuleStore = EdgeRuleStore.empty
)
