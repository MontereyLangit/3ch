package ch3s.bbs.domain.posting.moderation

/**
  * スレ立て規制ロジックのドメインモデル。
  * 
  * 同一IP, 同一UA, 同一AS+UA（将来廃止）, 同一AS+県（除外設定ASはASのみで。海外は海外全体）, 同一スレタイで制限
  * 将来：端末情報（認証済のみ）, AS+端末情報複合（認証済のみ）,未認証のASのみ/県のみ , 認証済IDでの制限も追加。未認証は海外禁止
  * 同一UA、AS/県、端末情報は認証済ならスルー。
  * BBS_THREAD_CREATION_RATE_LIMIT_RESET 秒(default=180000)でリセット。
  */
object ThreadCreationRateLimit {
  
  
}
