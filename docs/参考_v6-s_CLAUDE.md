> **【2026-09-11 中断】本プロジェクト（Scala.js 全部載せ案）の調査はユーザー指示により一旦中断した。**
> 後継の調査は **`C:\project\3ch\v6-s2`**（v5のTS版をベースにし、bg と共有ロジック・型のみ Scala）で行う。
> 本リポジトリは**実測記録として凍結**する（TS-001〜TS-013 の知見は v6-s2 から参照する）。
> 再開する場合も、まず v6-s2 の `CLAUDE.md` を読むこと。

# 3ch v6-s（Scala 3 移植版）— CLAUDE.md

**本プロジェクトの位置づけ**: `C:\project\3ch\v6`（Kotlin/KMP + Cloudflare Workers 版、Hono/React/TanStack
Router/kotlinx.html 等）の **Scala 3 技術調査移植**。「Scalaでこの規模の実プロダクトを作るとどうなるか」を
検証するための移植であり、v6の後継や置き換えではない。v6は一切変更しない（参照のみ）。

**移植範囲（重要）**:
- **完全移植**: 利用者向けの `read.cgi` と `bbs.cgi`（周辺の subject.txt/dat配信・DO層・投稿処理・
  忍法帖認証フローまで含め、実際に動く形で移植する）
- **代表機能のみ移植**: 管理画面(admin)・bg・batch は各々「代表的な画面・機能」を1〜数個選んで
  実装し、それ以外は**画面遷移・レスポンス形だけのモック**でよい（本物のロジックは書かない）
  - **admin は「v6の現状」ではなく「v6の将来像」を移植対象とする**（2026-09-08 ユーザー指示）:
    v6のadminは T-61 で **kotlinx.html のMPAへ移行済み**で、`frontend/admin` に残るSPAコードは
    未使用（T-81で本格SPA化する際の種）。v6-s では**その T-81 で予定されているSPA版**を作る。
    したがって移植先は Scalatags のMPAではなく **scalajs-react のSPA**であり、
    **ルート木は画面一覧の全画面を張る**
    （実装するのは代表画面のみ、残りはモック画面。ただし**ルートとナビゲーションは全部通す**）
- **【重要・2026-09-10 ユーザー指示】作る画面の正は `docs/screens.md`**: ユーザーから提示された画面一覧
  （v6 タスクスレ >>98/>>99）が**画面構成の正**。**v6の現状の実装や `docs/admin_IA*.md` と食い違う場合は
  `docs/screens.md` を優先する**。利用者画面・管理画面とも同じ扱い。原文と正規化した経路表は同ファイル参照
- 上記以外（過去ログ収集バッチの細部、admin の全画面等）は着手しない。深追いしない

**言語・ツールチェーンの置き換え（4点、ユーザー指定）**:
| v6 (Kotlin/KMP) | v6-s (Scala 3) |
|---|---|
| Gradle | **sbt** |
| React + TanStack Router | **scalajs-react（japgolly）+ React Router v7**（2026-09-08 確定。Laminar→Slinky→scalajs-react と2度変更。§技術判断ログ参照） |
| 各種npmライブラリの手書き external宣言（`external/*.kt`） | **ScalablyTyped** で生成したScala.js facade |
| bg の kotlinx.html テンプレート | **Scalatags**（2026-09-07 変更。当初のThymeleaf指定から差し替え） |

**上記4点以外は原則そのまま踏襲**（ユーザーから変更指示がないため）: Cloudflare Workers + Hono を
そのままエッジ実行基盤とする（Scala.js から ScalablyTyped生成のHono facadeを呼ぶ）。bg の
Spring WebFlux、batch の Spring Batch もそのまま使う（Scala 3 から Java ライブラリとして呼ぶ。
Reactor `Mono`/`Flux` は素のJava APIとして扱い、Kotlin coroutineの `coRouter`/`suspend` 相当の
糖衣構文は無い点に留意 — 必要なら cats-effect の `IO ↔ Mono` 変換ヘルパーを用意する）。
ドメイン設計（忍法帖認証、階層型ストレージ、規制方式等）は v6 の `docs/` が正であり、v6-s では
再設計しない。挙動は極力 v6 と同一になるよう移植する。

## リポジトリ構成（予定）

v6の Gradle モジュール構成を sbt サブプロジェクトへそのままマッピングする。

```
common/          Scala 3 cross-project (JVM + Scala.js, sbt-crossproject) — ドメイン共有ロジック
bbs/             Scala.js — 掲示板バックエンド (Cloudflare Workers, Hono facade経由)
frontend/
  bbs/           Scala.js — 掲示板フロント (SSR + hydration, Laminar)
  admin/         Scala.js — 管理画面SPA (Laminar)
  bg/            Scala.js — bg画面用JS (vanilla, Laminarも使わない — v6同様)
bg/              Scala 3 JVM — 管理画面連動の重量処理 (Spring WebFlux, Scalatags)
batch/           Scala 3 JVM — バッチ (Spring Batch)
docs/            設計資料・タスク管理（本ディレクトリ。v6のdocsとは独立。混在させない）
```

移植元ファイルの対応（read.cgi/bbs.cgi周辺、v6調査済み — 2026-09時点。実装時は必ず最新のv6を再確認すること）:
- `bbs/src/jsMain/kotlin/App.kt` — ルーティング定義の中心
- read.cgi: `handler/ThreadHandler.kt`
- bbs.cgi/subbbs.cgi: `handler/PostHandler.kt` + `service/PostService.kt`
- dat配信: `handler/DatHandler.kt`、subject.txt: `handler/SubjectHandler.kt`、
  head.txt: `handler/HeadTxtHandler.kt`、SETTING.TXT: `handler/SettingHandler.kt`、
  subback.html: `handler/SubbackHandler.kt`、板TOP: `handler/BoardTopHandler.kt`
- DO層: `durable/BoardDurableObject.kt` / `ThreadDurableObject.kt` / `UserDurableObject.kt` /
  `ErrorCountDurableObject.kt` + `BoardCore.kt` / `ThreadCore.kt` / `BoardTextsCore.kt` /
  `ThreadDatView.kt` + `durable/client/*DoClient.kt`
- 認証: `handler/AuthHandler.kt`（/auth）
- SSRページFC: `common/src/jsMain/kotlin/fc/pages/ReadPage.kt`（Laminarでは同等のViewとして移植）
- common ドメイン: `common/src/commonMain/kotlin/domain/{model,parser,regulation,id,trip,thread,kako,slip}/`
  （domain/配下が正。`commonMain/kotlin/` 直下に残る `entity/`,`model/`,`repository/`,`view/` は
  旧構成の残骸の可能性があり、移植時は `docs/architecture_change_plan_202607.md` 等で現行かどうか
  都度確認する）

admin/bg/batch の代表機能候補（実装順に着手、詳細は `docs/tasks.md` 参照）:
- admin: ログイン + ダッシュボード(`fc/DashboardPage.kt`,`LoginPage.kt`相当) を1画面
- bg: 代理ログイン(`DeputyLoginHandler`)、過去ログエクスポート(`BoardDataHandler`+`ExportService`)
- batch: `job/archivecleanup/ArchiveCleanupJobConfig.kt` 相当を1本

## 開発ポリシー（本プロジェクト専用。v6のCLAUDE.mdの運用ルールとは独立）

- **実装はSonnetサブエージェントへ委譲**: メインループは調査・設計・タスク分割・統合確認・
  進捗文書の更新に徹し、コード実装・単体テスト作成は `Agent` ツールで Sonnet モデルのサブエージェントに
  委譲する。数行程度の軽微な修正はメインループで直接行ってよい。
  - タスクの難易度に応じて `effort` を明示する（新規ロジック移植=high、ボイラープレート量産=medium/low）
  - 指示書には「これを満たしたら終了」という終了条件を3つ以内で明記する（ゴールドリフト防止）
- **セルフレビューは Gemini CLI を利用**: サブエージェントの実装完了後、`git diff`（ステージング）を
  `gemini -p` に渡してレビューさせ、指摘をコミット前に反映する（v6の運用と同じ手法。
  Gemini CLIのモデル指定は flash系を使わないこと — v6の運用実績上の注意点）
  - **【運用注意】モデルは `-m gemini-3.1-pro-preview` を使う**（2026-09-10 実測）: `gemini-2.5-pro` は
    新規ユーザーに提供終了しており `ModelNotFoundError`（404）で落ちる
  - **【運用注意】大きな差分は `-p` に埋め込まず、ファイルとして渡す**（2026-09-10 実測）: 差分が
    数万バイトを超えると Windows のコマンドライン長制限で
    `node: Argument list too long` になる。**リポジトリ内に一時ファイルとして書き出し、プロンプトでは
    相対パスを示して「まずこのファイルを読んでからレビューして」と指示する**（`C:/...` の絶対パスや
    リポジトリ外のパスは Gemini CLI のサンドボックスから読めない）。レビュー後は一時ファイルを消すこと
- **単体テストを必ず書く**: 新規ロジックには対応するテストを同時に作成する。純粋ロジックは
  common（commonTest等）に置きクロスプラットフォームで検証する。テスト観点（正常系・異常系・
  境界値）を実装報告に記載する
- **進捗管理は本ディレクトリ（v6-s/docs/）で行う**: `docs/tasks.md` を**索引**（現在地・タスク一覧の表・
  作業ログ）とし、**各タスクの詳細は `docs/tasks/TS-xxx.md` に1タスク1ファイルで置く**
  （2026-09-10 分割。索引が734行まで肥大化したため）。状態が変わったら**表とタスクファイルの両方**を更新する。
  作業を中断しても引き継げるようにする。**v6の `docs/tasks.md`
  やタスクスレ(qb5)とは完全に独立**（v6と文書が混在しないようにするための明示的な分離。
  qb5開発掲示板へのタスク連携も本プロジェクトでは行わない）
- **テストメソッド命名規約はv6を踏襲**: 日本語でテストの振る舞いを明確に記述する。テスト
  フレームワークは **munit**（JVM/JS両対応、TS-001で決定・§技術判断ログ参照）を採用したため、
  具体的な書式はバッククォート識別子ではなく munit の `test("...")` 文字列形式になる（例:
  `` test("空の入力は名無しさんを返す") { ... } ``）。ふるまい（入力→期待結果）が名前だけで
  読めることを重視するのはv6と同じ
- **FQCN直書き禁止（v6踏襲）**: `import` で解決し単純名で参照する。名前衝突時のみ import alias
- **Optional Braces（インデント構文）は原則使わない**（2026-09-08 ユーザー指示）: Scala 3 の
  brace省略（`if ... then` の後ろをインデントだけで書く、`class Foo:` / `def f() =` の後を
  インデントブロックにする等）は、**それを使うことで明らかに可読性が良くなる箇所に限定**する。
  **基本は波括弧 `{}` を使う**。
  - 理由: 省略と波括弧が混在すると読み手が構文境界を追いにくく、特に**深いネストや長いメソッドで
    どこまでがブロックか分からなくなる**。Kotlin（波括弧必須）からの移植コードとの見比べも難しくなる
  - **既存コードの扱い**: 新規・改修箇所から適用する。既存の省略記法を機械的に一括変換はしない
    （差分が膨らみレビュー不能になるため）。触るファイルで自然に直せる範囲で揃える
- **【運用注意】wrangler は 4.129.1 以降を使う**（2026-09-08 実測。TS-011u）: **wrangler 4.28.1 には
  DO内から見た自分のDO名 `ctx.id.name` がローカル実行で `undefined` になるバグ**があり
  （[workers-sdk#11580](https://github.com/cloudflare/workers-sdk/issues/11580) /
  [changelog 2026-03-15](https://developers.cloudflare.com/changelog/post/2026-03-15-durable-object-id-name/)）、
  Board DO の板ID（`bbs`）が空文字になってD1復元が静かに壊れた。**コード側の問題ではない**。
  `state.id.name` に依存する他のDO（Thread/User/ErrorCount）も同じ影響を受けるため、
  **古いwranglerを使い続けないこと**（TS-011uで 4.28.1 → 4.129.1 へ更新済み）
- **【運用注意】同一ビルドで sbt を同時に走らせない**（2026-09-07 実測）: サブエージェントを並行させると
  2つ目以降の sbt が
  `java.io.IOException: Could not create lock for \\.\pipe\sbt-load-..._lock, error 5` で起動失敗する。
  ビルドの不具合ではなく**sbtサーバの排他**。対処は「待って再実行」。`-Dsbt.server.autostart=false` で
  サーバを立てずに回避することもできるが、同じサブプロジェクトのtargetを同時にコンパイルすると
  Zincのファイルロックで詰まるため推奨しない。**タスクを並行委譲する場合は、触るサブプロジェクトを
  分けたうえで「sbt実行が衝突したら待って再試行」を指示書に明記すること**（Gradleのデーモンとは
  挙動が異なる点はTS-050の所見材料）
- **サブエージェントに `CLAUDE.md` / `docs/tasks.md` / `docs/tasks/**` / `docs/screens.md` を編集させない**（2026-09-07決定）: 並行実行時に
  文書が競合するため、実装は委譲しても**文書更新はメインループに一本化**する。サブエージェントには
  「決定事項・判明した事実は報告テキストに書く」よう指示し、メインループが技術判断ログ・タスク一覧へ反映する

## 単純化方針（2026-09-08 ユーザー指示・**移植時の原則より優先**）

**v6は複雑化している（特に Durable Object 層）。本来この掲示板はもっと単純なシステムだった。**
Scala移植を機に、**v5のTS+Hono移行版 `C:\project\3ch\3ch_workers` を「単純さの基準」として参照し、
意識的に単純化する**。v6の実装を無批判に写さないこと。

**規模の実測（2026-09-08）**:
| | v5版(3ch_workers) | v6 |
|---|---|---|
| 全体 | **2,294行**（TypeScript） | 数万行規模 |
| Durable Object 層 | **594行**（`thread.ts` 122 / `bbs.ts` 123 / `user.ts` 349） | **3,577行**（約6倍） |
| Thread DO 単体 | **122行** | **951行**（約8倍） |

**v5版から学ぶ具体的な設計**（実際に読んで確認した内容。パスは `3ch_workers/`）:
- **dat は「ただの文字列」**として DO の KVストレージへ入れる（`ctx.storage.put("dat", dat)` /
  `get<string>("dat")`）。**SQLiteの正規化テーブル（meta/posts/post_log）は既定にしない**
- **`wrangler.jsonc` の migrations は `new_classes`（KVバックエンドDO）**。`new_sqlite_classes` ではない
- **Range（206/416/Content-Range）は DO ではなくハンドラ層**で処理する（`src/services/service.ts` L74-106）。
  DOは「datを渡す」だけで、バイト範囲の切り出しを知らない
- **Shift_JIS は応答生成層の関心事**（ViewModelの `isSJIS` フラグ）。**DOはSJISを知らない**
- **コールドスタート復元は `isLoaded` フラグ1つの遅延load**（`thread.ts` L28-39）。
  スキーマ移行機構やhydrate専用の設計は無い
- **書き込み間引きは1行の時刻比較**（`thread.ts` L67-78。`lastModified + 1000ms < now` なら書く）。
  `ctx.waitUntil()` のマイクロバッチ機構を作り込まない

**原則の再定義（既存の「移植時の原則」を上書きする部分）**:
- **外から見える挙動は維持する**（ここは従来どおり厳守）: 2chプロトコル互換、URL、dat/subject.txt の形式、
  Shift_JIS配信、Rangeリクエスト、忍法帖認証・規制の**機能要件**
- **内部設計は単純化してよい（むしろ推奨）**: ストレージ方式・キャッシュ戦略・レイヤ構成・クラス分割。
  「v6と同じ実装であること」自体はもはや目的ではない
- ただし **機能を削るのではなく、実装を単純にする**。忍法帖認証・規制・過去ログのR2階層化は
  v5に無いv6固有の機能であり、**移植対象から外してよいという意味ではない**

**単純化の限界（安易にv5へ寄せると壊れる箇所。実装前に必ず検討すること）**:
- **DOのKVストレージは1値あたり上限がある（128KiBとされる。TS-012で要実測）**。
  v6のdatは**最大500KB**想定なので、**「dat丸ごと1キー」はスケールしない**。
  SQLiteを使うか、チャンク分割するかを実測で決める。**v5が単純なのは規模の前提が違う可能性がある**
- **毎リクエストでSJISエンコードしてから切り出すとCPUを食う**。v6が「write時に1回だけエンコードして
  `Uint8Array` をキャッシュし `subarray()` でO(1)スライス」にしたのはWorkersのCPU時間制約が理由
  （v6 CLAUDE.md DS-06）。**単純さと引き換えに何を失うかを測ってから決めること**

**判断の手順（複雑だと感じたとき）**:
1. **まず `3ch_workers` の対応箇所を読む**（v5がどう解いていたか）
2. 単純版で要件（外から見える挙動・上記の限界）を満たせるかを検討する
3. 満たせるなら**単純版を採用する**
4. **v6が複雑にした理由が分かった場合は `docs/design-notes.md` へ記録**する
   （v6側の判断が正しかったのか、過剰だったのかの評価はTS-050の主要論点）

**適用範囲**: TS-011の残り（Board系）とTS-012以降。
**既に移植したThread系も、テストを壊さない範囲で見直す**（`ThreadDurableObject` 323行 /
`ThreadCore` 381行は、v5の `thread.ts` 122行と比べてまだ重い）。

## DO層の状態設計（2026-09-08 ユーザー確定・実測に基づく）

**Thread DO / Board DO とも「①真実の表現（構造化データ）＋②配信用のSJISバイト列キャッシュ」の
2つをインメモリに持つ**。v6の3重管理（SQLite行 + Uint8Arrayビュー + `lineOffsets`）から
`lineOffsets` を落とし、v5の毎リクエストSJIS化（性能破綻。下記実測）も避ける折衷。

### 判断の根拠（実測 2026-09-08。`encoding-japanese` / Node 22）
| dat | SJIS後 | 全体エンコード | 1行のみ |
|---|---|---|---|
| 100KB(489レス) | 72KB | 3.67ms | 0.025ms |
| 300KB(1,460レス) | 217KB | 9.96ms | 0.016ms |
| 500KB(2,426レス) | 361KB | **16.5ms** | 0.015ms |

**DOはオブジェクト単位で単一スレッド**のため、v5式（毎リクエストで全体エンコード）だと
500KBのスレで**理論上限60req/s・CPU100%**。実況板（同一スレに最大3レス/秒＋多数の専ブラが
ポーリング）では成立しない。**書き込み時に追加1行だけエンコードすれば0.015msで無視できる**。

### Thread DO
- **① レスの配列**（インメモリ・真実の表現）
- **② dat の SJIS バイト列**（インメモリ・専ブラ配信専用キャッシュ）
- **永続化: DOのSQLiteへ「レス単位」で1行ずつ**（①と1:1対応）。**dat文字列は保存も常時保持もしない**
- **`lineOffsets`（レス番号↔バイト位置の対応表）は持たない**。専ブラのRangeは
  **クライアントが指定するバイト位置**なので②を直接sliceすればよく、read.cgi は HTML(UTF-8) 出力なので
  **①だけで足りバイト列に触れない**
- **②は追記のみ**（datは追記専用のため）。あぼーん等で①を書き換えた場合のみ②を作り直す

### Board DO
- **正（source of truth）は D1 の `threads` テーブル**（**全板共有・`bbs` 列あり**）。
  **Board DO のSQLiteではない**点が Thread DO との違い
- **① 板内スレ配列**（インメモリ）
- **② subject.txt の SJIS バイト列**（インメモリ・配信用キャッシュ）
- **① が保持するのは TOP N 件のみ**（2026-09-08 ユーザー指示）。N = **`BBS_MAX_MENU_THREAD`**
  （subject.txt 掲載スレ数上限。**本家2ch準拠のSETTING.TXTキー**。v6既定 **128**）
  - ⚠️ **`BBS_SUBJECT_COUNT` と混同しないこと**。そちらは**スレタイ最大文字数**（既定64）で別物
    （v6の T-68 で混同が指摘されている）。SETTING.TXTのキー名は**専ブラが読む互換面**なので独自名を作らない
  - **圏外のスレはD1に残る**（真実はD1）。v6は「dat落ち概念の廃止」（20年前のスレに書き込める）方針なので、
    圏外スレへの書き込みで bump されたら**①へ戻ってくる**
- コールドスタート時は **D1 から `bbs` で絞り、bump順の TOP N** を取得して①を復元し、②を組み立てる
- **②は「追記」ではなく「作り直し」になる**: subject.txt は**スレフロート（bump）で並び順が変わる**ため、
  Thread の dat のように末尾追記できない。
  **ただし TOP N 制約により、①も②も N で頭打ちになる**ので実害は小さい:
  N=128・1行約70バイトなら subject.txt は**約9KB → 再エンコードは0.3ms程度**（上表の100KB=3.67msから外挿）。
  3bump/秒でも約1ms/秒で、素朴に毎回作り直しても許容範囲
  - それでも **ダーティフラグによる遅延再構築**にしておくとよい（bump時は①の並び替えとフラグ立てのみ、
    ②は次に subject.txt が要求された時に作り直す）。書き込みバーストが1回の再構築にまとまる。
    **Nを大きく設定した板でも破綻しない**保険になる

### 共通の不変条件（バグの温床なので1箇所に閉じる）
- 「**② は ① のSJIS直列化である**」を保つ。①を変更する経路と②を更新する経路を
  **1つのメソッドに閉じ込める**こと（Threadは「①へpush＋②へ1行追記」、Boardは「①を並び替え＋②をdirty化」）

## 移植時の原則（TS-003で確立）

> **【2026-09-08 改訂】上の「単純化方針」が本節に優先する。**「v6と同じ実装であること」は
> もはや目的ではなく、**外から見える挙動の一致**のみが厳守事項。以下の記述はその前提で読むこと。

- **外から見える挙動の一致を最優先する**（内部設計の一致ではない）: 2chプロトコル互換・URL・
  dat/subject.txtの形式・Shift_JIS・Rangeなど、**クライアント（専ブラ含む）から観測できる挙動**は
  v6と一致させる。一方で**内部の実装が複雑だと感じたら単純化してよい**（上の「単純化方針」参照）
  - 例（TS-003のGemini CLIレビュー指摘。いずれも**v6と同じ実装であるため直さない**）: dat文字列の
    複数回走査、`ResRange.parseMultiple` が分割後に件数上限を見ること、`IdCounter` の正規表現2周、
    `TextSanitizer` の二重走査。性能特性の比較材料としてTS-050で扱う
- **言語差で挙動がズレる箇所は明示的に合わせる**（TS-003で実際に踏んだもの）:
  - Kotlinの `String.split` は末尾の空要素を残すが、Java/Scalaの `split` は既定で捨てる
    → **`split(sep, -1)` と書いて明示的に揃える**
  - Kotlinの `trimEnd('/')` は末尾の該当文字を全て除去する（1文字ではない）
  - `IntRange.EMPTY` 相当は Scala では `1 to 0`
  - `toIntOrNull`/`toIntOption` はどちらも全角数字や符号を受理しうるので、ASCII数字判定を自前で行う
- **正規表現は JVM と Scala.js で挙動が異なりうる**（`java.util.regex` vs JavaScript RegExp）。
  common に置く正規表現は複雑な量指定子・後方参照を避け、**JVM/JS両方のテストで検証する**
  （cross projectなので `sbt test` が自動的に両方走る）
- **移植のスコープ外にしたコードは、その旨をコード内のコメントに1行残す**（例: `TextSanitizer` の
  書き込みバリデーション専用関数群は read.cgi に不要なため未移植）。後から「移植漏れ」と誤認しないため

## アーキテクチャの分離規約（v6と同一）
`View` → `Router` → `Handler` → `Application Service` → `Domain` → `Infrastructure` の層を厳守。
Controller/Handler は入力値バリデーションとレスポンス変換のみ、DBアクセス直書き禁止。

## 技術判断ログ（実装中に決めた・要検討の項目を随時追記する）

- **[判断済] bg のテンプレートは Scalatags**（2026-09-07 ユーザー指示。当初のThymeleaf指定を差し替え）:
  bg(JVM) の kotlinx.html 製Viewは Thymeleaf ではなく **Scalatags** へ移植する。結果として
  **bbs(Scala.js)のSSR も bg(JVM)のView も同じ Scalatags** となり、テンプレート技術がリポジトリ内で1つに
  統一される。利点と留意点:
  - Thymeleafのような「HTMLファイル＋属性」ではなく**Scalaコードで型付きにHTMLを組む**方式なので、
    kotlinx.html（同じくコードでHTMLを組むDSL）からの移植は素直（v6の `view/BgPageLayout.kt` 等の
    構造をほぼそのまま写せる）。Thymeleafを選んだ場合に必要だった「DSL→テンプレートファイルへの
    パラダイム変換」が不要になる
  - Scalatags は cross-build されているため、**bbsとbgで共通のViewヘルパを `common` に置ける**
    （v6では bbs=React FC / bg=kotlinx.html と分かれていた層が統一できる）。ただし
    共有は「本当に同じHTMLを出す部分」に限り、無理に共通化しないこと
  - v6の「View関数の引数が5個を超えるならViewModelクラスを作る（ロジックは持たせない）」という
    方針はそのまま踏襲する
  - `spring-boot-starter-thymeleaf` 依存は不要（TS-001で入れていたものは削除済み）

未確定の技術判断はここに追記し、`docs/tasks.md` の該当タスクから参照する。判断済みのものも
「なぜそうしたか」を残す（v6の「要判断」運用に倣うが、本ファイルは技術判断専用）。

- **[撤回] ~~admin SPAのルーティングライブラリは Waypoint~~**（2026-09-07判断 → **2026-09-08に撤回**。
  フロントがReact系になったため、**React Router v7 / TanStack Router** を使う。以下は当時の調査記録）:
  TanStack Router相当。
  `frontroute` は最終リリース 0.19.1（2024-12、依存名変更のみ）以降更新が無く事実上停滞。対する
  `Waypoint`（raquo/Waypoint）はLaminar本体と同じ作者が並行メンテしており、v10.0.0-M1 が
  Laminar 18.0.0-M2 に追随・Scala 3 の enum パターンマッチにも対応している。TS-020 で
  Laminar 17.2.1 との組み合わせで動くバージョンを確定させること
- **[判断済・方針転換] フロントは Laminar をやめ、Slinky × React系にする**（2026-09-08 ユーザー指示）:
  下記「Laminarにhydrationが無い」問題に対し、**段階的に降りるのではなく最初からReact系を採る**ことにした。
  すなわち `tasks.md` TS-006 の段階3（React回帰）を初手として採用する。
  - **狙い**: React の `renderToString` + `hydrateRoot` が使えるため、**v6と同じ
    「SSRとCSRで同一コンポーネントを共有する」設計をそのまま移植できる**（Laminarでは
    アイランド方式への設計変更が必要だった）。かつ **v6のkotlin-wrappers構成と言語だけが異なる
    対照群**になるため、「Kotlin vs Scala」の比較としてはむしろ純度が上がる
  - **UIライブラリ = `scalajs-react`（japgolly）**（2026-09-08 ユーザー確定。当初のSlinky指定から変更）
  - **ルーター = React Router v7**（ScalablyTyped facade経由。**Waypoint・TanStack Router の案は撤回**）
  - **Next.js は入れない**（下記調査で確定）
  - Scalatags は **bg(JVM)のView用として維持**（kotlinx.htmlの移植先。こちらは変更なし）
- **[判断済] Slinky ではなく scalajs-react を採る**（2026-09-08 調査・ユーザー確定）:
  ユーザー指示は当初 Slinky だったが、一次情報調査で**前提が崩れた**ため変更した。
  - **Slinkyを外した理由**: (1) **看板機能の `@react` マクロが Scala 3 で使えない**
    （v0.7.0のCHANGELOGに "no `@react`/`Props`-`apply` sugar"、`-source:3.0-migration` 必須と明記）。
    手書きの `ComponentWrapper` 継承へ戻るため採用動機の大半が消える。(2) **React 18止まり**で
    最終リリースは v0.7.5（2025-03）。**v6が使う React 19.2.5 と版が揃わない**。(3) 活動が低調
    （ScalaJSBundler非依存で動く点だけは確認できた）
  - **scalajs-react を採る理由**: **Scala 3 正式対応**（v3.0.0 / 2025-12）で最新は
    **v4.0.0（2026-05-19）が React 19 対応**。直近1年で継続リリースがあり活発。
    マクロに依存しないビルダーAPIのため **Scala 3 でも書き味が変わらない**。
    `ReactDOMServer.renderToString` のfacadeとSSR実例あり
  - **ルーターを React Router v7 にした理由**: TanStack Router は**ルート木からの型推論が
    TypeScriptの高度な型機能に強く依存**しており、ScalablyTyped変換に耐えるか不明（v6と揃える
    利点より、変換破綻のリスクを重く見た）。React Router は（v5世代だが）ScalablyTyped経由の
    実例が存在する。※scalajs-react は独自Routerも内蔵しているので、React Router v7 の
    facade化が破綻した場合の退避先になる
- **[判断済] adminのURL設計は自由に変えてよい。ルーターは scalajs-react内蔵Router を第一候補とする**
  （2026-09-08 ユーザー指示）:
  - **URL互換が絶対なのは利用者向けだけ**: `read.cgi` / `bbs.cgi` / `dat` / `subject.txt` /
    `subject.html` / `SETTING.TXT` 等は**専ブラが叩くため1文字も変えられない**。対して
    **adminは認証の内側でSEO・クローラ・外部リンクと無関係**なので、URL設計は完全に自由。
    この非対称性を前提に設計すること
  - **`?page=2&status=banned` 形式はレガシー（Perl CGI時代の名残）なので踏襲しない**（ユーザー判断）。
    **正規化できる主要な絞り込みはパスセグメントにする**（例 `/admin/users/banned/p/2`）
  - **これによりルーター選定の軸が変わった**: React Router v7 の唯一の決定的な利点だった
    `useSearchParams`（クエリ状態の扱い）が不要になる。しかも**クエリ文字列はScalaから見ると型が付かない**
    （facade越しでは実質 `js.UndefOr[String]` でパースと検証を毎回手書き）のに対し、
    **内蔵Routerは境界で1回パースして `sealed trait Page` のADTへ落とす**ため、
    画面に届く時点で型が付き網羅性もコンパイラが検査する。**パス中心設計はScalaでは型安全側に倒れる**
  - **ただしクエリを全廃はしない**: 自由入力の検索語（`q=名無し` 等）はエスケープ・スラッシュの
    問題があるのでクエリに残す。**クエリを1〜2個に限定すれば内蔵Routerで手書きしても定型コードは積み上がらない**
  - TS-020a（React Router v7 の ScalablyTyped 変換スパイク）は**選定のためではなく
    「主要なReactルーターが変換できるか」のデータ点収集**として維持する（半日で見切り、本線には影響させない）
- **[経緯] adminのルーター比較の判断材料**（2026-09-08。上記により内蔵Router優位で決着）:
  - **前提の変化**: adminを **T-81の将来像＝フルSPA**として作ることが決まり（ユーザー指示）、
    ルート木は IA1/IA2/IA3 全画面（10本以上、`{bbs}`/`{key}` の動的セグメント・ロール別認可つき）になった
  - **内蔵Routerの利点**: 純Scalaで **npm不要＝ScalablyTyped変換リスクがゼロ**（honoで型劣化・
    workers-typesで変換失敗を実際に経験している）。ルートを `sealed trait Page` のADTで定義でき、
    **画面の網羅性をコンパイラが検査する**。本体とバージョン整合が保証される
  - **React Router v7の利点**: `useSearchParams` による**クエリ状態の扱い**。admin は一覧+絞り込み+
    ページングの画面が多く、`?page=2&status=banned` のような検索状態がURLに乗るため、ここが最大の判断軸。
    ネストレイアウト（`<Outlet>`）も板→スレッド→レスの階層に素直
  - **React Router v7の弱点**: 売りである framework mode（ファイルベースのルート定義・loader/action・
    typegen）は**TS/JSで書く前提の規約**でScala.jsからは受け取れない。残るのは
    `BrowserRouter`/`Routes`/`Route` 相当＝内蔵Routerとほぼ同じ範囲。かつ規模が大きいほど
    facade の型劣化リスクが広範囲に波及する
  - **遅延ロードは判断軸にならない**: Scala.js には `ModuleSplitStyle` + 動的importという
    言語側の分割機構があり、ルーターのlazy route機構に依存しなくてよい
  - **決め方**: TS-020a で React Router v7 を `stImport` にかけ、変換可否と型の質を実測して決める。
    ルーティング層は薄く差し替えが局所的（ページコンポーネントは影響を受けない）ため、
    **後から乗り換えても袋小路にならない**
- **[実証済] Scala.js から Cloudflare Durable Object を直接 `extends` できる**（2026-09-08 TS-005 実測。
  **公開事例が無かった領域で、本PJ最大の未知数が解消**）:
  ```scala
  @js.native @JSImport("cloudflare:workers", "DurableObject")
  class DurableObject(ctx: js.Any, env: js.Any) extends js.Object

  @JSExportTopLevel("CounterDurableObject")
  class CounterDurableObject(ctx: js.Any, env: js.Any) extends DurableObject(ctx, env)
  ```
  - `worker.mjs` から `export { CounterDurableObject }` として**再export**し、`wrangler.jsonc` に
    `durable_objects.bindings` + `migrations`(`new_sqlite_classes`) を書けば動く。
    `wrangler dev` でRPC往復とカウンタの増加（1→2→3）を実測確認
  - **JS側の薄いラッパクラスへ倒す代替案は不要だった**
  - **地雷（TS-011で必ず踏む）**: `@JSExportTopLevel` は**Testスコープのリンクでもリンカのルートになる**ため、
    DOクラスをexportした瞬間に `sbt bbs/test` が壊れる（`cloudflare:workers` は Workers ランタイム組込みで
    npmに存在せず、素のNodeのESMローダーが `ERR_UNSUPPORTED_ESM_URL_SCHEME` で即死する。
    テストが `increment()` を呼んでいなくても発生）。対処として `bbs/test-stubs/` に
    `cloudflare:workers` だけを差し替えるNodeのESMローダーを置き、`Test / jsEnv` の
    `--experimental-loader` で読ませている（`frontendBbs` の `NODE_PATH` シムと同じ発想）
  - **バンドルサイズへの影響は軽微**: DO facade + クラス + ルート追加で
    `fastLinkJS` +1,802B（+1.9%） / `fullLinkJS` +1,813B（+2.5%）
- **[要注意・後続タスクへの制約] jsdomテスト環境では `@JSImport` が使えない**（2026-09-08 実測）:
  TS-006bで `frontendBbs` のTestスコープに jsdom 環境（`scalajs-env-jsdom-nodejs` 1.1.0）を整備したが、
  このJSEnvは **ESModule / CommonJSModule のどちらの入力にも対応しておらず**（`UnsupportedInputException`）、
  `Test / scalaJSLinkerConfig` を **`NoModule` へ落とす**必要がある。その結果、
  **`@JSImport` を含むコードはリンクエラーになる**（実測: `module support is disabled`）。
  → **npmライブラリをimportするコード（＝scalajs-reactのコンポーネント）は、この環境でテストできない**。
  TS-015 / TS-020 では別手段（ブラウザ系JSEnv等）を検討すること。DOM非依存のロジックは
  素の `NodeJSEnv`（`frontendBg` 等）でテストする。**本線(Test以外)はESModuleのまま**であり、
  テストだけ出力形式が異なる＝**本番固有のロード問題をテストで検出できない**点にも注意
- **[判断済] `npm ci` が必要な場所が2つある**（Gemini CLIレビュー指摘・2026-09-08）: `bbs/`（ScalablyTyped用）と
  `frontend/bbs/`（jsdom用）。**sbtからは自動実行されない**ため、クリーンなクローンやCIでは
  手動実行が必要（下記「ビルド・開発コマンド」参照）。将来CIを組むならsbtタスクへのフック化を検討する
- **[実証済] Workers上での SSR + ブラウザでの hydration が成立する**（2026-09-08 TS-006 実測。
  **Phase 0 の最後の未知数が解消**）:
  - **採用: `com.github.japgolly.scalajs-react` 4.0.0**（Scala 3 / React 19 対応）
  - **`react-dom/server` の解決が肝だった**: scalajs-react 内蔵の `facade.ReactDOMServer` は
    `@JSImport("react-dom/server", ...)` を**ハードコードしており差し替えられない**。これは
    `server.node.js`（Node専用・Buffer依存）に解決されWorkersで壊れる。
    → **`react-dom/server.edge` を直接importする自前facadeを書いて回避**
    （`bbs/src/main/scala/ch3s/bbs/external/react/ReactDomServerEdge.scala`）
  - **`wrangler.jsonc` に `compatibility_flags: ["nodejs_compat"]` が必要**
    （`server.edge.js` が `process.env.NODE_ENV` を読むため）
  - **共有コンポーネントの置き場所は `common/.js/src/main/scala/ch3s/common/fc/`**（選択肢(a)を採用）。
    v6の `common/src/jsMain/kotlin/fc/` に直接対応する。`bbs` と `frontendBbs` は既に `commonJS` に
    依存しているので、scalajs-react依存を `common` の `.jsSettings` に置けば両方へ透過的に流れる。
    **新規サブプロジェクトは不要だった**
  - **hydrationはブラウザで実測確認済み**: SSR済みの `#counter-button` に React fiber が付いており、
    クリック後も**同一ノードのまま**テキストが `clicked 0 times` → `1` → `2` と更新。
    `#greeting` は1個のまま（**二重描画なし**）、コンソールに警告・エラーは**ゼロ**
    （hydration mismatch も invalid hook call も出ていない）
  - **代償: バンドルが激増する**。`bbs`(Workers) の `fullLinkJS` が **71,405B → 478,202B（+407KB）**。
    クライアント側 `frontendBbs` も `fullLinkJS` 469,953B。**scalajs-react core を1コンポーネント
    使うだけでこの増分**（Laminarの残存が原因ではないことを実測で確認済み — 成果物に
    `raquo`/`Laminar`/`Airstream` は0件）。TS-050の主要論点
  - **`react` / `react-dom` は `stIgnore` に入れる**必要がある（両者は `.d.ts` を同梱しておらず、
    ScalablyTypedの `stImport` がスキップではなく**ハード失敗**するため）
- **[経緯] Workers上で `react-dom/server` をどう解決するか**（TS-006で解決済み。以下は着手前の記録）:
  `react-dom/server.node.js` は Buffer等のNode専用APIに依存し **Workers/Miniflareでは動かない**。
  **`react-dom/server.edge`（`renderToReadableStream`）を明示的に指す必要がある**。
  facadeがデフォルトエントリ（Node版）を解決してしまうと実行時エラーになる。
  Scala.js からこの経路を通した実例は見つかっていないため、TS-006で実地検証すること
- **[参考・経緯] Laminar には hydration が無い**（2026-09-07 事前調査、確度=高。**上記の方針転換により
  Laminarは不採用となったが、判断の経緯として残す**）:
  Laminar に公式のSSR/hydration機能は存在しない（raquo/Laminar issue #60 が2020年からオープン、
  公式の laminar-full-stack-demo も README で「SSRからのHTML返却とクライアント読み込みは
  デモ対象外」と明記）。v6のレンダリング分類2（read.cgi・板TOP等の「SSR + hydration、JS無効でも
  コンテンツ表示」）と正面から衝突する **本移植で最も重要な観察ポイント**。
  **採用方針（2026-09-07 ユーザー指示）: 「静的パーツ（Scalatags）」と「動的パーツ（Laminar）」を
  アーキテクチャとして切り分ける**（いわゆるアイランド方式。参考:
  https://zenn.dev/110416/articles/997e1932fa0010 ）。
  - ページのベースHTMLと初期表示コンテンツ（read.cgi のレス本文一覧など**SEO・JS無効時に必要な部分**）は
    サーバー側で **Scalatags** により生成して配信する。ここは Laminar を通さない
  - HTML内にマウントポイント（`<div id="...">` 等）を用意し、**Laminarはその場所だけを乗っ取ってCSRで動く**
    （新着リロード、フォームの動的挙動、モーダル等の対話部分）
  - つまり React の `renderToString` + `hydrateRoot`（同一コンポーネントをサーバーとクライアントで
    共有し、DOMを引き継ぐ）とは**別物**。**同じレスをサーバーとクライアントで二重に描かない**よう、
    責務をコンポーネント単位で分ける設計にすること（＝v6のFC共有＝`common/jsMain/fc/` の構造は
    そのままは移植できない。ここが本移植で最も設計が変わる箇所）
  - Scalatags は cross-build されており **Cloudflare Workers 上（Scala.js）でも文字列生成に使える**
    見込み（v6のサーバー側SSRもWorkers上で行っている）。TS-006 でこれを実測確認する
  - TS-006 のスパイクでは「この切り分けで read.cgi が成立するか」「Laminarが乗っ取る境界をどこに置くか」
    を確定させ、限界（Reactの `hydrateRoot` から失うもの）をここへ追記する。TS-050 の所見レポートの中核

  **代替段取り（2026-09-07 ユーザー指示。上から順に試し、駄目なら次へ落とす）**:
  段階を下げた事実そのものが調査成果なので、**各段階で「なぜ駄目だったか」を必ず本ログへ記録**すること。
  1. **【既定】Scalatags(SSR) + Laminar アイランド**（上記）。TS-006 で検証
  2. **Laminar を維持したまま別手段**:
     - (a) **動的部分を極小化して vanilla Scala.js（scalajs-dom）で書く** — read.cgi の対話要素は
       新着リロードとアンカーポップアップ程度なので、そもそもリアクティブUIライブラリが要らない
       可能性がある。`frontend/bg` が v6 時点で vanilla 方針（React不使用）なのと同じ発想
     - (b) 最小限の自作hydration（サーバー生成DOMを捨てずにイベントだけ結線する薄い層）
  3. **UIライブラリを React 系へ戻す**: Scala.js から React を使う
     （`scalajs-react` または `Slinky`、あるいは ScalablyTyped 生成facade経由）。
     `hydrateRoot` が使えるため **v6 と同一の設計（SSRとCSRでコンポーネント共有）を再現できる**。
     SPAルーティング（admin・`/auth`）は **TanStack Router / React Router v7** を
     ScalablyTyped facade で使う（＝v6の kotlin-wrappers 構成に最も近い形。言語だけをKotlin→Scalaに
     替えた比較になるので、**調査としてはむしろ有意義な対照群**）
  4. **最後の手段**: 当該画面のみ TypeScript/JS で書き、Scala は他層に集中する
     （「Scalaで書けない部分がある」という結論も成果）

  **判断のタイムボックス**: 各段階は**半日相当の試行で見切る**。段階1が駄目なら 2(a) を短く試し、
  そこで詰まったら段階3へ移る判断をユーザーへ報告してから進める（段階3はフロント全体の
  方針転換になるため、独断で降りないこと）
- **[判断済] SSRの分担: ページ本文 = scalajs-react ／ ドキュメントの殻 = Scalatags**（2026-09-11 TS-013c 実測で確定）:
  2026-09-07 の「bbsのSSRもbgのViewもScalatagsに統一する」という判断は、その後 TS-006 で
  フロントが scalajs-react に変わったため**境界を引き直した**。最終形は次のとおり:
  - **`<html>`/`<head>`/`#root` の殻と、JSを使わないエラー画面（`ReadCgiErrorView`）は Scalatags**
    （`bbs` の Compile 依存に追加した）。エスケープが既定で効き、`raw(...)` でSSR断片を差し込める
  - **`#root` の中身（hydrationするページ本文）は scalajs-react**。SSRとCSRで同一コンポーネントを共有する
  - **バンドルサイズ面の裏付け（実測・`docs/performance-notes.md` PN-10）**: Scalatags の実コストは
    **43,517B（read.cgi 完成時点の増分の約9%）**にすぎず、残る**約445KBは scalajs-react の
    vdom/属性サーフェス**だった。**Scalatagsを避けても意味が無い**ことが数字で確認できたので、
    テンプレート技術の統一（bg/JVM と同じもの）を優先してよい
  - **⚠️ Scalatags の罠**: `<title>` タグは `scalatags.Text.all` に**無い**（同名のグローバル属性
    `title` と衝突するため）。`scalatags.Text.tags2.title` を個別importする。`style`/`main` も同様
- **[判断済] SSR/hydration 共有データのJSON化は `js.JSON` を手書きする。JSONライブラリを足さない**
  （2026-09-11 TS-013c）: v6 は kotlinx.serialization の `@Serializable` 1行だったが、
  Scala 3 の対応物（upickle / circe）は入れなかった。理由は
  (1) このJSONが通る境界は **Workers(Scala.js) → ブラウザ(Scala.js)** の片道で **JVM側が関与しない**、
  (2) すでにバンドルが1.37MBあり SSRホットパスにライブラリを積みたくない、の2点。
  `ch3s.common.model.page.ReadPageData` に `toJson`/`fromJson` を手書きした。
  - **⚠️ Scala.js の `Long` は opaque なので `js.JSON.stringify` にそのまま渡すと壊れる**。
    `serverTime` は `toDouble` してから載せている（TS-012 でも同じ罠を踏んだ）
  - **JVMと共有するJSONや、フィールド数の多いDTOが要る時点（TS-030 bg / admin API）で upickle を再検討する**。
    「`@Serializable` 1行 vs 手書き変換関数」は TS-050 の比較材料
- **[判断済] 移植元（v6）の欠陥を見つけたら、v6-sで直したうえでv6側にタスクとして起票する**
  （2026-09-11 ユーザー指示。`docs/design-notes.md` DN-13。**当初「直さずテストで固定する」と判断したが覆した**）:
  `MessageFormatter` に v6 由来の実バグ（URL正規表現の文字集合がエスケープ済み実体参照
  `&lt;` `&gt;` `&quot;` `&apos;` を飲み込み、後段のアンカー置換が生成済みタグの属性値の内側まで
  書き換えて `href` に `<a>` が入れ子になる）を発見した際の扱い。
  - **「外から見える挙動の一致を最優先する」は仕様の一致を指すのであって、バグの再現までは要求しない**。
    移植元の欠陥は **(a) v6-s で直す** ＋ **(b) v6側へ起票して収束させる** の両方を行う
  - 据え置いてよいのは「v6側の判断を待つ必要がある**設計上の論点**」に限る
    （性能特性の違いや実装の複雑さのように、どちらが正しいか自明でないもの）
  - **修正した箇所はコード内コメントとテストに「v6と挙動が異なる」ことを明記する**。
    v6と読み比べる人が差分に驚かないようにするため
- **[未定] common の validation層（zodkmp相当）**: zodkmpはKotlin実装のzod風DSL（JS zodをラップした
  ものではない）のため直接のScala移植先が無い。手書きの軽量バリデーションコンビネータをcommonに
  実装するか、Scala用の同種ライブラリを探すかを決定する。
  **TS-003（read.cgi側ドメイン）では validation層に触れずに済んだ**ため未決のまま。
  bbs.cgi 側ドメイン（投稿フォームの検証）を移植する TS-003b で決めること
- **[判断済] TS-003: Kotlin ADT → Scala 3 `enum`**（2026-09-07）: `sealed interface` + `data object` /
  `data class` の階層は Scala 3 の `enum` で素直に表現できた。ただし `ResRange` の内側の case `Range` は
  標準の `scala.Range`（`resolve` の戻り値型）と名前が衝突するため **`Span` へ改名**した（挙動は同一）。
  この種の「標準ライブラリとの名前衝突」はKotlinでは起きなかった摩擦としてTS-050へ記録する
- **[判断済] TS-003: Kotlinの非ローカルreturnは `Option` の合成へ書き換える**: Kotlinの
  `?: return All` のようにラムダ内から関数ごと抜ける書き方はScalaに無いため、`Option` を返す
  privateメソッドに切り出し `for` 内包表記のガードで合成する形にした（`ResRange.parseSpan` 等）。
  制御フローの形は変わるが結果は同一（テストで確認済み）
- **[判断済] bbs backendはScala.js + Hono facade継続**: ユーザー指示は「ReactとTanStackを置き換える」
  であり、Hono自体の置き換えは指示されていない。Cloudflare Workers上でHonoを動かす構成を維持し、
  ScalablyTypedでHonoのfacadeを生成する（v6の`external/hono`手書き宣言を置き換える）
- **[判断済] bg/batchはSpring WebFlux/Spring Batchを継続**: 同様にユーザー指示の範囲外。Java
  ライブラリとしてScala 3から利用する（`coRouter`/`suspend`のKotlin糖衣は使えないため
  `RouterFunctions`のJava DSL、またはcats-effectとの相互変換ヘルパーで書く）
  - **[要検証]** Scala 3 × Spring WebFlux/Batch は一次情報がほぼ存在しない（事前調査で
    実例・公式ガイドとも発見できず。Scala版Spring Bootサンプルは在るがWebFlux/Scala3対応は未確認）。
    アノテーション（`@RestController` 等）はバイトコードレベルの機能なのでScala 3からも動くはずだが
    **未検証**。Reactor `Mono`/`Flux` はKotlinの `reactor-kotlin-extensions` に相当するScala拡張が
    見当たらず素のJava APIを叩くことになる。**机上調査はここで打ち切り、TS-030の冒頭で
    最小WebFluxアプリの起動確認（ハンズオン）から始めること**
  - **[要検討] Spring Bootのバージョン**: TS-001では 3.3.5 を採用したが、v6は 4.0.6 を使っている。
    比較の公平性のためTS-030着手時に4.0.x系へ揃えるか検討する（現状はコンパイル確認のみで
    起動確認をしていないため、上げるなら起動確認と同時が効率的）
- **[要検証] Scala.js の Durable Object を Cloudflare Workers で動かせるか**（2026-09-07 事前調査、
  確度=中）: Scala.js-defined クラスが native JSクラスを `extends` することは
  [公式にサポート](https://www.scala-js.org/doc/interoperability/sjs-defined-js-classes.html)されて
  おり、`ModuleKind.ESModule` 出力と `@JSExportTopLevel` でのexportも確立技術。ただし
  **`export class Foo extends DurableObject` をScala.jsで書いてwranglerでデプロイした実例が
  見つからない**（Pages Functions の named export 実例は在る）。理論と実装のギャップが出やすい
  領域なので、TS-005（スパイク）でDO層の本移植（TS-011）より前に単独で潰す。
  なお **Workersのサイズ制限は2026-09-04に非圧縮64MiBへ大幅緩和された**ため、Scala.js出力の
  バンドルサイズ懸念はほぼ解消している（CPU時間上限も最大5分まで引き上げ済み）
- **[要検証] ScalablyTyped の hono 変換**（2026-09-07 事前調査、確度=中）: `sbt-converter` の最新は
  `1.0.0-beta45`（約7ヶ月前）。**活発ではないが生きている**。プラグイン自体はsbt 1.x要件により
  Scala 2.12限定だが、生成コードはScala 3.2.0+/Scala.js 1.11+ で使える。
  **Scala.js 1.18 で `stImport` が壊れる報告あり**（2025-05、`typings from std can't be found`。
  回避策は `~/.ivy2/local/org.scalablytyped` の削除）— TS-001で採用した **sbt-scalajs 1.17.0 は
  この問題の手前**なので、ScalablyTyped が安定するまで安易にScala.jsを上げないこと。
  honoは条件型・mapped types を多用するため変換が近似・失敗する可能性がある（hono固有の失敗報告は
  未確認）。**判断基準: 変換がタイムアウト/OOM/使い物にならない出力を返したら、実際に使う
  関数シグネチャだけを手書き `@js.native` facade に落とす**（v6の `external/` と同じ形に戻す）。
  その場合も「ScalablyTypedで賄えなかった範囲」をTS-050の所見に記録する
- **[判断済] TS-002: ScalablyTyped は ExternalNpm 方式で使う（結論）**（2026-09-07 実測）:
  - **標準の `ScalablyTypedConverterPlugin` は使えない**。内部で `ScalaJSBundlerPlugin` に依存し
    「ScalaJSBundler有効時は `scalaJSModuleKind` が `CommonJSModule` 必須」という制約があるため、
    Workers用の **ESModule 出力と衝突する**（sbtロード時に RuntimeException で確定）。
    代わりに **`ScalablyTypedConverterExternalNpmPlugin` + `externalNpm`** を使い、npm依存の実体は
    `bbs/package.json` と `bbs/node_modules`（`npm install` で用意）に持たせる
  - 副作用として **sbt と npm の二重管理**になる（`sbt` の前に `npm install` が必要）。
    Gemini CLIレビューは「bundler経由の `npmDependencies` へ一元化せよ」と指摘したが、
    **上記の理由で採用できない**。将来この構成を「直そう」として bundler へ戻すと ESModule が壊れるので、
    戻さないこと。`bbs/package-lock.json` は再現性のためコミットする
  - **変換結果**: `hono@4.12.14` = 成功だが巨大（**442ファイル / 42k行 / jar 4.85MB**）。
    暗黙に生成される `std`（dom+es6）が **2229ファイル / 205k行 / 26.4MB** で、hono変換に伴う不可避の
    オーバーヘッド。`encoding-japanese@2.2.0` = 素直に成功（5ファイル / 613行）。
    **`@cloudflare/workers-types` = 失敗**（既定スタックで `StackOverflowError`、`-Xss512M` でも
    30分・CPU 2000秒で終わらず中断）→ **手書き `@js.native` facade に落とす**（v6の
    `external/cloudflare` と同じ形。TS-004/TS-005/TS-010 で必要になった分だけ書く）
  - **生成facadeの型の質**: honoの複雑な型は一部が近似に落ちる。`type MergePath[A,B] = B`
    （生成コードに「Mapped type はScalaへ変換不能。キャストで回避せよ」とコメントが入る）、
    `Context.html(...)` は条件型の変換に失敗して `js.Any` に退化。呼び出し側では
    `val h: Handler[Env, "/", BlankInput, Response] = ...` のような**明示的な型注釈が必要**
    （ハンドラのラムダをそのまま `get(...)` に渡すと、`H = Handler | MiddlewareHandler` の
    ユニオン型と約11個のオーバーロードで解決できない）
- **[判断済] TS-001: バージョン選定**（2026-09-07、Maven Central / GitHub Releases で存在確認の上決定。
  すべて `build.sbt` 冒頭の `val` で一元管理）:
  - **Scala 3.3.8**（LTS系列）。cross project（JVM+JS）と Spring Boot（JVM）を同一ビルドで扱うため、
    最新の非LTS系列（3.9.x等）より互換性リスクが低いLTSを優先した
  - **sbt 1.13.0**（ユーザー指定・固定）。sbt-scalajs 1.17.0 / sbt-scalajs-crossproject 1.3.2
    （sbt-crossprojectを内包）はこの組み合わせで問題なく動作した
  - **munit 1.0.0**: ScalaTestではなくmunitを採用（ユーザー指定）。JVM/JS両対応かつ
    `test("日本語文字列")  { ... }` の形式で振る舞い記述がしやすく、v6のバッククォート日本語命名の
    思想をそのまま引き継げる
  - **Laminar 17.2.1**（frontendBbs/frontendAdmin）、**scalajs-dom 2.8.0**（frontendBg）
  - **Spring Boot 3.3.5**: bg/batchのSpring依存（webflux, thymeleaf, batch）はJavaライブラリとして
    素のGAV（`"org.springframework.boot" % "..." % version`、`%%`ではなく`%`）で追加する。この時点では
    起動確認はしておらず、コンパイルが通ることのみ確認（起動確認はTS-030/TS-040）
- **[判断済] TS-001: common cross projectは `CrossType.Pure`**: ユーザー指定のディレクトリ表で
  `common/` が単一ディレクトリとして書かれているため、`common/js`・`common/jvm`・`common/shared`
  の3分割になる `CrossType.Full`（デフォルト）ではなく、共有ソースが `common/src/main/scala` 直下に
  来る `CrossType.Pure` を採用した。プラットフォーム別コードが必要になったら
  `common/.jvm/src/main/scala`・`common/.js/src/main/scala` に追加する
- **[判断済] TS-001: Scala.jsのテストはNodeJSEnv（jsdom無し）のまま**: Laminar/scalajs-domを使う
  frontend系プロジェクトのプレースホルダテストは、DOM（`document`）に触れる関数（`rootNode`等）を
  直接呼ばず、DOM非依存の純粋関数のみを検証することで、jsdom等の追加セットアップ無しに
  `sbt test` を緑にした。実際のDOM操作をテストする段階（TS-015/TS-020/TS-033）で
  `jsdom-nodejs-env` 等の導入を検討する

## ビルド・開発コマンド（TS-001で確定。実行して成功を確認済み）

前提: JDK 21 or 25（`JAVA_HOME`で切替。TS-001時点ではJDK25のままで全コマンドが成功しており、
JDK21へのフォールバックは不要だった）、Node.js 22.18.0、sbt 1.13.0。sbt起動はフルパス推奨:
`C:\Users\kouki\tools\sbt\bin\sbt`

```bash
# 0. 初回のみ（および各 package.json を変更したとき）: npm依存を用意する。**sbtは自動実行しない**
#    - bbs/          : ScalablyTyped が参照する node_modules（ExternalNpm方式。技術判断ログ TS-002 参照）
#    - frontend/bbs/ : jsdom（DOMテスト用。技術判断ログ「jsdomテスト環境」参照）
cd bbs && npm ci && cd ..
cd frontend/bbs && npm ci && cd ../..

sbt compile          # 全サブプロジェクトのコンパイル
sbt test             # 全サブプロジェクトのテスト実行（munit。JS側はNode.jsで実行）
sbt "bbs/fastLinkJS" "frontendBbs/fastLinkJS" "frontendAdmin/fastLinkJS" "frontendBg/fastLinkJS"
                      # Scala.js 4プロジェクトのリンク確認

# Cloudflare Workers をローカル起動（TS-004で実測確認済み）
sbt "bbs/fastLinkJS"          # 先に bbs をリンクする（worker.mjs が成果物を直接importするため必須）
wrangler dev --local          # http://127.0.0.1:8787/ に 200 応答
# ※ worker.mjs は開発用の bbs-fastopt を参照している。本番相当は fullLinkJS（bbs-opt）
```

実測（2026-09-07、JDK25・Node22.18.0・sbt 1.13.0、いずれも初回実行）:
- `sbt compile`: 約52秒（sbt本体・プラグイン・全依存のダウンロード込み）。
  common.js / common.jvm / bbs / frontendBbs / frontendAdmin / frontendBg / bg / batch の
  7サブプロジェクト全てコンパイル成功
- `sbt test`: 約24秒（sbt内部の実測総時間は17秒）。JVM側（common.jvm, bg, batch）・
  JS側（common.js, bbs, frontendBbs, frontendAdmin, frontendBg）の計7プロジェクト全てで
  munitテストが緑。日本語テスト名（`test("空の入力は名無しさんを返す")`形式）もコンソールへ
  正しく表示された
- `bbs/fastLinkJS` 他3件のScala.jsリンク確認: 約10秒、4プロジェクトとも成功

## 参考ドキュメント
- 移植元: `C:\project\3ch\v6\CLAUDE.md`, `C:\project\3ch\v6\docs\ch3-v6.md`,
  `C:\project\3ch\v6\docs\architecture_change_plan_202607.md`, `C:\project\3ch\v6\docs\admin_IA3.md`
  ほか `C:\project\3ch\v6\docs\feature\*.md`（ドメイン仕様の正はここ。v6-s側では複製しない）
- `docs/screens.md` — **作る画面の正**（2026-09-10 ユーザー指示。v6の現状・`admin_IA*.md` より優先）
- `docs/tasks.md` — 本プロジェクトのタスク一覧・進捗の**索引**（正）。各タスク詳細は `docs/tasks/TS-xxx.md`
