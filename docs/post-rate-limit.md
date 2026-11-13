# bbs.cgi 連投規制

2026-11-06。[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md) §8 の段 A-1 を実装した。
構成は [スレ立て規制](thread-creation-regulation.md) と同じで、判定と状態遷移はドメインの純粋関数、DO はロックと保存だけを持つ。

## 判定

| 規制 | 単位 | 内容 | 拒否コード |
| --- | --- | --- | --- |
| ごんたくれ | 板 | 板の履歴を新しい順に `timecount` 件取り、24 時間以内の同一投稿者が `timeclose` 件以上 | `post-rate:board:gontakure` |
| Samba24 | 板 | 板の履歴に `BBS_SAMBA24` 秒以内の IP か `aid` が一致する投稿がある。残り秒数を文言に出す | `post-rate:board:samba24:<秒>` |
| 広域連投規制（FlyRibbon, FR） | 板 | 板の履歴に `BBS_FR_SECOND` 秒以内の識別子が一致する投稿がある。`BBS_FR_LEVEL` が 1 以上（キー無しを含む）か `log` のときだけ見る。残り秒数を文言に出す | `post-rate:board:fr:<秒>` |
| スレ連投 | スレ | `BBS_THREAD_RENTOU_RESET` 秒以内の同一投稿者が `BBS_THREAD_RENTOU` 件以上。>>1 は見ない | `post-rate:thread:rentou` |
| コピペ | スレ | dat の末尾 2 行（あぼーん行は除く）の本文フィールドと完全一致。投稿者は問わない。>>1 は見ない | 成功画面のまま dat に残さない |

- ごんたくれとスレ連投の同一投稿者は `BBS_RENTOU_KEYS` のキー（`ip`・`shiki`・`aid`）のどれかが一致すること。
  Samba24 は `BBS_RENTOU_KEYS` によらず `ip`・`aid` のどちらかの一致、広域連投規制は `shiki` の一致だけを見る。
  識別子は同じ地域・キャリア・機種の別人でも一致するので、10 秒の Samba24 から外し、短い秒数の広域連投規制に分けた（2026-11-06 利用者決定）。
  `ip` はスレ立て規制と同じ正規化、`shiki` は名前欄の識別子（キャップの `★` と、`BBS_ID_SHIKIBETSU` が `checked` 以外の板は空にする。`ken`・`city`・`as`・`raw` では県・市・AS の単位で別人が一致し、`phoebe` も UA 解析表が無い間は E/F/G が `000` で市も含まないため粗すぎる。`phoebe` を照合に使うかは UA 解析表の整備後に決める）、`aid` は認証主体の `accountKey`（認証を有効にした環境のログイン済の投稿だけ。未ログインと認証を有効にしていない環境では空）。
  値はどれもソルト付き SHA-256 で、空の値は照合しない。
- 板の履歴では予約中も確定済みも 1 件に数える。吸い込んだ投稿も数える。拒否した投稿は数えない。
- 判定順は スレ連投 → ごんたくれ → Samba24 → 広域連投規制 → コピペ。
- 判定しない投稿: `BBS_FR_LEVEL=off`、`BBS_LIVE` が `checked` か `1`、`BBS_RENTOU_PASS` で免除される投稿者。
  免除は回避レベル（[設計書](auth-phoebe-rate-limit-design.md) §7）で決める。段 D から役割と実効 Lv を見る。`staff` は名無しの投稿でも免除され、`cap` 権限はキャップを付けた投稿（★）でだけ免除される。
  認証を有効にしていない環境では役割も Lv も無いので、免除されるのは従来どおり `privileged`・`l10` ではキャップ、`login` ではキャップと認証済だけ。
  免除した投稿は履歴にも残さない。saku 板にも適用する。

## 段階（`BBS_FR_LEVEL`）

| 値 | 動き |
| --- | --- |
| `off` | 判定も記録もしない |
| `log` | 判定と履歴の記録だけ行い、拒否も吸い込みもしない（試行モード）。広域連投規制も判定して記録する |
| キーが無い | 既定の `1` と同じ。規制モードで、広域連投規制も判定する |
| 1 以上の整数 | 規制モードで、広域連投規制も判定する。数値は旧 3ch の広域連投規制の強さで、意味は未実装（型に保持する） |
| `0`・空（`BBS_FR_LEVEL=`） | 規制モードだが広域連投規制だけを判定しない（ごんたくれ・Samba24・スレ連投・コピペは行う） |
| それ以外（負数・`on`・`OFF`・`1.5` などの不正な値） | キー無しと同じ（設定ミスで規制が外れないようにする） |

前後の空白は除いて比べる（空白だけの値は空）。`off` / `log` は小文字だけに一致する（`BBS_LIVE` の `checked` と同じく値をそのまま比べる）。
**コード既定は規制モードで広域連投規制も有効**で、SETTING.TXT にキーを書かない板はすべて拒否する。
型は `PostRateLevel`（`Off` / `Log` / `Enforce(flyRibbon: Option[Int])`）で、`Enforce(None)` が広域連投規制を判定しない規制モード。
`BBS_FR_SECOND=0` でも広域連投規制は判定しない（どちらか一方で外れる）。

`Thread.addRes` の `posting.rateLimit.level` には `off` / `log` / `0` / 1 以上の整数で渡す（`Enforce(None)` は `0`）。
`level` が空の旧形式は、以前の意味（規制モード）に合わせてキー無しと同じに読む。
`Board.reservePost` の `settings` には、広域連投規制を判定しない段階なら `checksFr: false` を付けて渡す（無いときは判定する）。
`fr` は板設定のまま渡し、履歴の保持と読み込みは板設定だけで決める（スレ設定の `BBS_FR_LEVEL=0` などで板の履歴を縮めない）。

`log` で拒否するはずだった判定は Workers Logs に 1 行の JSON で出す（`{"event":"post-rate","mode":"log","bbs","key","number","codes":[...],"ip","shiki"}`。
`ip`・`shiki` はソルト付きハッシュ）。拒否は HTTP 200 と `<!-- 2ch_X:error -->`。
吸い込み（コピペ）は投稿者を問わないので、規制モードでは別人の投稿の写しも成功画面のまま dat に残さない。

当初は `BBS_RENTOU_MODE`（`off` / `log` / `on`、既定 `log`）で試行してから既定を `on` にする予定だったが、2026-11-06 の利用者決定で
`BBS_FR_LEVEL` に置き換え、既定を最初から規制にした。`BBS_RENTOU_MODE` は読まない。

## 設定

| SETTING.TXT | 既定 | 範囲 | 読む場所 |
| --- | --- | --- | --- |
| `timecount` / `timeclose` | `18` / `10` | 0〜1000 | 板設定だけ |
| `BBS_SAMBA24` | `10` | 0〜86400 | 板設定だけ |
| `BBS_FR_SECOND` | `1` | 0〜86400。`BBS_FR_LEVEL` が広域連投規制を外していれば見ない | 板設定だけ |
| `BBS_THREAD_RENTOU` / `BBS_THREAD_RENTOU_RESET` | `40` / `3600` | 0〜1000 / 0〜315360000 | スレ設定 → 板設定 |
| `BBS_FR_LEVEL` | `1`（キー無し） | `off` / `log` / `0`・空 / 1 以上の整数 | スレ設定 → 板設定 |
| `BBS_RENTOU_PASS` | `privileged` | `admin` / `staff` / `privileged` / `l10` / `login` | スレ設定 → 板設定 |
| `BBS_RENTOU_KEYS` | `ip,shiki,aid` | カンマ区切り。`id` などは無視 | スレ設定 → 板設定 |

不正な値は拒否せず既定値で動く（`BBS_FR_LEVEL` の不正な値は規制モードで広域連投規制も有効）。スレ立て時の設定の書き写しから `BBS_SAMBA24`・`BBS_FR_SECOND`・`BBS_THREAD_RENTOU(_RESET)`・`BBS_RENTOU_PASS`・`BBS_RENTOU_KEYS`・`BBS_FR_LEVEL` を除く。
`timecount` / `timeclose` は従来どおり書き写すが、板単位の規制は板設定だけを読むので効かない（書き写しをやめるかは未決）。

## 保存・同時実行

**Board DO**

| キー | 内容 |
| --- | --- |
| `postRateMeta` | `{newest, oldest}`。保存しているページの通し番号の範囲 |
| `postRateHistory:<n>` | 通し番号を 16 で割った余りの枠に置く 64 件のページ（古い順）。各件 `{key, t, ip, shiki, aid, status}`、`status` は `pending` / `completed` |

- `reservePost(key, entry, settings)`（空＝許可、それ以外は拒否コード）・`completePost(key)`・`cancelPost(key)`。どれも `DurableLock.serialized`。
  `settings.enforce` が偽（試行モード）なら拒否コードを返しつつ予約する。
- `settings` は `{timecount, timeclose, samba24, fr, keys, enforce}`。`fr` の無い旧形式は既定の 1 秒で読む（`Thread.addRes` の `posting.rateLimit` も同じ）。
- 予約は最新ページとメタだけを書く。読むのは最新ページから、直近 `timecount` 件と `BBS_SAMBA24`・`BBS_FR_SECOND` の大きい方の秒数以内を読み終えるまで。
  確定と取消は記録を含むページだけを書く。
- 保持: 確定済みの直近 `max(timecount, 1)` 件・`BBS_SAMBA24` と `BBS_FR_SECOND` の大きい方の秒数以内の確定済み・有効な予約（120 秒）のどれかを含むページまでを範囲に残す。
  板設定だけで決まり、予約中は件数と秒数に数えない。範囲の見直しは予約の追記の前（追記前の履歴）に行うので、予約を取り消せば範囲は予約前のまま（予約の追記でページ数の上限を超えて押し出されたページは戻らない）。
  判定のために読むページも確定済みの件数で決める。
  上限は 16 ページ（1,024 件）で、超えたら古いページを範囲から外す。容量では拒否しない。範囲から外れたページは消さず、枠が次に使われるときに上書きする。
  読み込まなかった古いページの予約は範囲から外れることがあるが、判定は予約と確定を同じに数えるので結果は変わらない。
- `meta` に件数は持たない（範囲から外したページの件数は読まないと分からないため）。

**Thread DO**

- `recentPosts`: `{t, ip, shiki, aid}` の配列（新しい順）。`BBS_THREAD_RENTOU_RESET` 秒以内・1,000 件まで。`dat`・`log` と同じ `put` で保存する。
  DO の値の上限（128 KiB）に収めるため、照合値は先頭 12 文字（16 進、48 bit）に短縮し、空の値は書かない（最大で約 85 バイト × 1,000 件）。全長のハッシュで保存した値も、読むときに短縮して照合する。
  スレの移動では `dat`・`log` と一緒に移動先へ引き継ぐ（無ければ作らない）。
- 板の履歴のページは全長のハッシュのまま（64 件で約 20 KB）。
- `addRes` の `posting.rateLimit` があれば、排他区間内で スレ連投の判定 → スレ立て予約 → `Board.reservePost` → コピペ判定 → `put` → 両方の確定 の順に行う。
  `put` が失敗したら両方の予約を取り消す。規制モードで板単位に拒否したらスレ立て予約を取り消す。吸い込みは `completePost` だけして `put` しない。
  `Board.reservePost` の失敗は規制モードなら投稿の失敗、`log` なら無視する。
- 戻り値は `{count, swallowed, observed}`。`observed` は試行モードで記録だけした判定のコード。`rateLimit` が無い呼び出し（管理画面・移動・テスト）は判定しない。

## 実装の置き場所

- ドメイン: `domain/posting/moderation/` の `PostRateLimit`（判定）・`PostRateHistory`（板の履歴）・`ThreadRecentPosts`・`PostRateSettings`・`BypassLevel`
- DO の変換: `durable/codec/PostRateCodec`
- 配線: `PostingService.postRateCheck`、試行モードの出力は `ports/PostRateLog`、文言は `PostingText`
