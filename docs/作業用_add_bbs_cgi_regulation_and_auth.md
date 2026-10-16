- まず必須のスレ立て規制実装 -> 一旦デプロイ
- 板topのSSR化・bbs.cgi結果/BBSMENUなどのReact化
- 次に連投規制 -> 一旦デプロイ
- その後認証+他規制という感じで。

## スレ立て規制について

通常のスレ立て制限対象：同一IP/同一認証済ID/同一セッションID(未認証)での規制/同一スレタイ
広域スレ立て規制対象：同一AS+ISP(指定ISPはASのみで判定するようにする：管理画面に機能追加)、同一UA(未認証)
通常のスレ立て制限は直近BBS_THREAD_TATESUGI件のスレ立てのうち、一つでも対象のスレ立て履歴があればはじく。
広域スレ立て規制対象はBBS_THREAD_JUNBAN件のスレ立てのうち、一つでも対象のスレ立て履歴があればはじく。
ただし、BBS_THREAD_TATESUGI_RESET, BBS_THREAD_JUNBAN_RESET秒より前の履歴は無視する。
今回のブランチで追加したドメインモデルを使用すること。
[[ThreadCreationRateLimit]]のScaladoc参照。
認証機能/Lv機能は後回しなのでまだ考慮しなくて良い
