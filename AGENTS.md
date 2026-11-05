# 3ch 掲示板システム 開発ガイド

AI アシスタント（Claude Code / Gemini CLI / Codex CLI）および開発者向けの、本リポジトリにおける
プロジェクト概要・移植計画・作業規約。

> **本ファイル `AGENTS.md` が唯一の原本。** 各エージェントは次の経路でこれを読む。
>
> - Codex CLI: `AGENTS.md` を直接読む。
> - Claude Code: v2.1.277 以降は、`CLAUDE.md` がなければ `AGENTS.md` を読む（Bedrock / Vertex / Foundry 経由は未対応）。
> - Gemini CLI: `.gemini/settings.json` の `context.fileName` で `AGENTS.md` を指定。
>
> `CLAUDE.md` や `GEMINI.md` を作らないこと（`CLAUDE.md` があると Claude Code は `AGENTS.md` を読まなくなる）。以前は3ファイルに同じ内容を
> 持たせていたが、片方だけ直した結果、Shift_JIS の方針が別ファイルで逆向きに書き換わる事故が起きた。

---

## 1. プロジェクト概要

### 1.1 これは何か

`3ch`（旧プロジェクト名 `ch3s`）は 2ch 互換掲示板「3ch（３ちゃんねる）」の **現行稼働システム = 3ch（Scala.js + HonoX）** のリポジトリ。
Scala のパッケージは `io._3ch.*`。ローカル作業ディレクトリは `C:/project/3ch/ch3s/` のままとする。
Cloudflare Workers / Durable Objects / R2 / KV 上で動く。専用ブラウザ（専ブラ, Monazilla 系）互換の
dat / subject.txt プロトコルを提供する。

もとは素の JavaScript 実装（v5）だった。**v5 から v6 への移植（step 3）は完了し、本番は v6 に切り替え済み**。
v5 のソース（`src/`）は削除した。参照が必要なときは git タグ **`v5-final`** を使う
（`git show v5-final:src/common.js` など）。docs や Scala のコメントにある `src/*.js` への言及は、このタグ上のファイルを指す。

本リポジトリのゴールは、v6 を土台に step 4 以降（新 UI・新仕様バックエンド・データ移行）へ進めること。

### 1.2 3ch 系プロジェクトの位置づけ（`C:/project/3ch/`）

| PJ                                     | 内容                                                                                                                                                                                                          |
| :------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `2ch`, `readcgi`, `gns`, `bbs18c`      | 参考用の歴史的実装（perl / C / PHP）                                                                                                                                                                          |
| `v3`, `delightly-v2`(=v4)              | 旧 3ch システム（PHP）。現在ほぼ未使用                                                                                                                                                                        |
| **`3ch`（本リポジトリ）**             | **v6 = 現行稼働システム**。Scala.js + HonoX on Cloudflare Workers + DO + R2 + KV（旧 v5 はタグ `v5-final`）                                                                                                   |
| `ch3s-migration`                       | **Scala.js × HonoX 移植の参照実装**（後述 step 3 の手本）                                                                                                                                                     |
| `ch3/3chiko`                           | 旧フロントエンド配布元（`3chiko.html`, Cloudflare Pages 経由）。**2026-09-25、`3ch` へ統合済み**（本体は `public/3chiko.html` 1本。Workers Static Assets(`env.ASSETS`)経由で配信）。以後はこちらを更新しない |
| `3ch_ui`                               | 新 UI のデザイン案・HTML モック                                                                                                                                                                               |
| `v6`, `v6-s`, `v6-k`, `v6-s2`, `v6-bp` | v6 検討・試作（Kotlin/Spring Boot 版含む）                                                                                                                                                                    |
| `kako-data`                            | 過去ログデータ（step 6 の移行対象）                                                                                                                                                                           |
| `3ch_workers`                          | v5 から途中で分離した別系統（`mmr-3ch/3ch_workers`）。TS + Hono JSX 移行試作を含む（1.7）                                                                                                                     |

### 1.3 技術スタック（現行 v6）

- **ランタイム**: Cloudflare Workers (ES Modules, `compatibility_date: 2026-08-04`。この日付以降は `nodejs_compat` が既定で有効)
- **言語 / フレームワーク**: Scala 3 / Scala.js（`bbs/` = Worker・DO・ハンドラ、`common/` = 純粋ロジック）+ scalajs-react + HonoX + Vite。`codegen` は `bbs.Main` の `@route` と `bbsclient.Main` の `@islands` から配線を生成。既存 fetch の `app/routes/index.ts` は手書き
  - 管理画面はscalajs-reactのSPA。利用者向け画面はHonoX SSR (Islands)
- **状態管理**: Durable Objects — `Board`（板: スレ一覧 / 削除済み一覧）, `Thread`（スレ: dat / log / settings / hidden / stopped / movedTo）
- **オブジェクトストレージ**: R2 `ch3www`（`SETTING.TXT`, `head.txt`, `bbsmenu.html`, `headline.txt`, `deny.cgi`, `ngwords.cgi`, 過去ログ `.cgi`）
- **KV**: `CAP`（キャップ: パスワードハッシュ → 表示名）, `TEST`
- **文字コード**: 自前 Shift_JIS (JIS X 0208) エンコーダ/デコーダ（`bbs/.../util/SjisEncoding.scala`、背景は `docs/sjis差異一覧.md`）

### 1.4 ディレクトリ構成

```
common/                Scala (JVM/JS cross)。純粋ロジック（ルート表 RouteTable.scala, BoardId / ThreadKey / BbsSettings 等）
bbs/
  src/main/scala/io/_3ch/bbs/
    Main.scala         Worker エントリ（JSExportTopLevel）
    handler/           PublicRoutes（dat, subject.txt, SETTING.TXT, bbsmenu 等）, Posting（投稿パイプライン）,
                       Moderation, AdminHandler（/admin/*）, Responses
    durable/           Board DO, Thread DO
    external/cloudflare/  Workers / DO / R2 / KV の facade
    hash/, util/       Web Crypto ハッシュ・MD5 の npm facade（`@noble/hashes`）, Shift_JIS 変換
  src/test/            munitによるScala側テスト。単体テストはこちらで実装する。
  test-stubs/          sbt の Test / jsEnv が cloudflare:workers を差し替えるスタブ
bbs-client/            クライアント側（hydration）Scala
codegen/               Main の @route / @islands から app/routes/・app/islands/・bbs の IslandComponents を生成
app/                   HonoX（server.ts がエントリ。routes/index.ts と _renderer.tsx は手書き）
public/
  3chiko.html          3chiko.html本体。Vite の publicDir として dist/ へコピーされ Workers Static Assets で配信
tests/                 実workerdを使う統合テスト（要 dist/）用ディレクトリ。admin.integration.test.js（実 workerd）。golden/ は言語中立の入出力表（Scala 側が読む）
docs/                  仕様書・設計メモ（後述）
wrangler.jsonc         本番 Wrangler 設定（main = dist/index.js）
```

### 1.5 必読ドキュメント

| ファイル                                                                               | 位置づけ                                                                                                                                                                              |
| :------------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`docs/現行システム仕様書.md`**                                                       | **最重要。v5 の全ロジックを抽出した仕様書（版 2.0.0）。v6 は同一仕様を実装している。** 文中の `src/*.js` はタグ `v5-final` 上のファイル。第11章に既知の不具合とv6推奨仕様の対照表あり |
| **`docs/既知不具合台帳.md`**                                                           | **既知不具合の一覧（KB-nn）。** 新たに見つけた不具合もここに追記する。修正方針は 3.3                                                                                                  |
| `docs/v6s実装メモ.md`                                                                  | Scala.js 実装（`bbs/`）の設計背景。doc コメントから参照される                                                                                                                  |
| `docs/トリップ・ID廃止とスレ主表示.md`                                                 | 2026-11-02 の仕様変更（トリップ・ID 廃止、`!id`、スレ主表示 ` (主)`）。認証ブランチとの差分を含む                                                                                      |
| `docs/admin.md`                                                                        | 管理画面の利用手順・規制ルール書式                                                                                                                                                    |

### 1.6 開発コマンド

```bash
npm ci                 # 依存導入
npm run v6:build       # sbt codegen/run → vite build（client / workers）→ dist/
npm run dev            # v6:build 後に wrangler dev --config wrangler.jsonc → http://127.0.0.1:8787
sbt test               # 単体テスト/現仕様固定テスト（munit。golden 照合を含む）
npm test               # Scala テスト用リンク → workerd 統合・Islands SSR/hydration 検証（要 dist/）
npm run deploy         # v6:build 後に wrangler deploy --config wrangler.jsonc --minify
```

管理画面のローカル利用は `.dev.vars` に `ADMIN_PASSWORD="..."` を設定（Git 管理外）。
本番は `npx wrangler secret put ADMIN_PASSWORD --config wrangler.jsonc`。

---

## 2. 開発計画（ロードマップ）

### 【完了】Step 1. 移植フェーズ: Vanilla JSからscalajs-react × HonoX構成に移植
### 【進行中】Step 2. 小規模機能追加/改善/リファクタリングフェーズ: 現行システムの問題点の改善・リファクタリングおよび、現代2ch型掲示板の必須機能の追加
- 26年中はこのフェーズを進める予定。
- レガシーフロントエンド(`3chiko.html`)からHonoX画面への移行・新UI化も進める
  - 現代掲示板としてWeb版のUIも改善していきたい
    - 移行(3chiko.html画面全廃)完了後、3chiko.htmlは削除
- 認証や規制などbbs.cgi必須機能の追加+アカウント制導入(認証のみの仮登録/ID・PASS方式/Google OAuth)
- **基本的にDB 変更はここではしない**

### Step3. データ移行・過去ログアップロードフェーズ
- 現行のデータストレージにはかなりパフォーマンス上の問題があり、実況・規模拡大時に耐えられるか不明
  - このフェーズでデータストレージを改修しデータ移行を行う。
- データストレージ改修後に過去ログ投入もしたい。`kako-data` 等の既存データを新スキーマへ移行し、過去ログを投入する。
- 26年の年末？～27年頭に進める予定

### Step4. 3chへの付加価値追加（大規模機能追加）フェーズ：ActivityPub、Workers AIによるAI統合モデレーション、検索機能、
- 検索機能はStep3でやる可能性もある
- Step3終わり次第進める

## 3. 作業規約（AI エージェント向け）

### 3.1 実装の委譲

- **実装作業はサブエージェントに委譲する。ユーザーから指示があった場合はそちらを優先する**。
  オーケストレーターは計画・分解・レビュー統合・最終判断を担う。
- サブエージェントへの指示は自己完結させる（ファイルパス・仕様の根拠・完了条件を明記）。

### 3.2 変更時の不変条件（壊してはいけないもの）

- **専ブラ互換**: dat の `<>` 5 フィールド構造、本文フィールド前後の半角スペース、エラー時も HTTP 200 + `<!-- 2ch_X:error -->` マーカー、`Range` / `304` の挙動。
- **Shift_JIS 出力**: 2ch専用ブラウザ（Monazilla）向け出力の文字コードを変えない。
- **ストレージスキーマ**: データストレージ改修フェーズまでは DO / R2 / KV のキーとフォーマットを変更しない。

### 3.3 コーディング規約
- scalajs-reactのコンポーネントは原則関数コンポーネント（ScalaFnComponent）とする。React公式がクラスコンポーネントの新規採用を推奨していないため。
- 既存コードのスタイルに合わせる。不要なコメント・過剰な抽象化を入れない。
- TypeScript の `any`、Scala の未型付け構造型を避ける。コンパイル / Lint 警告は抑制せず解消する。
- **Scala の Optional Braces（インデント構文）は原則使わない**（2026-09-08 ユーザー指示）: Scala 3 の
  brace省略（`if ... then` の後ろをインデントだけで書く、`class Foo:` / `def f() =` の後を
  インデントブロックにする等）は、**それを使うことで明らかに可読性が良くなる箇所に限定**する。
  **基本は波括弧 `{}` を使う**。
  - 理由: 省略と波括弧が混在すると読み手が構文境界を追いにくく、特に**深いネストや長いメソッドで
    どこまでがブロックか分からなくなる**。Kotlin（波括弧必須）からの移植コードとの見比べも難しくなる。
  - `if ... then` / `while ... do` などの新制御構文自体は可。複数行になるブロックは `{}` で囲む。
    `match` の `case ... =>` 本体は従来どおり波括弧不要。
  - 既存ソースは `-no-indent -rewrite` で一括変換済み。残存確認は
    `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile` がエラーなく通ることで行う
    （例外を許す方針のため、ビルド設定では `-no-indent` を常時強制しない）。
- **Scaladoc / JSDoc は簡潔に**: ドキュメントコメントには「何をするか」と
  利用者が知るべき制約だけを 1〜2 行で書く。タスク番号(K-*など)は書かないこと。
  経緯・却下した代案・実測値・性能上の理由・元コードの逐語引用などの**背景情報は `docs/` 配下に書く**。
  - 理由: 長い doc コメントはコードを読む妨げになり、方針変更のたびに文書と食い違う
    （`SjisEncoding.scala` の冒頭 48 行の Scaladoc が実例。背景は `docs/sjis差異一覧.md` へ移した）。
- **Durable Object はインフラ操作だけを持つ**: DO（`bbs/.../durable/`）に書いてよいのは
  ストレージの読み書き（キー設計・ページ分割・一括保存）、`blockConcurrencyWhile` による直列化、他 DO / R2 / KV の呼び出し、
  RPC の引数・戻り値と保存形式の変換（コーデック）だけとする。DO の外で書けるロジックは
  ドメインモデル / ドメインサービス（`bbs/.../domain/`、または `common/`）に純粋関数として置く。
  - 状態遷移（予約・確定・取消など）、保持・切り捨て規則、規制判定とその組み合わせ、期限の計算はドメイン側の型のメソッドにする。
    DO のメソッドは「ロック → 読込 → デコード → ドメイン呼出し → エンコード → 保存」の形にとどめる。
  - DO の中で `js.Dynamic` のまま業務データを書き換えない。境界で case class / enum に変換する。
  - `durable/` 直下には DO クラスだけを置く。変換（コーデック）は `durable/codec/`、ロックなど DO 共通の補助は
    `durable/support/` に置く。これらは `js` / Cloudflare 型に依存するので `domain/` には置かない。
  - 現在時刻はドメインの関数に引数で渡す（DO 側で1処理につき1回だけ読む）。ドメイン内で `js.Date.now()` を呼ばない。
  - ユーザー向け文言は DO で組み立てない。DO / ドメインは結果を enum（RPC ではそのコード文字列）で返し、文言への変換は handler 側で行う。
  - テストもこれに合わせ、ドメインの振る舞いは `bbs/src/test/.../domain/` の純粋な単体テストで、DO のテストは
    直列化・保存形式・障害時の扱いなどインフラ固有の項目で確かめる。
  - 理由: DO 内のロジックはテストに DO のテストキットと時刻の差し替えが要り、規則が保存処理に埋もれて読み取れなくなる。
- テストは `npm test` で通ること。統合テストは実 Wrangler をローカルストレージで起動して検証する（本番ストレージは使わない）。

### 3.4 セキュリティ

- `wrangler.jsonc` の `salt` や `ADMIN_PASSWORD` 等の秘匿値をログ・コミットメッセージ・外部 CLI への送信内容に含めない。

