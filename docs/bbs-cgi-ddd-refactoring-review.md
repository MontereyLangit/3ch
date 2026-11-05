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

## 段 2: 入力の型付け

| 項目 | 内容 |
| --- | --- |
| 実施 | `handler/PostingRequest.scala`（`PostingRequest.from(Request)`。投稿処理が読むヘッダと `cf` を 1 回で取り出し、`validate` で method → Content-Type → Referer → UA → client-ip の順に検査。`host`・`browserUserAgent`・`isMonazilla` も持つ）、`common/.../domain/posting/PostForm.scala`（`PostForm` と `PostKind`）、`bbs/.../domain/posting/FormSanitizer.scala`（submit 判定・Shift_JIS 復号・置換列・`★◆＃` 変換・名前の `#` 以降の切り捨て・`!id` / `!no` / `showid`）、`PostRouting.scala`（`!newthread`、`<br>` 除去、板名・key・time の文字除去、共通スレ 924 / 900 の板付け替え、禁止板・本文・題名・time の検査、スレ立て／返信の決定と `!924` / `!900`）を追加。`g.form`・`g.isSJIS`・`g.isMonazilla`・`g.showId`・`g.noNusi` を削除し、ハンドラ内の `Draft(var form: PostForm)` に置き換えた。`PostingPages.respondEndHtml` / `respondError` は `PostForm` を受ける。`TextDecoder` の facade を追加 |
| 正規表現 | `FormSanitizer` / `PostRouting` の `js.RegExp` はオブジェクト内の定数に閉じ、`JSStringOps.jsReplace` で使う（`js.Dynamic` なし）。グローバルフラグ付きは `replace` にだけ使い、`test` には使わない |
| エラー画面 | 拒否時はその時点のフォームをエラー画面に出す（従来の `g.form` と同じ）。`FormSanitizer.decode` / `PostRouting.route` は `Either[(PostingError, PostForm), PostForm]` で、左にその時点のフォームを添える |
| 規制 | `Moderation.decide(gb, form, env)` は mark 適用後の `PostForm` を返す。`Moderation.check(gb)` は `ModerationSuite` 用の包み（`gb.form` に書き戻す）として残した |
| 検証 | `FormSanitizerSuite`（エスケープ・制御文字・`★☆` 変換・名前の `#` 切り捨て・`!id` / `!no`・KB-03 の前段・後勝ち・共通スレ・必須項目の順）を追加。スナップショット差分なし |
| 計画との差分 | `PostingRequest` に本文は含めない（ヘッダ検査の前に本文を読むと、本文の読み込み失敗が検査より先に出うるため、従来どおり検査後に読む）。`PostRouting` は計画の「振り分け」に加え、従来 `readForm` 後半にあった文字除去と必須項目の検査も受け持つ（順序依存のため）。`PostForm.kind` は振り分け後にだけ意味を持つ |
| 未解決 | `g.ip` / `g.host` / `g.userAgent` / `g.browserInfo` / `g.asn` 等の識別系は段 4 の対象として `g` に残る（`PostingRequest` から初期化） |

## 段 3: 設定

| 項目 | 内容 |
| --- | --- |
| 実施 | `BbsSettings` に型付き読み（`get`、`orElse`（空なら既定値。従来の `or`）、`checked`（ちょうど `checked`）、`number(name, default)`（JavaScript の `Number`）、`int(name, default)`（`orElse` の値を JavaScript の `parseInt`）、`updated`、`merged`（既存キーの位置を保って上書き、新キーは末尾。既定値とのマージはしない＝KB-12 維持））を追加。数値の解釈は `common/text/JsNumber`（`Number` / `parseInt` の文字列解釈を JVM でも同じに書いたもの）。`repository/ThreadRepository.scala`（`getSettings(board, key, env)`）を新設。`PostingHandler` の `g.settings` / `g.boardSettings` / `g.boardDisableNusi` を削除し、`Draft.settings`（板設定、返信ならスレ設定で上書き、`BBS_SHIKIBETSU=off` を足す）と、板設定だけを使う箇所（スレ立て規制・板の `BBS_DISABLE_NUSI`）用の `LoadedBoard` に置き換えた。`Moderation.decide` は `BbsSettings` を受ける |
| 振る舞いの保存 | 従来 `g.boardSettings` は `g.settings` と同じオブジェクトだったので、`BBS_MAX_MENU_THREAD` と headline の `BBS_TITLE` はスレ設定で上書きされた値を読む（そのまま）。`BBS_TITLE` が無いときの `undefined` も維持。文字数上限の文言には設定値の文字列そのもの（無ければ既定値）を出す。初期スレ設定のキー順は JavaScript のオブジェクトと同じ（整数のキーが先）になるよう、保存直前に `js.Dictionary` を通して並べる |
| 検証 | `BbsSettingsSuite` に型付き読み・`merged` を追加（JVM / JS）。`JsNumberSuite`（JVM / JS）と、実行環境の `Number` / `parseInt` と突き合わせる `JsNumberRuntimeSuite`（JS のみ）を追加。スナップショット差分なし |
| 計画との差分 | 計画書は合成した設定を `PostingContext.settings` に置くとしているが、`PostingContext` はまだスレ立て規制の入力としてしか作られていない（実行中の状態を持つ不変オブジェクトにするのは段 7）。段 3 ではハンドラ内の `Draft.settings` に置き、段 7 で `PostingContext` に移す。`int(name, default)` の既定値は従来の `or` に合わせて文字列で受ける |
| 未解決 | `Moderation.check(gb)`（`ModerationSuite` 用の包み）は `gb.settings` から `BbsSettings` を組み立てる。段 5 で整理する |

## 段 4: 投稿者の構成要素

| 項目 | 内容 |
| --- | --- |
| 実施 | `common/domain/posting/CapHandle`（照合済みキャップの強弱・表示名・メールのパスワード部分）と `NameField`（名前・ユーザー ID 表示・識別子の連結）、`common/domain/identity/DateSalt`・`ShikibetsuSeeds`・`ShikibetsuMode`・`ShikibetsuFormat`、`repository/CapRepository`（KV 読込）を追加。全板共通スレの拒否判定は既存 `GlobalThread` の `postingError` に移した |
| 型付き状態 | リクエスト由来の IP・host・UA・Client Hints・端末情報・地域・ハッシュ用ソルトを `PostingIdentity` に保持。`Draft` にキャップ・識別子・スレ主を置き、対応する `g` のフィールドを削除した。ヘッダ検査前は元の IP・UA を保持し、検査後にだけ表示用 host と Client Hints 連結済み UA に更新する |
| 振る舞いの保存 | 通常・`!` 付きキャップの照合方式、PON・識別子・スレ主・スレ立て規制のハッシュ入力と実行順は維持。日付窓の計算は純粋関数、数値の文字列化はハンドラの JavaScript `String` に残した。強キャップは最初の `★` から最初の改行までを除く従来の `/★.*/` と同じ規則。KB-09 の空括弧も残す |
| レビュー | 実装者のセルフレビューと独立レビュー、オーケストレーターの統合レビューを実施。スレ主のデコードを設定読込時へ前倒しした点を修正し、`Draft` の遅延変換を既存の `markNusi` の位置でだけ呼ぶようにした。キャップの迂回・閉鎖・満杯の拒否順が壊れた `owner` にも影響されないことを別スイートで確認する |
| テスト | `CapHandleSuite`・`NameFieldSuite`・`ShikibetsuSuite`（JVM / JS）、`GlobalThreadSuite` の拒否優先順、`PostingOwnerCompatibilitySuite` を追加。既存 `PostingHandlerSuite`・スナップショット・golden の期待値は変更していない |
| 検証 | `sbt test`（JVM 43・common JS 45・codegen 27・bbs 505、計 620 件）、`sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`（実 workerd を含む 46 件、スキップなし）、`git diff --check` が成功。投稿スナップショット 196 ケースは全件一致。クリーンなチェックアウトでは `IslandComponents` が未生成なので、先に `sbt codegen/run` を実行した |
| 計画との差分 | 既存 `PostingUser`・`NusiMark`・`ThreadOwner` は `bbs/domain` に置いたまま利用し、`NameField` には `PostingUser.nameSuffix` の結果を渡す。` (主)` は従来の段で既存 `NusiMark` を使って付ける。`Moderation.decide` は投稿者情報の入力だけを型付き引数に変更し、ルールの分離（段 5）は行っていない |
| 次の段 | 段 5（規制・`UserInfo`・R2 の規制ルール読込の分離）。アプリケーションサービスへの集約と `g` 全廃は段 7 |

## 段 0a〜4 の再レビュー（2026-11-05）

| 観点 | 指摘と対応 |
| --- | --- |
| 数値の互換性・テスト | `JsNumber` の `BigDecimal` による変換で負のゼロの符号が失われ、Scala.js では巨大な指数が例外になっていた（KB-41）。十進構文を検査して `Double.parseDouble` で読むように変更。負のゼロは逆数の符号も比較し、指数・非正規化数・丸め境界と日付窓を JVM / JS で検証する。HTTP 回帰テストは巨大指数の文字数設定と負のゼロの日付窓を扱う |
| DDD・エラーモデル | `InvalidCreationSettings` の日本語文字列を `ThreadCreationSettingError` に、`Internal` の文字列段名と説明を `PostingStage` に置換。表示文言と診断用の段名は `PostingText` に集約した。管理画面も同じ設定エラーの変換を使い、表示内容を維持する |
| 純粋関数・依存方向 | `handler/PostingFormDecoder` がバイト列・パーセント符号化を復号し、ドメインの `FormSanitizer.fromFields` が文字列を整形する。ドメインから `TextDecoder` と `SjisEncoding` への依存を削除。復号前の submit 検査、不正なパーセント符号化、重複項目、UTF-8 / Shift_JIS を別スイートで検証する |
| Scaladoc・コメント | `ThreadCreationSettings.fromSettings` などを現在の契約の説明に絞り、互換化の経緯はこの記録に残した。型付きエラーが表示文言を保持しなくなり、Scaladoc と実装の食い違いも解消した |
| レビュー | 実装者のセルフレビュー、独立レビュー、オーケストレーターの統合レビューを実施。追加の修正指摘なし。既存 `PostingHandlerSuite`・スナップショット・golden は変更していない |
| 検証 | `sbt test` 計 634 件、`npm run pretest`、`npm run test`（実 workerd を含む 46 件、スキップなし）、`npm run v6:typecheck`、`sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`、`git diff --check` が成功。既存の投稿スナップショット 196 ケースは全件一致。`npm run dev` はローカル専用の salt を指定して起動し、トップと投稿エラー応答の HTTP 200、管理者パスワード未設定時の既定の HTTP 503 を確認して停止した |

## 段 0a〜4 のレビュー指摘への対応

| 指摘 | 対応 |
| --- | --- |
| コメントの文体 | このブランチで足した doc コメントとコメントの末尾の句点を外し、バッククォートを紛らわしい記号を含む箇所（スナップショット再生成のコマンド）だけに減らした。説明調の言い回しを 1〜2 行に詰めた |
| Scaladoc の不足 | ShikibetsuMode と parse、CapHandle.fromStored、CapRepository、ThreadRepository.stub、BbsSettings.get・updated に加え、PostingRequest の browserInfo・isMonazilla・from、PostingFormDecoder.decode、PostingText.creationSettingsMessage に 1 行の doc を足した |
| Moderation.decide の var | ルールの走査を foldLeft で（拒否理由, mark 適用後のフォーム）に畳み込み、ファイルごとに次のファイルへ渡す形にした。拒否の後のルールは評価しない。照合は従来どおり mark を付ける前の内容で行う |
| 文字列で表した種別 | FailureSite.clock を FailureClock（Time・Unixtime）にした。文言は label で出し、PostingTextSuite とスナップショットで同じ文言を確認した |
| 正規表現の生成 | PostingRequest の validate・isMonazilla の正規表現を companion の private val にした |
| テストの整理と不足 | PostRouting のテストを PostRoutingSuite に移した。PostingRequestSuite でヘッダ検査の判定順、referer・UA の境界、host、Client Hints の連結、Monazilla 判定、cf の欠損を固定した。ModerationSuite には書き換え前に mark の重複・拒否後の打ち切り・ファイルをまたぐ mark・照合に使う内容のテストを足した |
| スタイル | GlobalThread.postingError を同じファイルの if ... then に揃えた |
| 検証 | sbt test 計 647 件（投稿スナップショット 196 ケース全件一致）、-no-indent の Test/compile、npm run v6:build、npm run v6:typecheck、npm test（実 workerd を含む 46 件、スキップなし）、git diff --check が成功。PostingHandlerSuite・スナップショット・golden は変更していない |
| 見送り | PostingFormDecoder の Shift_JIS 復号に残る var と可変バッファ、PostingHandler の Draft の var は、段 7 でアプリケーションサービスへ集約するときに扱う |

## 段 5: 規制

| 項目 | 内容 |
| --- | --- |
| 実施 | handler/Moderation を削除し、bbs/domain/posting/moderation に ModerationRuleSet（deny.cgi / ngwords.cgi の 1 行を js.RegExp に閉じた ModerationRule にし、書式の誤りは行番号と ModerationRuleProblem で返す）と ModerationService（ModerationRuleFile の順に読み、ModerationDecision の Pass(form) / Deny(denial, form) を返す）を追加。R2 の読込は repository/ModerationRuleRepository（Missing / Broken / Loaded）。log と規制の対象欄に使う投稿者情報は common/domain/posting/UserInfo にし、g.userInfo を削除した |
| 振る舞いの保存 | 照合は mark を付ける前の内容で行い、拒否の後のルールと後のファイルは読まない。拒否時もそれまでの mark を付けたフォームをエラー画面に出す（従来どおり）。R2 の get の失敗は Promise の reject のまま、本文の読込失敗と書式の誤りは BrokenRules。文字参照の復元は JavaScript の parseInt / Number / String.fromCodePoint を BigInt と Character.toChars に置き換え、範囲外の参照を残す扱いも同じにした。スナップショット差分なし |
| 管理画面 | 規制ルールの保存時の検証は ModerationRuleSet.parse を使い、文言は AdminText.ruleError で従来どおり「N行目: …」にする |
| テスト | ModerationSuite を ModerationRuleSetSuite・ModerationServiceSuite（読んだファイルの記録、saku 板、対象欄の書式、文字参照の境界を追加）・ModerationRuleRepositorySuite に移設。UserInfoSuite（JVM / JS）、AdminHandlerSuite に規制ルールの誤りの文言を追加 |
| 計画との差分 | 計画書は ModerationDecision を Deny / Mark / Pass としていたが、mark は拒否時にも残るので、Pass と Deny がどちらも加工後のフォームを持つ形にした。UserInfo は正規表現を使わないので計画書 §2 の bbs/domain ではなく common に置いた。Main の parseRules / checkModeration の書き出し（v5 互換の名残で、どこからも使われていない）を削除した |

## 段 6: 保存

| 項目 | 内容 |
| --- | --- |
| 実施 | common/domain/posting/DatLineFormatter（最初の <br> の前後の空白、dat 行、log 行）、application/posting/ports/Clock（現在時刻と Intl.DateTimeFormat による dat の日付欄。段 7 のポートの先行分）、bbs/domain/posting/ThreadPosting（addRes の付帯情報）、durable/codec/PostingCodec、ThreadRepository.addRes、repository/ArchiveIndexRepository（過去ログ目次と headline.txt の読込と ETag 条件付き書き込み）を追加。ThreadCreationCodec に encodeRequest と ThreadCreationLimit を足し、Thread DO の addRes は PostingCodec.decode で case class にしてから使う。g.headers・g.path・g.logPath・g.logFile・g.logData を削除した |
| 応答ヘッダ | Responses.plain と RendererShell の extra を Seq[(String, String)] にした。名前ごとに既存の値を消してから append するので、1 本なら従来の set と同じで、同名を並べれば Set-Cookie も複数出る |
| 振る舞いの保存 | RPC の外形（プレーンオブジェクトと同じ項目名）は変えず、DO スタブの署名も変わらないので codegen の生成物はそのまま。旧形式は従来の読み方に合わせ、null と省略は付帯情報なし、真偽は JavaScript の真偽判定、板名・key が無ければ String(undefined) の名前で Board DO を呼ぶ。初期スレ設定・スレ立て規制・スレ主は create が真で値が真のときだけ読む。subject.txt の空 ETag と未作成時の doesNotExist（KB-25）、headline の条件と結果を見ない扱いも維持。スナップショット差分なし |
| テスト | DatLineFormatterSuite（JVM / JS）、PostingCodecSuite（往復、外形、旧形式・真偽判定・create でない場合）、ArchiveIndexRepositorySuite（ETag 競合、空 ETag と KB-25、headline）、ResponsesSuite（Set-Cookie の複数と 1 本のときの出力）。ThreadSuite・ThreadCreationSuite は変更なしで通る |
| 計画との差分 | 計画書は日付文字列を Clock 経由で渡すとしているので、ポートの Clock だけを段 6 で入れ、ハンドラは Clock.system を使う。Thread DO の旧ログ変換（convertLegacy）の日付整形は対象外のまま。旧形式で title が null のときは undefined として渡す（スレ一覧に既存行があれば既存の題名を使う従来の扱いに揃う。既存行が無い場合だけ従来の "null" が "undefined" になるが、ハンドラは常に文字列を渡す） |
| 検証（段 5・6 と下記 doc 補足の後） | sbt test 計 681 件（common JVM 53・common JS 50・codegen 27・bbs 551。投稿スナップショット 196 ケース全件一致）、-no-indent の Test/compile、npm run v6:build、npm run v6:typecheck、npm test（実 workerd を含む 46 件、スキップなし）、git diff --check が成功。PostingHandlerSuite・スナップショット・golden は変更していない |
| 未解決 | subject.txt と headline.txt の本文の組み立て、sage の判定、g.subject・g.number・g.sage・g.time・g.unixtime・g.nowTime・g.cookies は段 7 でサービスへ移す |

## domain の Scaladoc の補足

bbs/domain と common/domain の公開型・公開メソッドで doc が無かったもの（GlobalThread.Kind・kindOf・canPost、KeyIp.of、NusiMark、PostingError の一部の場合と code、ThreadCreationRejection・ThreadCreationRecord・ThreadCreationRateLimit の Settings / Entry、ThreadCreationSettingError、BoardId・ThreadKey・ThreadTitle・BbsSubject の create、SubjectText.decode、PostingResponse.classify、DateSalt・ShikibetsuFormat・ShikibetsuSeeds、CapHandle.displayName、NameField.withIdentity、UserInfo.text など）に 1 行の doc を足した。コードと既存の doc の意味は変えていない。
