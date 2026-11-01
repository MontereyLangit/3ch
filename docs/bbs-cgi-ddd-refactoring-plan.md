# bbs.cgi 投稿処理の DDD 化リファクタリング計画

2026-11-01。[認証・PHOEBE・連投規制 設計](auth-phoebe-rate-limit-design.md)（以下「設計書」）を載せる前提として、
`bbs.cgi` の投稿処理（`handler/PostingHandler.scala`。`feature/board_top` では `handler/Posting.scala`）を
ドメインモデル・ドメインサービス・アプリケーションサービス・リポジトリ・DO に分ける。**状態: 計画（未着手）。**

## 0. 前提と不変条件

| 項目 | 扱い |
| --- | --- |
| 起点 | `develop_v6s`（スレ立て規制の `domain/` と `durable/codec` `durable/support` を含む）と `feature/board_top`（`Posting.scala` への改名、`common/domain/BbsSettings` `BbsSubjects` `ThreadTitle`、`repository/BoardRepository`、`@route` / `@islands` 注釈、`components/`）。両者の分岐点は `0c405af` で、`board_top` はスレ立て規制を含まない。段 0 で統合する |
| 振る舞い | **HTTP レベルの入出力をバイト単位で変えない。** dat / log / subject / R2 の書き込み内容、応答本文・ヘッダ・文字コード・エラー文言（`guarded` の内部エラー文言の書式を含む）も同じ。既知不具合（KB-03・KB-09・KB-10・KB-24・KB-25 など）は直さず残す。直すときは AGENTS.md 3.3 に従い別コミット |
| 検証の軸 | 既存 `PostingHandlerSuite`（約 70 件、HTTP レベルのキャラクタリゼーション）を**改名以外は変更せずに通す**。段 0 で入出力スナップショットを足す（§5） |
| 規約 | AGENTS.md 3.5（DO はインフラ操作のみ、波括弧、短い doc コメント）。`common` は JVM/JS クロスで I/O と `js.*` を持たない |
| 対象外 | 公開ルート（dat / subject.txt 等）、管理画面、`3chiko.html`。DO のストレージキー・形式 |

## 1. 現状の問題

- **`g: js.Dynamic` の可変バッグ**: 40 個超のフィールドを 15 段の関数が読み書きする。型がなく、段の順序依存（`makeId` は `identify` の後、`threadCreationLimit` は `checkSizes` の後）がコードに現れない。
- **エラーの表現**: `deny(g, 文言)` が `g.error` に文言を入れて `Rejected` を投げる。文言の組み立てが各段に散らばり、判定（ドメイン）と文言（表示）が分かれていない。
- **設定の読み方が 3 通り**: `Posting.parseSettings`（`<>` / `\n` 区切り、2 つ目の `=` 以降を捨てる）、`ThreadCreationSettings.settingsMap`（trim・後勝ち）、`BbsSettings.parse`（`board_top`。値 trim・最初の `=` で分割）。KB-34 / KB-35 / KB-36 はこの差から生じた。
- **ドメイン規則の置き場所**: ID・識別子の種の組み立て、トリップの分岐、キャップ表示名、dat 行の整形、sage 判定が handler の中にあり、Thread DO の `addRes` にも 1000 件上限・`BBS_HEISA`・同一 key・スレ不在の判定が入っている（3.5 の「DO はインフラ操作だけ」に反する）。
- **I/O の混在**: R2 の `SETTING.TXT` / `deny.cgi` / 過去ログ目次 / `headline.txt`、KV `CAP`、Thread / Board DO の呼び出しが handler に直書きで、テストはすべて HTTP レベル（`HandlerTestKit` で `Date` / `Math.random` / `caches` を差し替えるため `parallelExecution := false`）。
- **`js.Dynamic` が RPC 境界を越える**: `Thread.addRes(dat, log, posting: js.Dynamic)` の `posting` に板名・key・sage・初期設定・スレ立て規制の材料が入り、DO 側で `truth(p.create)` のように読む。
- **設計書の載せ先がない**: 認証主体の決定（§3.4）、PHOEBE ゲート（§4.4）、識別子の新書式（§5）、回避レベル（§7）、連投規制（§8）はいずれも「段」として差し込む必要があり、現状ではまた `g` にフィールドを増やすしかない。

## 2. 目標構造

```
common/src/main/scala/ch3s/common/
  domain/
    BoardId, ThreadKey, ThreadTitle, BbsSettings（統合後の唯一の設定モデル）
    posting/   PostKind（NewThread / Reply）, PostForm（サニタイズ済み入力）, DatLine, DatLineFormatter,
               NameField（名前＋トリップ＋識別子の連結規則）, CapHandle（★・強キャップ・表示名）, TripAlgorithm
    identity/  IdSeeds（ID・県・市・AS・UA の種文字列）, ShikibetsuMode（checked / raw / ken / city / as / d / phoebe）,
               ShikibetsuFormat（書式）, DateSalt（BBS_ID_RESET 境界）
    account/   設計書 §9.1 の Level / Phoebe / Roles / BypassLevel（段 10 で追加）
  text/        Utf8（既存）, HtmlEntity（正規表現を使わない文字参照の復元）
bbs/src/main/scala/ch3s/bbs/
  domain/posting/
    PostingContext（既存を拡張）, PostingError（拒否理由の enum。文言は持たない）, PostingOutcome,
    FormSanitizer / PostRouting（js.RegExp を内部に閉じ込めた純粋関数）, UserInfo（log の userInfo 文字列）
    moderation/  ThreadCreationRateLimit / ThreadCreationHistory（既存）, ModerationRuleSet / ModerationDecision,
                 PostRateLimit / PostRateHistory / CopipeGuard（設計書 §8。段 10）
  application/posting/
    PostingService（ユースケース。段の並びを 1 か所で定義）, ports/（ThreadStore, BoardStore, SettingsStore,
    RuleStore, CapStore, ArchiveIndexStore, Hasher, Clock, Random）
  repository/
    BoardRepository（既存）, ThreadRepository, ModerationRuleRepository, CapRepository, ArchiveIndexRepository
    （ports の実装。DO stub / R2 / KV / Web Crypto を包む。`js.Dynamic` はここで止める）
  durable/      Board, Thread（ロック・読込・コーデック・保存だけ）, codec/（PostingCodec を追加）, support/
  handler/      Posting（HTTP アダプタ: Request → PostingRequest、PostingOutcome → Response）, PostingPages, PostingText
```

**`common` と `bbs/domain` の線引き**: 現行コードは JS の `RegExp` を意図して使う（JVM の正規表現と受理言語が違う。`Moderation` のコメント参照）。
正規表現に依存する純粋ロジック（`readForm` のサニタイズ、トリップ抽出、`!newthread`、deny.cgi のルール照合）は **`bbs/domain`** に置き、
`js.RegExp` をオブジェクト内部に閉じる（`js.Dynamic` は使わない）。正規表現を使わないもの（dat 行整形、種の組み立て、設定の型付き読み、
名前欄の連結、トリップ方式の分類、ID 計算の日付境界）は **`common`** に置き、JVM でもテストする。

**非同期ハッシュ**: Web Crypto（`WebHash`）は非同期なので、ドメイン関数は「種の文字列」や「ハッシュ済みの値」を受け取る純粋関数にし、
ハッシュの実行は `Hasher` ポート経由でアプリケーションサービスが行う（スレ立て規制の `digest` と同じ分け方）。

## 3. 境界の型

| 型 | 層 | 内容 |
| --- | --- | --- |
| `PostingRequest` | handler → application | メソッド・Content-Type・Referer・UA・`cf`（asn / country / region / city）・Cookie・Client Hints・`CF-Connecting-IP`・forwarded 系・本文バイト列。`Request` から 1 回で作る |
| `PostForm` | domain | `FROM` `mail` `MESSAGE` `subject` `bbs` `key` `time` `submit` のサニタイズ済み値と `isSJIS` `isMonazilla`。`FormSanitizer.decode(bytes, isMonazilla)` が `Either[PostingError, PostForm]` で返す |
| `PostingContext` | domain | 既存の `board` `threadKey` `creatingThread` `title` `identity` に、`kind`、`number`、`unixtime`、`settings: BbsSettings`（板＋スレの合成）、`poster`（名前欄の構成要素: 名前・トリップ・キャップ・識別子）、`id`、`sage` を足す。段が進むたびに `copy` で埋める不変オブジェクト |
| `PostingError` | domain | `enum`。`MethodNotPost`、`BrokenBody(contentType)`、`InvalidReferer(referer)`、`InvalidUserAgent(ua)`、`ProxyHeader(clientIp)`、`NoSubmit`、`SjisFromWeb`、`TripKeyTooLong`、`ForbiddenBoard`、`EmptyMessage`、`MissingSubject(key)`、`InvalidTime(time)`、`FieldTooLong(field, len, limit)`、`TooManyLines(lines, limit)`、`Closed`、`ThreadMissing(logFile)`、`BrokenLog(kind)`、`ThreadFull`、`DuplicateKey`、`Moderated(kind)`、`ThreadCreation(rejection)`、`Internal(stage, description, clock, cause)` …。動的な部分（UA・件数など）はフィールドで持ち、文言は `PostingText.message(error, context)` が組み立てる。現行文言と 1 字違わないことを既存テストで固定 |
| `PostingOutcome` | application → handler | `Posted(key, number, headers)`、`Rejected(error)`。設計書の吸い込み（§8.5）は `Swallowed(key, number)` として後で足す |
| ports | application | `trait ThreadStore { def readForReply(board, key): Future[ThreadSnapshot]; def addRes(…): Future[Either[code, Int]] }` のように Future を返す薄いインターフェース。`Clock.nowMillis`、`Random.next` も同じ扱い |

## 4. 段階

各段は独立したコミット（必要なら複数）で、段の終わりに §5 の検証を全部通す。段を飛ばさない。

| 段 | 内容 | 主な成果物 |
| --- | --- | --- |
| 0 | **ブランチ統合と足場**: `develop_v6s` を `feature/board_top` に取り込む（`Posting.scala` と `threadCreationLimit`、`ThreadCreationSettings` と `BbsSettings` の競合を解消）。設定の読みを `BbsSettings` に一本化（`settingsMap` の「キーも trim・後勝ち」を `BbsSettings.parse` に寄せ、`BbsSettingsSuite` に境界ケースを足す）。入出力スナップショットテストを追加（§5） | 統合済みブランチ、`BbsSettings` 統合、スナップショット |
| 1 | **エラーモデル**: `deny(g, 文言)` を `PostingError` と `PostingText.message` に置き換える。`Rejected` 例外は `PostingService` の内部実装に閉じる。`guarded` の内部エラー文言は `Internal` の文言化で同じ書式を再現する | `domain/posting/PostingError.scala`、`PostingText` の文言表、`PostingTextSuite`（全エラーの文言一致） |
| 2 | **入力の型付け**: `PostingRequest.from(Request)`、`FormSanitizer`（`readForm` の置換列・Shift_JIS 復号・`submit=書` 判定）、`PostRouting`（`!newthread`、924 / 900、禁止板、スレ立て/返信の判定）。`g.form` を `PostForm` に置き換える | `bbs/domain/posting/{FormSanitizer, PostRouting}.scala`、`FormSanitizerSuite`（エスケープ・制御文字・`★☆` 変換・KB-03 の固定） |
| 3 | **設定**: `BoardRepository.getBoardSettings` と新設 `ThreadRepository.getSettings` から `BbsSettings` を取り、`PostingContext.settings` に合成。`BbsSettings` に型付き読み（`int(name, default)`、`checked(name)`）を足す。KB-12（既定値とのマージ無し）は維持 | `common/domain/BbsSettings` 拡張、`repository/ThreadRepository.scala` |
| 4 | **投稿者の構成要素**: `Trip`（`processTrip`。方式の分類は `TripAlgorithm.classify(sjisBytes)` を `common` に、DES / SHA-1 の実行はサービス）、`CapHandle`（`processCap` の表示名規則）と `CapRepository`（KV）、`IdSeeds` と `DateSalt`（`makeId` の種）、`ShikibetsuMode` / `ShikibetsuFormat`（識別子書式）、`NameField`（名前＋ `</b>◆trip</b>` ＋ ` </b>(識別子)<b>` の連結。KB-09 の空括弧も再現） | `common/domain/posting/{TripAlgorithm, CapHandle, NameField}.scala`、`common/domain/identity/*`、`repository/CapRepository.scala`、golden 照合の単体テスト |
| 5 | **規制**: `Moderation.parseRules` → `ModerationRuleSet.parse`（`js.RegExp` を内包）、`Moderation.check(gb)` → `ModerationService.check(context, rules): ModerationDecision`（`Deny(kind)` / `Mark` / `Pass`。`Mark` の名前・本文加工は `PostingContext` の `copy` で返す）、`UserInfo`（log の `userInfo` 書式。設計書 §5.3 の `UID:` / `Device:` 実値化の差し込み口）、`ModerationRuleRepository`（R2 `deny.cgi` `ngwords.cgi`） | `bbs/domain/posting/moderation/{ModerationRuleSet, ModerationDecision}.scala`、`ModerationSuite` の移設 |
| 6 | **保存**: `DatLineFormatter`（dat 行・log 行。`Intl.DateTimeFormat` の日付は `Clock` 経由で文字列にして渡す）、`ThreadRepository.addRes`（RPC 引数はこの段では変えない）、`ArchiveIndexRepository`（`updateSubback` / `updateHeadline` の R2 ETag 条件付き書き込み。KB-25 維持） | `common/domain/posting/DatLineFormatter.scala`、`repository/ArchiveIndexRepository.scala`、dat / log のバイト一致テスト |
| 7 | **アプリケーションサービス**: `PostingService.post(request): Future[PostingOutcome]` に段の並びを集約し、`Posting.entry` を「`PostingRequest` 化 → サービス → `PostingPages` で描画」だけにする。`g` を削除。`PostingPages` は `PostingContext` / `PostingOutcome` を受ける | `application/posting/PostingService.scala`、`ports/*`、`PostingServiceSuite`（メモリ上のポート実装で段の順序と `copy` の内容を検証） |
| 8 | **Thread DO の整理**（3.5）: `addRes` 内の 1000 件上限・`BBS_HEISA`・同一 key・スレ不在の判定を `common/domain/posting/ThreadWriteGuard.check(datLineCount, settings, create): Option[code]` に出し、DO はコード文字列を返す。`posting: js.Dynamic` は `durable/codec/PostingCodec` で case class に変換する。handler 側は現行と同じ文言に戻す（`errmsg:updateThreadFile->…` の中身まで一致させる） | `ThreadWriteGuard`、`PostingCodec`、`ThreadSuite` の更新（DO の責務は直列化・一括保存・失敗時の予約取消しに限定） |
| 9 | **テストの再配置**: ドメインは `common/src/test`（JVM / JS）と `bbs/src/test/.../domain`、サービスは `PostingServiceSuite`、HTTP は `PostingHandlerSuite` を縮退させず保持。`Clock` / `Random` をポートにしたことで `withFixedTime` / `withRandom` 依存の段を減らし、`Test / parallelExecution := false` の対象を `HandlerTestKit` を使うスイートだけに絞れるか確認する | テスト構成、`docs/v6s実装メモ.md` 4 章の更新 |
| 10 | **設計書の差し込み**（リファクタリング後の新機能。別作業）: 設計書 §12 の段 A（連投規制）以降は、このサービスの段として追加する。対応は §7 の表 | — |

段 1〜7 の間、`Posting.entry` は常に動く状態を保つ（段ごとに `g` のフィールドを 1 群ずつ型付きに置き換え、置き換えた群は `g` から消す）。
1 つの段の途中で `g` と型付き値が二重に存在することは許すが、段の終わりには残さない。

## 5. 検証

- `sbt test` 全件。`PostingHandlerSuite` は対象の改名（`PostingHandler` → `Posting`）以外は変更しない。golden（`tests/golden/*.json`）は変更しない。
- **入出力スナップショット**（段 0 で追加、`bbs/src/test/.../handler/PostingSnapshotSuite`）: スレ立て / 返信 × 通常 / キャップ / 強キャップ / トリップ × sage / age × UTF-8 / Shift_JIS、エラー各種（§3 の `PostingError` 全ケース）を固定時刻・固定乱数で実行し、応答本文・ヘッダ・dat・log・subject・R2 書き込み（過去ログ目次・headline）を `tests/golden/posting-snapshots.json` と完全一致で照合する。段 0 で現行コードから生成し、以後は手で編集しない。
- `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`、`npm run v6:build`、`npm run v6:typecheck`、`npm test`（実 workerd）、`git diff --check`。
- 各段でレビュー連鎖（AGENTS.md 3.2）。記録は `docs/bbs-cgi-ddd-refactoring-review.md` に段ごとの表で残す。

## 6. リスクと対処

| リスク | 対処 |
| --- | --- |
| JVM と JS で正規表現の意味が違う | 正規表現を使うロジックは `bbs/domain` に置き、JVM テストに持ち込まない。`common` へ移すときは正規表現を使わない実装に書き直し、golden で同値を示す |
| `guarded` の内部エラー文言（段名・時刻・板名・key・番号・原因文字列）が一致しない | `PostingError.Internal` に段名・説明・時計種別・原因を持たせ、文言化を 1 関数に集約。スナップショットで固定 |
| DO の RPC 境界を越えるエラー文字列（`fail("…")`）を段 8 でコード化すると handler 側の文言が変わる | コード → 従来文言の対応表を `PostingText` に持ち、`errmsg:updateThreadFile->Error: …` までスナップショットで一致を確認 |
| `board_top` との競合 | 段 0 で統合してから始める。`board_top` 側で `Posting.scala` に触る作業が並行する場合は段 0 を先に `board_top` へマージする |
| 非同期ハッシュの順序変更で ID・識別子が変わる | ハッシュの入力文字列（種）を純粋関数にして golden で固定し、サービス側は種を渡すだけにする |
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
| §12 段 A〜E | 本計画の段 10 以降。段 A（連投規制）は本計画の段 7 完了後に着手する |

## 8. 着手順の要約

1. 段 0: ブランチ統合、`BbsSettings` 一本化、スナップショット作成（現行コードから生成）。
2. 段 1〜3: エラー・入力・設定の型付け。`g` から `error` / `form` / `settings` 系を消す。
3. 段 4〜6: 投稿者構成要素・規制・保存の分離。`g` から残りを消す。
4. 段 7: `PostingService` に集約し `g` を削除。
5. 段 8〜9: DO の整理とテスト再配置。
6. 設計書 §12 の段 A へ。
