# bbs.cgi 投稿処理の DDD 化リファクタリング計画

2026-11-01。[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md)（以下「設計書」）を載せる前提として、
`bbs.cgi` の投稿処理（`bbs/src/main/scala/io/_3ch/bbs/handler/PostingHandler.scala`）を
ドメインモデル・ドメインサービス・アプリケーションサービス・リポジトリ・DO に分ける。**状態: 段 6 まで完了（段 0a・0b・1・2・3・4・5・6。検証・レビュー記録は [bbs-cgi-ddd-refactoring-review.md](bbs-cgi-ddd-refactoring-review.md)）。**
2026-11-02 の Opus 5 セルフレビュー（[auth-phoebe-review.md](auth-phoebe-review.md)）の指摘を反映した。
2026-11-05、`develop` の取り込みに合わせて前提を改めた（`develop_v6s` と `feature/board_top` は PR #37 で統合済み、パッケージは `io._3ch.*`）。
同日、トリップと ID の廃止・`!id`・スレ主表示・全板共通スレ（develop 3775f12）の取り込みに合わせて §2〜§5 を改めた。

## 0. 前提と不変条件

| 項目 | 扱い |
| --- | --- |
| 起点 | `develop`（2026-11-05 時点。`develop_v6s` のスレ立て規制・ドメイン型と `feature/board_top` の `BbsSettings`・`BoardRepository`・`components/`・islands は PR #37 で統合済み。投稿処理は `PostingHandler.scala`） |
| 振る舞い | **HTTP レベルの入出力をバイト単位で変えない。** dat / log / subject / R2 の書き込み内容、応答本文・ヘッダ・文字コード・エラー文言（`guarded` の内部エラー文言の書式を含む）も同じ。既知不具合（KB-03・KB-09・KB-10・KB-24・KB-25 など）は直さず残す。直すときは AGENTS.md 3.3 に従い別コミット |
| 検証の軸 | **現在の `develop`（本番相当）で**入出力スナップショットを作り（§5）、以後の段でこれを通す。`PostingHandlerSuite`（73 件）は段 1 以降、対象の改名以外は変更しない |
| 規約 | AGENTS.md 3.5（DO はインフラ操作のみ、波括弧、短い doc コメント）。`common` は JVM/JS クロスで I/O と `js.*` を持たない |
| 対象外 | 公開ルート（dat / subject.txt 等）、管理画面、`3chiko.html`。DO のストレージキー・形式 |

## 1. 現状の問題

- **`g: js.Dynamic` の可変バッグ**: 40 個超のフィールドを 15 段の関数が読み書きする。型がなく、段の順序依存（`makeShikibetsu` は `identify` の後、`markNusi` は `makeShikibetsu` の後、`threadCreationLimit` は `checkSizes` の後）がコードに現れない。
- **エラーの表現**: `deny(g, 文言)` が `g.error` に文言を入れて `Rejected` を投げる。文言の組み立てが各段に散らばり、判定（ドメイン）と文言（表示）が分かれていない。
- **設定の読み方の名残**: 統合前は 3 通りの読み方があり、KB-34〜36 はその差から生じた。`develop` では `BbsSettings` にほぼ揃ったが、スレ立て規制だけが `BbsSettings` の出力を `ThreadCreationSettings.settingsMap` で読み直している。
- **ドメイン規則の置き場所**: 識別子の種の組み立て、キャップ表示名、名前欄コマンド（`!id` / `!no`）とスレ主表示の連結、dat 行の整形、sage 判定が handler の中にあり、Thread DO の `addRes` にも 1000 件上限・`BBS_HEISA`・同一 key・スレ不在の判定が入っている（3.5 の「DO はインフラ操作だけ」に反する）。
- **I/O の混在**: R2 の `SETTING.TXT` / `deny.cgi` / 過去ログ目次 / `headline.txt`、KV `CAP`、Thread / Board DO の呼び出しが handler に直書きで、テストはすべて HTTP レベル（`HandlerTestKit` で `Date` / `Math.random` / `caches` を差し替えるため `parallelExecution := false`）。
- **`js.Dynamic` が RPC 境界を越える**: `Thread.addRes(dat, log, posting: js.Dynamic)` の `posting` に板名・key・sage・初期設定・スレ立て規制の材料が入り、DO 側で `truth(p.create)` のように読む。
- **設計書の載せ先がない**: 認証主体の決定（§3.4）、PHOEBE ゲート（§4.4）、識別子の新書式（§5）、回避レベル（§7）、連投規制（§8）はいずれも「段」として差し込む必要があり、現状ではまた `g` にフィールドを増やすしかない。

## 2. 目標構造

```
common/src/main/scala/io/_3ch/common/
  domain/
    BoardId, ThreadKey, ThreadTitle, BbsSettings（統合後の唯一の設定モデル）
    posting/   PostKind（NewThread / Reply）, PostForm（サニタイズ済み入力）, DatLine, DatLineFormatter,
               NameField（名前＋`(@ユーザーID)`＋識別子＋` (主)` の連結規則）, CapHandle（★・強キャップ・表示名）,
               UserInfo（log の userInfo 文字列）
    identity/  ShikibetsuSeeds（県・市・AS・UA の種文字列）, ShikibetsuMode（checked / raw / ken / city / as / d / phoebe）,
               ShikibetsuFormat（書式）, DateSalt（BBS_ID_RESET 境界。識別子の日付窓に残る）
               （設計書 §5.2 の UA 解析 `UserAgentProfile` は v3 の正規表現表を使うため bbs/domain に置く）
    account/   設計書 §9.1 の Level / Phoebe / Roles / BypassLevel（段 10 で追加）
  text/        Utf8（既存）, HtmlEntity（正規表現を使わない文字参照の復元）
bbs/src/main/scala/io/_3ch/bbs/
  domain/posting/
    PostingContext（既存を拡張）, PostingError（拒否理由の enum。文言は持たない）, PostingOutcome,
    FormSanitizer / PostRouting（js.RegExp を内部に閉じ込めた純粋関数）, ThreadPosting（addRes の付帯情報）
    moderation/  ThreadCreationRateLimit / ThreadCreationHistory（既存）, ModerationRuleSet / ModerationService / ModerationDecision,
                 PostRateLimit / PostRateHistory / CopipeGuard（設計書 §8。段 10）
  application/posting/
    PostingService（ユースケース。段の並びを 1 か所で定義）, ports/（ThreadStore, BoardStore, SettingsStore,
    RuleStore, CapStore, ArchiveIndexStore, Hasher, Clock, Random）
  repository/
    BoardRepository（既存）, ThreadRepository, ModerationRuleRepository, CapRepository, ArchiveIndexRepository
    （ports の実装。DO stub / R2 / KV / Web Crypto を包む。`js.Dynamic` はここで止める）
  durable/      Board, Thread（ロック・読込・コーデック・保存だけ）, codec/（PostingCodec を追加）, support/
  handler/      Posting（HTTP アダプタ: Request → PostingRequest、PostingOutcome → Response）, PostingFormDecoder, PostingPages, PostingText
```

**`common` と `bbs/domain` の線引き**: 現行コードは JS の `RegExp` を意図して使う（JVM の正規表現と受理言語が違う。`Moderation` のコメント参照）。
正規表現に依存する純粋ロジック（`readForm` のサニタイズ、名前欄の `#` 以降の切り捨て、`!newthread`、deny.cgi のルール照合）は **`bbs/domain`** に置き、
`js.RegExp` をオブジェクト内部に閉じる（`js.Dynamic` は使わない）。正規表現を使わないもの（dat 行整形、種の組み立て、設定の型付き読み、
名前欄の連結、識別子計算の日付境界）は **`common`** に置き、JVM でもテストする。

**非同期ハッシュ**: Web Crypto（`WebHash`）は非同期なので、ドメイン関数は「種の文字列」や「ハッシュ済みの値」を受け取る純粋関数にし、
ハッシュの実行は `Hasher` ポート経由でアプリケーションサービスが行う（スレ立て規制の `digest` と同じ分け方）。

## 3. 境界の型

| 型 | 層 | 内容 |
| --- | --- | --- |
| `PostingRequest` | handler → application | メソッド・Content-Type・Referer・UA・`cf`（asn / country / region / city）・Cookie・Client Hints・`CF-Connecting-IP`・forwarded 系・本文バイト列。`Request` から 1 回で作る |
| `PostForm` | domain | `FROM` `mail` `MESSAGE` `subject` `bbs` `key` `time` `submit` のサニタイズ済み値と `isSJIS` `isMonazilla`。HTTP 境界の `PostingFormDecoder.decode(bytes, isMonazilla)` が復号し、ドメインの `FormSanitizer.fromFields` が文字列を整形する。拒否時はその時点のフォームを添える |
| `PostingContext` | domain | 既存の `board` `threadKey` `creatingThread` `title` `identity` に、`kind`、`number`、`unixtime`、`settings: BbsSettings`（板＋スレの合成）、`poster`（名前欄の構成要素: 名前・キャップ・`!id` / `!no`・識別子・スレ主）、`sage` を足す。段が進むたびに `copy` で埋める不変オブジェクト |
| `PostingError` | domain | `enum`。`MethodNotPost`、`BrokenBody(contentType)`、`InvalidReferer(referer)`、`InvalidUserAgent(ua)`、`ProxyHeader(clientIp)`、`NoSubmit`、`SjisFromWeb`、`ForbiddenBoard`、`EmptyMessage`、`MissingSubject(key)`、`InvalidTime(time)`、`FieldTooLong(field, len, limit)`、`TooManyLines(lines, limit)`、`Closed`、`ThreadMissing(logFile)`、`BrokenLog(kind)`、`ThreadFull`、`DuplicateKey`、`GlobalThreadCreateCapOnly`、`GlobalThreadCapOnly`、`Moderated(kind)`、`InvalidCreationSettings(reason)`、`ThreadCreation(rejection)`、`Internal(stage, site, cause)` …。`reason` は `ThreadCreationSettingError`、`stage` は `PostingStage`。動的な部分（UA・件数など）はフィールドで持ち、日本語の文言と段名の表示は `PostingText` が組み立てる。現行文言と 1 字違わないことを既存テストで固定 |
| `PostingOutcome` | application → handler | `Posted(key, number, headers)`、`Rejected(error, headers)`、`Swallowed(key, number, headers)`。`headers` は `Seq[(String, String)]`（同名複数可）。`PostingError` は `code: String` を持ち、設計書 §8.9 のコード表と `x-post-error` の値になる。`Swallowed` は最初から定義する（採番予定の番号は DO の戻り値 `{count, swallowed}` から得る） |
| ports | application | `trait ThreadStore { def readForReply(board, key): Future[ThreadSnapshot]; def addRes(…): Future[Either[code, Int]] }` のように Future を返す薄いインターフェース。`Clock.nowMillis`、`Random.next` も同じ扱い |

## 4. 段階

各段は独立したコミット（必要なら複数）で、段の終わりに §5 の検証を全部通す。段を飛ばさない。

| 段 | 内容 | 主な成果物 |
| --- | --- | --- |
| 0a | **スナップショット作成**: 現在の `develop` で §5 のスナップショットを生成し、`tests/snapshots/` に置く。これが「振る舞いを変えない」の比較基準 | スナップショット、生成コマンド |
| 0b | **設定の読み方の仕上げ**: ブランチ統合は PR #37 で済み、板設定は `BoardRepository` → `BbsSettings.create`、スレ設定は `BbsSettings.parse` で読むようになった。残るのは、スレ立て規制が `BbsSettings` の出力を `ThreadCreationSettings.settingsMap` で読み直している二重の解析で、これを `BbsSettings` の値を直接使う形にする。振る舞いが変わる場合はスナップショットの差分を意図した変更として記録し、独立コミットにする（AGENTS.md 3.3） | `ThreadCreationSettings` の整理、差分の記録 |
| 1 | **エラーモデル**: `deny(g, 文言)` を `PostingError` と `PostingText.message` に置き換える。`Rejected` 例外は `PostingService` の内部実装に閉じる。`guarded` の内部エラー文言は `Internal` の文言化で同じ書式を再現する | `domain/posting/PostingError.scala`、`PostingText` の文言表、`PostingTextSuite`（全エラーの文言一致） |
| 2 | **入力の型付け**: `PostingRequest.from(Request)`、HTTP 境界の `PostingFormDecoder`（バイト列・パーセント符号化の復号、`submit=書` 判定）、ドメインの `FormSanitizer`（置換列・名前欄コマンド）、`PostRouting`（`!newthread`、924 / 900、禁止板、スレ立て/返信の判定）。`g.form` を `PostForm` に置き換える | `handler/PostingFormDecoder.scala`、`bbs/domain/posting/{FormSanitizer, PostRouting}.scala`、復号と純粋な整形を分けた単体テスト |
| 3 | **設定**: `BoardRepository.getBoardSettings` と新設 `ThreadRepository.getSettings` から `BbsSettings` を取り、合成する（段 3 ではハンドラ内に置き、段 7 で `PostingContext.settings` に移す）。`BbsSettings` に型付き読み（`orElse`、`checked(name)`、JavaScript の `Number` / `parseInt` と同じに読む `number` / `int`、`merged`）を足す。KB-12（既定値とのマージ無し）は維持 | `common/domain/BbsSettings` 拡張、`repository/ThreadRepository.scala` |
| 4 | **投稿者の構成要素**（トリップと ID は develop で廃止済み）: `CapHandle`（`processCap` の表示名規則）と `CapRepository`（KV）、`ShikibetsuSeeds` と `DateSalt`（`makeShikibetsu` の種）、`ShikibetsuMode` / `ShikibetsuFormat`（識別子書式）、`NameField`（名前＋ ` </b>(@ユーザーID)<b>` ＋ ` </b>(識別子)<b>` ＋ ` (主)` の連結。KB-09 の空括弧も再現。既存の `NameCommands` / `PostingUser` / `NusiMark` / `ThreadOwner` を使う）、全板共通スレの判定（`GlobalThread`、`checkGlobalThread`） | `common/domain/posting/{CapHandle, NameField}.scala`、`common/domain/identity/*`、`repository/CapRepository.scala`、単体テスト |
| 5 | **規制**: `Moderation.parseRules` → `ModerationRuleSet.parse`（`js.RegExp` を内包）、`Moderation.check(gb)` → `ModerationService.check(input, load): Future[ModerationDecision]`（`Pass(form)` / `Deny(denial, form)`。mark の名前・本文加工は拒否時にも残るので、どちらも `PostForm` の `copy` で返す）、`UserInfo`（log の `userInfo` 書式。設計書 §5.3 の `UID:` / `Device:` 実値化の差し込み口）、`ModerationRuleRepository`（R2 `deny.cgi` `ngwords.cgi`） | `bbs/domain/posting/moderation/{ModerationRuleSet, ModerationService}.scala`、`common/domain/posting/UserInfo`、`repository/ModerationRuleRepository`、`ModerationSuite` の移設 |
| 6 | **保存**: `DatLineFormatter`（dat 行・log 行。`Intl.DateTimeFormat` の日付は `Clock` 経由で文字列にして渡す）、`ThreadRepository.addRes` と `durable/codec/PostingCodec`（`posting: js.Dynamic` を DO 側で case class に変換。RPC の外形は変えず、旧形式も読む）、`ArchiveIndexRepository`（`updateSubback` / `updateHeadline` の R2 ETag 条件付き書き込み。KB-25 維持）。応答ヘッダを `Seq[(String, String)]` にして `Set-Cookie` を複数出せるようにする（`Headers.append`。現行の `js.Dictionary` では 1 本に上書きされる） | `common/domain/posting/DatLineFormatter.scala`、`application/posting/ports/Clock`、`domain/posting/ThreadPosting`、`durable/codec/PostingCodec.scala`、`repository/ArchiveIndexRepository.scala`、dat / log のバイト一致テスト |
| 7 | **アプリケーションサービス**: `PostingService.post(request): Future[PostingOutcome]` に段の並びを集約し、`Posting.entry` を「`PostingRequest` 化 → サービス → `PostingPages` で描画」だけにする。`g` を削除。`PostingPages` は `PostingContext` / `PostingOutcome` を受ける | `application/posting/PostingService.scala`、`ports/*`、`PostingServiceSuite`（メモリ上のポート実装で段の順序と `copy` の内容を検証） |
| 8 | **Thread DO の整理**（3.5）: `addRes` 内の 1000 件上限・`BBS_HEISA`・同一 key・スレ不在の判定を `common/domain/posting/ThreadWriteGuard.check(datLineCount, settings, create): Option[code]` に出し、DO はコード文字列を返す。handler 側は現行と同じ文言に戻す（`errmsg:updateThreadFile->…` の中身まで一致させる）。現行の `BBS_HEISA` 判定は設定文字列への正規表現（`Thread.scala` の `(?:^|<>)BBS_HEISA=checked(?:<>|$)`）で、解析済み設定とは空白入りの値などで読み方が違うので、スナップショットに境界ケース（`BBS_HEISA= checked`・値に `<>` を含む等）を足してから統一する | `ThreadWriteGuard`、`ThreadSuite` の更新（DO の責務は直列化・一括保存・失敗時の予約取消しに限定） |
| 9 | **テストの再配置**: ドメインは `common/src/test`（JVM / JS）と `bbs/src/test/.../domain`、サービスは `PostingServiceSuite`、HTTP は `PostingHandlerSuite` を縮退させず保持。`Clock` / `Random` をポートにしたことで `withFixedTime` / `withRandom` 依存の段を減らし、`Test / parallelExecution := false` の対象を `HandlerTestKit` を使うスイートだけに絞れるか確認する | テスト構成、`docs/v6s実装メモ.md` 4 章の更新 |
| 10 | **設計書の差し込み**（リファクタリング後の新機能。別作業）: 設計書 §12 の段 A（連投規制）以降は、このサービスの段として追加する。対応は §7 の表。段 A は本計画の段 7 完了後（`PostingCodec` は段 6 で入る）。段 0b〜9 は振る舞いを変えないので、どの段の後でもデプロイできる。連投規制のデプロイ時期は段 A-1 | — |

段 1〜7 の間、`Posting.entry` は常に動く状態を保つ（段ごとに `g` のフィールドを 1 群ずつ型付きに置き換え、置き換えた群は `g` から消す）。
1 つの段の途中で `g` と型付き値が二重に存在することは許すが、段の終わりには残さない。

## 5. 検証

- `sbt test` 全件。`PostingHandlerSuite` は対象の改名（`PostingHandler` → `Posting`）以外は変更しない。golden（`tests/golden/*.json`）は変更しない。
- **入出力スナップショット**（段 0a で追加、`bbs/src/test/.../handler/PostingSnapshotSuite`）: スレ立て / 返信 × 通常 / キャップ / 強キャップ × sage / age × UTF-8 / Shift_JIS、`!id`・スレ主表示・全板共通スレ（924 / 900）、エラー各種（§3 の `PostingError` 全ケース）、`BBS_HEISA` と設定値の境界ケースを固定時刻・固定乱数で実行し、応答本文・ヘッダ・dat・log・subject・R2 書き込み（過去ログ目次・headline）を `tests/snapshots/posting/*.json` と完全一致で照合する。
  - `tests/golden/` とは分ける（golden は v5 由来で手で編集しない表。スナップショットは v6 の現行コードから生成し、意図した変更時に再生成する）。
  - Shift_JIS の本文・バイト列は base64 で載せる。`Last-Modified` と `Date` は固定時刻から決まるので正規化しない。
  - 現行の内部エラー文言に出る `undefined`（`g.nowTime` 未設定。KB-39）はそのまま固定する。
  - 生成は `sbt "bbs/testOnly io._3ch.bbs.handler.PostingSnapshotSuite -- --include-tags=update"`（環境変数ではなく引数で更新モードにする。munit が `--update` のような独自引数をスイートに渡さないため、タグ指定を使う）。再生成した差分はレビュー記録に理由を書いてからコミットする。
- `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`（実 workerd）、`git diff --check`。
- 各段でレビュー連鎖（AGENTS.md 3.2）。記録は `docs/bbs-cgi-ddd-refactoring-review.md` に段ごとの表で残す。

## 6. リスクと対処

| リスク | 対処 |
| --- | --- |
| JVM と JS で正規表現の意味が違う | 正規表現を使うロジックは `bbs/domain` に置き、JVM テストに持ち込まない。`common` へ移すときは正規表現を使わない実装に書き直し、golden で同値を示す |
| `guarded` の内部エラー文言（段名・時刻・板名・key・番号・原因文字列）が一致しない | `PostingError.Internal` に段名・説明・時計種別・原因を持たせ、文言化を 1 関数に集約。スナップショットで固定 |
| DO の RPC 境界を越えるエラー文字列（`fail("…")`）を段 8 でコード化すると handler 側の文言が変わる | コード → 従来文言の対応表を `PostingText` に持ち、`errmsg:updateThreadFile->Error: …` までスナップショットで一致を確認 |
| 並行する UI 作業との競合 | 板トップの統合は済んだ。`PostingHandler.scala` に触る UI 側の作業（投稿欄の fetch 送信など）と並行する場合は、段ごとに `develop` を取り込んでから進める |
| 非同期ハッシュの順序変更で識別子・スレ主判定が変わる | ハッシュの入力文字列（種）を純粋関数にして golden で固定し、サービス側は種を渡すだけにする |
| テスト実行時間の増加 | ドメインテストは JVM で速く回る。HTTP レベルのテストは件数を増やさず、スナップショット 1 本に集約する |
| `Thread.addRes` の引数を変えると管理画面・移動（`receiveMove`）の経路が壊れる | 段 8 まで RPC 署名を変えず、`PostingCodec` で旧形式も読めるようにする |

## 7. 設計書との対応

| 設計書 | 差し込み先（リファクタリング後） |
| --- | --- |
| §3.4 認証主体の決定、§3.3 Cookie | `PostingService` の `identify` 段。`AuthenticationService.resolve(request, form): Future[Principal]` を呼び、`PostingContext.principal` に入れる。`CapRepository` はここで使う |
| §3.5 認証要求、§4.4 `BBS_PHOEBE` | `checkSizes` の直後に `PhoebeGate.check(settings, principal): Option[PostingError]`（`common/domain/account`）。応答ヘッダは `PostingOutcome.Rejected` の `headers` で返す |
| §5 識別子 `phoebe` | `ShikibetsuMode.Phoebe` と `ShikibetsuFormat`。UA 解析は `common/domain/identity/UserAgentProfile` |
| §5.3 `UID:` / `Device:` | `UserInfo` の組み立て |
| §6.2 キャップのアカウント紐づけ | `CapRepository` が `AccountIndex` を併せて引く。`CapHandle` は変えない |
| §7 回避レベル | `ModerationRuleSet`（5 列目）と `BypassLevel.exempts(principal)`。連投規制・スレ立て規制の免除判定も同じ関数 |
| §8 連投規制 | `PostRateLimit`（純粋）、`PostRateHistory`（集約）、`CopipeGuard`。`ThreadRepository.addRes` の引数と `PostingCodec` に `rateLimit` を追加。`PostingOutcome.Swallowed` |
| §8.6 パイプライン位置 | `PostingService` の段の並びに 1 行ずつ追加。順序はサービスの 1 か所で読める |
| §5.3 `!id`、§4.4 `!バルサン` / `!SETTING` | `PostingService` の「コマンド解釈」段（`makeShikibetsu` の後）。`NameField` が `(@ユーザーID)` を連結し（develop で実装済み）、`ThreadRepository.addRes` の `commands` でスレ設定を書く |
| §4.2 `Account.recordPost` | `addRes` 成功後の段。失敗は無視（投稿は成功） |
| §3.6 エッジ防御 | `identify` の直後に `EdgeDefense.check(request.cf, principal, rules)`（規則は `RuleStore` から） |
| §12 段 A〜E | 本計画の段 10 以降。段 A（連投規制）は本計画の段 7 完了後に着手する |

## 8. 着手順の要約

1. 段 0: ブランチ統合、`BbsSettings` 一本化、スナップショット作成（現行コードから生成）。
2. 段 1〜3: エラー・入力・設定の型付け。`g` から `error` / `form` / `settings` 系を消す。
3. 段 4〜6: 投稿者構成要素・規制・保存の分離。`g` から残りを消す。
4. 段 7: `PostingService` に集約し `g` を削除。
5. 段 8〜9: DO の整理とテスト再配置。
6. 設計書 §12 の段 A へ。
