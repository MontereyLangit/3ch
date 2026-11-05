# bbs.cgi 投稿処理 DDD 化リファクタリング 作業記録

[計画書](bbs-cgi-ddd-refactoring-plan.md) の段ごとの実施記録。何をしたか、計画との差分、未解決事項を残す。

## 段 0a: 入出力スナップショット

| 項目 | 内容 |
| --- | --- |
| 実施 | `bbs/src/test/scala/io/_3ch/bbs/handler/PostingSnapshotSuite.scala` を追加し、変更前の `PostingHandler` で `tests/snapshots/posting/*.json`（1 ケース 1 ファイル）を生成した |
| 照合対象 | 応答（ステータス・全ヘッダ・本文の base64 と復号済み文字列）、Thread DO の dat / log / settings（`<板>/1700000000`・`<板>/1699990000` ほか）、Board DO のスレ一覧、R2 の `<板>/thread/1700/subject.txt`・`<板>/thread/1699/subject.txt`・`<板>/thread/subject.txt`・`headline.txt`。応答が例外で終わるケースは `failure` に例外文字列を残す |
| 固定条件 | 時刻 1700000000 秒（`withFixedTime`）、`Math.random` は既定 0.5（ケースごとに指定可） |
| ケース | スレ立て / 返信 × 通常・キャップ（弱・ハッシュ・強）・トリップ（DES・`##` 16 進・ソルト付き・`$`・SHA-1・SJIS 11/12 バイト）× sage / age × UTF-8 / Shift_JIS、識別子モード（checked / raw / ken / city / as / その他 / 空）、`BBS_ID`（checked / ip / siberia / off と `BBS_ID_LV`）、X-Forwarded-For 等・IPv6・Client Hints・cf 欠落、`BBS_LIVE`（0.1 / 0.05 / 0.5）、`BBS_FORCE_SAGE`、`BBS_HEISA` 境界（板: `= checked`・`checked<>x`・大文字、スレ: `= checked `・空・末尾 `<>` なし・後勝ち上書きで DO 側の正規表現だけが当たる場合）、計画書 §3 の `PostingError` 各種、規制（deny.cgi / ngwords.cgi の deny・md5・mark・TateDame・`BBS_NO_MADAKANA`・`BBS_NGWORD_PASS`・Tor・ルール不正）、スレ立て規制（狭域・同一タイトル・広域・設定不正各種）、内部エラー（R2 / DO の例外、subject.txt・過去ログ目次の ETag 競合、headline）。計 185 ケース |
| 更新方法 | `sbt "bbs/testOnly io._3ch.bbs.handler.PostingSnapshotSuite -- --include-tags=update"` |
| 計画との差分 | 計画書は `-- --update` を想定していたが、munit 1.0 の Scala.js ランナーは未知の引数を黙って捨て、スイートから引数を読む手段がない（`Settings` は `trimStackTraces` だけ）。そこで munit が解釈する `--include-tags=update` を使う。この指定では照合用の各ケースが除外され `update` タグ付きのテストだけが走るので、そのテストは「照合ケースが 1 件も走っていない」ときに限り全ケースを生成して書き出す（通常の `sbt test` では照合ケースが先に走るため何もしない）。環境変数・システムプロパティは使っていない |
| 未解決 | 到達できない拒否（`キー情報が不正です。`、DO 側の `同じkeyのスレッドが存在します。` / `スレッドがありません。`）はケースにしていない。deny.cgi の R2 読み込み例外は `guarded` の外なので応答にならず Promise が reject される（現行どおり `failure` として固定） |

## 段 0b: スレ立て規制の設定の読み直しをやめる

| 項目 | 内容 |
| --- | --- |
| 実施 | `PostingHandler.threadCreationLimit` が `BbsSettings.toString` を `ThreadCreationSettings.parseText` で解析し直していたのをやめ、`boardSettings` 段が返す `BbsSettings` を `ThreadCreationSettings.fromSettings` に渡す形にした。`g.boardSettingsText` を削除。`guarded` を値を返せる形（`Future[A]`）にした |
| 振る舞い | スナップショット差分なし。`BbsSettings.parse` はキー末尾の空白を残す（` BBS_THREAD_TATESUGI = 0` のキーは `BBS_THREAD_TATESUGI `）一方、旧経路の `settingsMap` は前後空白を除いていたので、`fromSettings` はキーの前後空白を除いて後勝ちにする（`error-creation-spaced-keys` で固定） |
| 計画との差分 | なし。管理画面の検証（`AdminHandler`）は SETTING.TXT 本文を直接受けるので `parseText` / `settingsMap` は残した |

## 段 1: エラーモデル

| 項目 | 内容 |
| --- | --- |
| 実施 | `bbs/.../domain/posting/PostingError.scala` に `PostingError`（enum、`code` を持つ）と補助の `PostField` / `BrokenLogKind` / `ModerationDenial` / `FailureSite` を追加。`PostingHandler` の `deny(g, 文言)` をすべて `reject(PostingError)` に置き換え、`Rejected(error)` 例外はハンドラ内に閉じた。文言は `PostingText.message(error)` に集約し、`PostingPages.respondError` は文言を引数で受ける。`g.error` を削除 |
| 内部エラー | `guarded` は `PostingError.Internal(stage, description, FailureSite(clock, time, bbs, key, number), cause)` を作る。`time` は従来どおり段によって `g.time` か `g.nowTime`（未設定なので `undefined`。KB-39）を文字列化して渡す。subject.txt / 過去ログ目次の ETag 競合は書式が違うので `ArchiveConflict(target, site)` として分けた |
| 規制 | `Moderation.decide` が `Option[ModerationDenial]` を返し、ハンドラは `PostingError.Moderated` にする。`Moderation.check`（`gb.error` に文言を入れて 0/1 を返す）は `ModerationSuite` が使うので `decide` の薄い包みとして残した（段 5 で `ModerationService` に移すときに整理する） |
| コード | 設計書 §8.9 の表に既存エラーのコードはないので、`request:*` / `form:*` / `size:*` / `thread:*` / `moderation:*` / `thread-creation:settings` / `internal:<段名の kebab-case>` とした。スレ立て規制は既存の `ThreadCreationRejection.code`。まだ応答ヘッダには載せない（振る舞いを変えないため。`x-post-error` は設計書の段で入れる） |
| 検証 | `PostingTextSuite` で全拒否理由の文言一致とコードの一意性を確認。スナップショット差分なし |
| 計画との差分 | 計画書 §3 の `DuplicateKey` は「同時スレ立て」（handler 側の `別の人が同時刻に…`）に充てた。DO 側の `同じkeyのスレッドが存在します。` は段 8 まで DO の例外文字列のまま（`Internal` の `cause` に出る） |

## develop（3775f12）の取り込み

| 項目 | 内容 |
| --- | --- |
| 経緯 | 段 2 の途中で develop にトリップ・ID の廃止、`!id` のユーザー ID 表示、スレ主表示 `(主)`、全板共通スレ 924 / 900（`GlobalThread`・`checkGlobalThread`）、DO の RPC スタブ自動生成、MD5 / crypt の npm ライブラリ化が入ったため取り込んだ。段 2 の作業はローカルブランチ `wip-stage2` に退避し、段 1 の時点（1bd13a8）に develop をマージした |
| 衝突の解決 | 「develop の振る舞いが正」とし、`PostingHandler.scala` は develop の版に段 0b（`ThreadCreationSettings.fromSettings`）と段 1（`reject(PostingError)`・`PostingText.message`）を当て直した。トリップ廃止で `PostingError.TripKeyTooLong` を削除し、全板共通スレの拒否を `GlobalThreadCreateCapOnly`（`global-thread:create-cap-only`）・`GlobalThreadCapOnly`（`global-thread:cap-only`）として追加。`PostingText` は develop の定数と段 1 の文言表を併せた |
| スナップショット | 段 0a のスナップショットは develop（3775f12）上で取り直した。develop を別の作業ツリーに出し、`PostingSnapshotSuite` を develop 向けに改訂して（トリップの方式別・`BBS_ID` 系のケースを外し、トリップ入力が捨てられる確認・`!id`（解決済み利用者・未解決・キャップ・`showid` 欄・エスケープ）・スレ主表示（IP / アカウント / IPv6 /64 / `!no` / キャップ / 板・スレの `BBS_DISABLE_NUSI`）・924 / 900 のスレ立てと書き込みを加え、Thread DO の `owner` も照合対象にした。計 196 ケース）生成し、その生成物とスイートを本ブランチに持ち込んだ。本ブランチ（段 0b・1 適用後）は全件一致し、差分はない |
| 計画書の訂正 | §1・§2・§3・段 4・§5・§6・§7 のトリップ・ID 関連（`TripAlgorithm`、`IdSeeds` の ID 部分、`BBS_ID` 系、`TripKeyTooLong`）を削り、`!id`・スレ主表示・全板共通スレを段 4 の対象に加えた |
