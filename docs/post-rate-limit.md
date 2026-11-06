# bbs.cgi 連投規制

2026-11-06。[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md) §8 の段 A-1 を実装した。
構成は [スレ立て規制](thread-creation-regulation.md) と同じで、判定と状態遷移はドメインの純粋関数、DO はロックと保存だけを持つ。

## 判定

| 規制 | 単位 | 内容 | 拒否コード |
| --- | --- | --- | --- |
| ごんたくれ | 板 | 板の履歴を新しい順に `timecount` 件取り、24 時間以内の同一投稿者が `timeclose` 件以上 | `post-rate:board:gontakure` |
| Samba24 | 板 | 板の履歴に `BBS_SAMBA24` 秒以内の同一投稿者がいる。残り秒数を文言に出す | `post-rate:board:samba24:<秒>` |
| スレ連投 | スレ | `BBS_THREAD_RENTOU_RESET` 秒以内の同一投稿者が `BBS_THREAD_RENTOU` 件以上。>>1 は見ない | `post-rate:thread:rentou` |
| コピペ | スレ | dat の末尾 2 行（あぼーん行は除く）の本文フィールドと完全一致。投稿者は問わない。>>1 は見ない | 成功画面のまま dat に残さない |

- 同一投稿者は `BBS_RENTOU_KEYS` のキー（`ip`・`shiki`・`aid`）のどれかが一致すること。スレ連投にも同じキーを使う。
  `ip` はスレ立て規制と同じ正規化、`shiki` は名前欄の識別子、`aid` は認証主体の `accountKey`（認証基盤ができるまでは常に空）。
  値はどれもソルト付き SHA-256 で、空の値は照合しない。
- 板の履歴では予約中も確定済みも 1 件に数える。吸い込んだ投稿も数える。拒否した投稿は数えない。
- 判定順は スレ連投 → ごんたくれ → Samba24 → コピペ。
- 判定しない投稿: `BBS_RENTOU_MODE=off`、`BBS_LIVE` が `checked` か `1`、`BBS_RENTOU_PASS` で免除される投稿者。
  権限と Lv がまだ無いので、免除されるのは `privileged`・`l10` ではキャップ（★）、`login` ではキャップと認証済、`admin`・`staff` では誰もいない。
  免除した投稿は履歴にも残さない。saku 板にも適用する。

## 試行モード

`BBS_RENTOU_MODE` が `log`（コード既定）なら判定と履歴の記録だけ行い、拒否も吸い込みもしない。拒否するはずだった判定は
Workers Logs に 1 行の JSON で出す（`{"event":"post-rate","mode":"log","bbs","key","number","codes":[...],"ip","shiki"}`。
`ip`・`shiki` はソルト付きハッシュ）。`on` で拒否と吸い込みを行う。拒否は HTTP 200 と `<!-- 2ch_X:error -->`。

## 設定

| SETTING.TXT | 既定 | 範囲 | 読む場所 |
| --- | --- | --- | --- |
| `timecount` / `timeclose` | `18` / `10` | 0〜1000 | 板設定だけ |
| `BBS_SAMBA24` | `10` | 0〜86400 | 板設定だけ |
| `BBS_THREAD_RENTOU` / `BBS_THREAD_RENTOU_RESET` | `40` / `3600` | 0〜1000 / 0〜315360000 | スレ設定 → 板設定 |
| `BBS_RENTOU_MODE` | `log` | `off` / `log` / `on` | スレ設定 → 板設定 |
| `BBS_RENTOU_PASS` | `privileged` | `admin` / `staff` / `privileged` / `l10` / `login` | スレ設定 → 板設定 |
| `BBS_RENTOU_KEYS` | `ip,shiki,aid` | カンマ区切り。`id` などは無視 | スレ設定 → 板設定 |

不正な値は拒否せず既定値で動く。スレ立て時の設定の書き写しから `BBS_SAMBA24`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_*` を除く。
`timecount` / `timeclose` は従来どおり書き写すが、板単位の規制は板設定だけを読むので効かない（書き写しをやめるかは承認待ち）。

## 保存・同時実行

**Board DO**

| キー | 内容 |
| --- | --- |
| `postRateMeta` | `{newest, oldest}`。保存しているページの通し番号の範囲 |
| `postRateHistory:<n>` | 通し番号を 16 で割った余りの枠に置く 64 件のページ（古い順）。各件 `{key, t, ip, shiki, aid, status}`、`status` は `pending` / `completed` |

- `reservePost(key, entry, settings)`（空＝許可、それ以外は拒否コード）・`completePost(key)`・`cancelPost(key)`。どれも `DurableLock.serialized`。
  `settings.enforce` が偽（試行モード）なら拒否コードを返しつつ予約する。
- 予約は最新ページとメタだけを書く。読むのは最新ページから、直近 `timecount` 件と `BBS_SAMBA24` 秒以内を読み終えるまで。
  確定と取消は記録を含むページだけを書く。
- 保持: 直近 `max(timecount, 1)` 件・`BBS_SAMBA24` 秒以内・有効な予約（120 秒）のどれかを含むページまでを範囲に残す。
  上限は 16 ページ（1,024 件）で、超えたら古いページを範囲から外す。容量では拒否しない。範囲から外れたページは消さず、枠が次に使われるときに上書きする。
  読み込まなかった古いページの予約は範囲から外れることがあるが、判定は予約と確定を同じに数えるので結果は変わらない。
- `meta` に件数は持たない（範囲から外したページの件数は読まないと分からないため）。

**Thread DO**

- `recentPosts`: `{t, ip, shiki, aid}` の配列（新しい順）。`BBS_THREAD_RENTOU_RESET` 秒以内・1,000 件まで。`dat`・`log` と同じ `put` で保存する。
- `addRes` の `posting.rateLimit` があれば、排他区間内で スレ連投の判定 → スレ立て予約 → `Board.reservePost` → コピペ判定 → `put` → 両方の確定 の順に行う。
  `put` が失敗したら両方の予約を取り消す。`on` で板単位に拒否したらスレ立て予約を取り消す。吸い込みは `completePost` だけして `put` しない。
  `Board.reservePost` の失敗は `on` なら投稿の失敗、`log` なら無視する。
- 戻り値は `{count, swallowed, observed}`。`observed` は試行モードで記録だけした判定のコード。`rateLimit` が無い呼び出し（管理画面・移動・テスト）は判定しない。

## 実装の置き場所

- ドメイン: `domain/posting/moderation/` の `PostRateLimit`（判定）・`PostRateHistory`（板の履歴）・`ThreadRecentPosts`・`PostRateSettings`・`BypassLevel`
- DO の変換: `durable/codec/PostRateCodec`
- 配線: `PostingService.postRateCheck`、試行モードの出力は `ports/PostRateLog`、文言は `PostingText`
