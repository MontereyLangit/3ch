# 3ch 掲示板システム (ch3s) 開発ガイド

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

`ch3s` は 2ch 互換掲示板「3ch（３ちゃんねる）」の **現行稼働システム = v6（Scala.js + HonoX）** のリポジトリ。
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
| **`ch3s`（本リポジトリ）**             | **v6 = 現行稼働システム**。Scala.js + HonoX on Cloudflare Workers + DO + R2 + KV（旧 v5 はタグ `v5-final`）                                                                                                   |
| `ch3s-migration`                       | **Scala.js × HonoX 移植の参照実装**（後述 step 3 の手本）                                                                                                                                                     |
| `ch3/3chiko`                           | 旧フロントエンド配布元（`3chiko.html`, Cloudflare Pages 経由）。**2026-09-25、`ch3s` へ統合済み**（本体は `public/3chiko.html` 1本。Workers Static Assets(`env.ASSETS`)経由で配信）。以後はこちらを更新しない |
| `3ch_ui`                               | 新 UI のデザイン案・HTML モック                                                                                                                                                                               |
| `v6`, `v6-s`, `v6-k`, `v6-s2`, `v6-bp` | v6 検討・試作（Kotlin/Spring Boot 版含む）                                                                                                                                                                    |
| `kako-data`                            | 過去ログデータ（step 6 の移行対象）                                                                                                                                                                           |
| `3ch_workers`                          | v5 から途中で分離した別系統（`mmr-3ch/3ch_workers`）。TS + Hono JSX 移行試作を含む（1.7）                                                                                                                     |

### 1.3 技術スタック（現行 v6）

- **ランタイム**: Cloudflare Workers (ES Modules, `compatibility_date: 2026-08-04`。この日付以降は `nodejs_compat` が既定で有効)
- **言語 / フレームワーク**: Scala 3 / Scala.js（`bbs/` = Worker・DO・ハンドラ、`common/` = 純粋ロジック）+ HonoX + Vite。ルーティングは `common/.../routes/RouteTable.scala` から `codegen` が生成
- **状態管理**: Durable Objects — `Board`（板: スレ一覧 / 削除済み一覧）, `Thread`（スレ: dat / log / settings / hidden / stopped / movedTo）
- **オブジェクトストレージ**: R2 `ch3www`（`SETTING.TXT`, `head.txt`, `bbsmenu.html`, `headline.txt`, `deny.cgi`, `ngwords.cgi`, 過去ログ `.cgi`）
- **KV**: `CAP`（キャップ: パスワードハッシュ → 表示名）, `TEST`
- **暗号**: Web Crypto (SHA-1 / SHA-256) + 自前 MD5 / Unix crypt(3) DES（トリップ用。`common/`）
- **文字コード**: 自前 Shift_JIS (JIS X 0208) エンコーダ/デコーダ（`bbs/.../util/SjisEncoding.scala`、背景は `docs/sjis差異一覧.md`）

### 1.4 ディレクトリ構成

```
common/                Scala (JVM/JS cross)。Shift_JIS 以外の純粋ロジック（MD5, crypt(3), ルート表 RouteTable.scala 等）
bbs/
  src/main/scala/ch3s/bbs/
    Main.scala         Worker エントリ（JSExportTopLevel）
    handler/           PublicRoutes（dat, subject.txt, SETTING.TXT, bbsmenu 等）, Posting（投稿パイプライン）,
                       Moderation, AdminHandler（/admin/*）, Responses
    durable/           Board DO, Thread DO（V6PlaceholderDurableObject は骨格確認用の残り）
    external/cloudflare/  Workers / DO / R2 / KV の facade
    hash/, util/       Web Crypto ハッシュ, Shift_JIS 変換
  src/test/            munitによるScala側テスト。単体テストはこちらで実装する。
  test-stubs/          sbt の Test / jsEnv が cloudflare:workers を差し替えるスタブ
bbs-client/            クライアント側（hydration）Scala
codegen/               RouteTable.scala から app/routes/ を生成
app/                   HonoX（server.ts がエントリ。routes/ は生成物）
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
| `docs/v6s実装メモ.md`                                                                  | Scala.js 実装（`bbs/`）の設計背景。doc コメントから参照される（3.5）                                                                                                                  |
| `docs/admin.md`                                                                        | 管理画面の利用手順・規制ルール書式                                                                                                                                                    |
| `docs/admin-review.md`                                                                 | 管理画面追加時のレビュー記録（セルフ / Gemini CLI）                                                                                                                                   |
| `docs/実装方針251030v1_ストレージ改修.md`                                              | ストレージ改修の実装方針                                                                                                                                                              |
| `docs/詳細設計たたき台_ストレージ改修.md` / `docs/詳細設計仮案(AI)1_ストレージ改修.md` | 同上の設計案                                                                                                                                                                          |
| `../ch3s-migration/GEMINI.md`                                                          | Scala.js × HonoX 構成の規約（step 3 で準拠する）                                                                                                                                      |

### 1.6 開発コマンド

```bash
npm ci                 # 依存導入
npm run v6:build       # sbt codegen/run → vite build（client / workers）→ dist/
npm run dev            # v6:build 後に wrangler dev --config wrangler.jsonc → http://127.0.0.1:8787
sbt test               # 単体テスト/現仕様固定テスト（munit。golden 照合を含む）
npm test               # 実 workerd を使う統合テストのみ（要 dist/）
npm run deploy         # v6:build 後に wrangler deploy --config wrangler.jsonc --minify
```

管理画面のローカル利用は `.dev.vars` に `ADMIN_PASSWORD="..."` を設定（Git 管理外）。
本番は `npx wrangler secret put ADMIN_PASSWORD --config wrangler.jsonc`。

### 1.7 参考ブランチ `feature/transfering_to_ts_hono_jsx`

`mmr-3ch/3ch_workers` の同名ブランチを本リポジトリ（`3ch-io/v5`）へそのまま載せたもの（2026-09-24、
先頭 `f3b6511`、49 コミット、2025-09-10〜10-05）。**v6 実装の参考資料であり、`develop` / `develop_v6s` へは統合しない。**
`develop` 系と共通祖先がない独立した履歴なので、参照は `git show origin/feature/transfering_to_ts_hono_jsx:<path>` 等で行う。

**内容**: v5 系 JS を **TypeScript + Hono + hono/jsx** へ書き換えた試作（`src/index.tsx` 起点、MVC 風に
`services/`・`model/`・`views/` へ分割）。

- ルート: `POST /test/bbs.cgi`・`/test/subbbs.cgi`、`GET /:bbs/`、`/:bbs/:key.dat`、`/:bbs/subject.txt`・`head.txt`・`SETTING.TXT`、
  `/test/read.cgi/:bbs/:key/`、`/boards/:bbs/`、認証用 `GET /api/auth.js`・`POST /api/auth`
- 投稿: `services/PostService.ts` に検証 → 板/スレ設定読込 → 規制（deny リスト・立て過ぎ等）→ ID/トリップ → 保存のパイプライン。
  設計メモは `post.markdown`（v5 系 `bbs_cgi` の関数単位の説明）
- 認証: 新設の `User` DO による忍法帖相当のユーザー識別（`services/AuthService.ts`、`views/auth*.ts(x)`）
- 画面: `views/` の JSX テンプレート（`3chiko.template.tsx`・板トップ・投稿結果・エラー）と、
  `.html/itest3ch/` の Web 版 HTML ひな形（htmx 使用）

**v5 / step 3 との違い（参考にする際の注意）**:

- **ストレージスキーマが v5 と異なる**: DO は `Bbs` / `Thread` / `User`（v5 は `Board` / `Thread`）。`SETTING.TXT`・`head.txt`・
  `subject.txt` を `Bbs` DO のストレージに持ち、R2 バインディングがない。
- Shift_JIS 変換は `encoding-japanese`、crypt(3) は `unix-crypt-td-js` を使う。v5 とのバイト単位の一致は検証されていない
  （Shift_JIS の差異は `docs/移植方針_step3_ScalaJS.md` R-01 参照）。
- 以上から **step 3（仕様・スキーマ不変の移植）の手本にはしない**。新仕様を扱う step 4 / 5（UI・`User` 相当の認証・
  規制の設計）の参考として使う。
- `src/index.tsx` は `@/durable_objects/Bbs` 等を大文字で import しているが、実ファイルは小文字（`bbs.ts`）。
  大文字小文字を区別しないファイルシステム（Windows・既定の macOS）でしかビルドできない。
- `src/services/AuthService.ts` に認証署名用の値がハードコードされている。流用する場合は secret に移すこと（値を転記しない）。

---

## 2. 移植計画（ロードマップ）

本リポジトリで進める作業の全体像。**上から順に実施する。** Step 1〜3 は完了（本番は v6 に切替済み）。

### Step 1. 作業ブランチ整備（完了）

`develop` から作業用ブランチ **`develop_v6s`** を作成し、`feature/add_controll_panel`（管理画面 + 投稿モデレーション）を取り込む。以降の作業はこのブランチを起点とする。

### Step 2. キャラクタリゼーションテスト追加（仕様固定化）（完了）

Scala.js 移植の前提として、**現行 v5 の振る舞いをテストで固定する**。

- 目的は「正しさ」の検証ではなく **現行の実動作の写し取り**。
- 重点範囲: dat フォーマット（`<>` 区切り・本文前後の半角スペース・第5フィールド）、ID / 識別ID / トリップ / キャップ生成、Shift_JIS 変換、投稿パイプラインの検証順序とエラー文言、Range / If-Modified-Since、規制判定、Board/Thread DO の状態遷移。

### Step 3. JS → Scala.js 移植 + HonoX 導入（完了）

`ch3s-migration` を参照実装として、**バックエンドをリプレイス**する。

- **仕様は step 2 で固定したものから変えない。**
- フロントエンドは **`3chiko.html` のまま**（2026-09-25 に `ch3s` へ統合済み。本体は `public/3chiko.html`
  1本を Workers Static Assets(`env.ASSETS`)で配信する。ロジックは変更しない）。
- **DB / ストレージ構成の変更なし**（DO / R2 / KV のスキーマそのまま）。
- 構成規約は `../ch3s-migration/GEMINI.md` に準拠（Scala 3 / Scala.js, HonoX, React 19, Vite, 単一 sbt プロジェクト, ルーティングは `RouteTable.scala` からの自動生成、Scala↔TS 境界型の制約）。

#### 移植の順序

純粋な部分から積み上げ、各層を step 2 のテストで検証してから次へ進む。

1. `common` 相当（Shift_JIS 変換, MD5, crypt(3) DES, SHA ヘルパ）— 純粋関数のみで最も検証しやすい
2. Durable Objects（`Board`, `Thread`）
3. `moderation`
4. `bbs.cgi` 投稿パイプライン
5. `main` ルーティング + HonoX 配線
6. `admin`

#### 等価性の検証方式（移植時の記録）

移植中は二段構えで v5 との等価性を示した。**この検証は完了し、v5 のソース（`src/`）とともに廃止した**（`v5-final` タグに残る）。

- **単体レベル**: v5 で記録した入出力表を `tests/golden/*.json` に固定し、Scala 側の単体テストが同じ表を読んで照合する。**この表は現仕様の固定として残す**（再生成元の v5 は無いので手で編集しない）。
- **HTTP レベル**: v5 と v6 を別ポートの `wrangler dev` で同時起動して突き合わせる差分テストを行った。**廃止済み。**

#### 現仕様の固定（移植後）

v6 の振る舞いは次で固定する。仕様変更時は、対応するアサーションを意図的に更新する。

- **`sbt test`（Scala, munit）が主**: `bbs/src/test/scala/ch3s/bbs/`
  - `handler/PostingSuite`（投稿パイプライン）、`PublicRoutesSuite`（公開ルーティング）、`RangeSuite`（dat の Range / 304）、
    `ModerationSuite`、`AdminSuite`、`durable/ThreadSuite`・`BoardSuite`（DO）。
  - R2 / KV / `caches.default` / ASSETS / 時刻・乱数のフェイクは `durable/DurableTestKit` と `handler/HandlerTestKit`。
    Date / Math.random / `caches` を差し替えるため、`bbs` は `Test / parallelExecution := false`。
  - 期待値は v5 から引き継いだもの。KB-nn コメント付きのアサーションは既知不具合の現挙動の固定。
  - `common/src/test` と `WebHashSuite` / `SjisEncodingSuite` は `tests/golden/*.json` と照合する。
- **`npm test`（node:test）**: 実 workerd（`unstable_dev`）が要る `tests/admin.integration.test.js` のみ。
  `dist/index.js`（`npm run v6:build`）が無いと skip される。

### Step 4. フロントエンドを新仕様で Next.js 化

- **新仕様は別途検討**（`3ch_ui/` のモックが起点）。
- **DB 変更なし。**

### Step 5. バックエンドを新仕様へ移行

- 新仕様バックエンド + **`bg`（バックグラウンドサービス）追加** + **DB 変更あり**。

### Step 6. データ移行・過去ログアップロード

`kako-data` 等の既存データを新スキーマへ移行し、過去ログを投入する。

---

## 3. 作業規約（AI エージェント向け）

### 3.1 実装の委譲

- **実装作業は Sonnet 5 サブエージェント、または Codex CLI に委譲する。**
  オーケストレーター（Opus）は計画・分解・レビュー統合・最終判断を担う。
- サブエージェントへの指示は自己完結させる（ファイルパス・仕様の根拠・完了条件を明記）。

### 3.2 レビューチェーン（実装後、必ずこの順で実施）

1. **セルフレビュー** — 仕様書との突き合わせ、差分の妥当性確認、テスト実行。
2. **Codex CLI レビュー** — `codex` コマンドでレビューを依頼。
3. **Gemini CLI レビュー** — `gemini` コマンドでレビューを依頼。

各レビューの指摘は「判断・対応」とセットで `docs/` 配下のレビュー記録に残す
（`docs/admin-review.md` の書式を踏襲）。指摘を鵜呑みにせず、現行仕様・テストに照らして採否を判断すること。

> 外部 CLI にコードを送信する前に、利用者の許可を取ること（`docs/admin-review.md` の先例に従う）。

### 3.3 既知不具合の扱い — 記録してから直す

step 3 完了までは「v5 とバイト単位で同一の出力」を検証の軸にしていたため、バグを見つけても直さず記録だけにしていた。
**v5 との差分検証は廃止したので、修正してよい。** ただし次の手順を守る。

1. `docs/既知不具合台帳.md` に `KB-nn` を採番して追記する（未記録のものを見つけた場合）。
2. 修正は 1 件ずつ独立したコミットにし、現挙動を固定しているアサーション（`// KB-nn:` コメント付き）を
   意図した新挙動へ更新する。台帳のステータスも更新する。
3. 3.4 の不変条件に触れる修正（ID / トリップ / キャップの値、dat 構造、Shift_JIS 出力など）は、
   既存ユーザーへの影響を台帳に書き、利用者の承認を得てから行う（KB-14 / KB-19 など）。
4. 仕様書の記述と実動作が食い違う場合は、**コードの実動作を正として**仕様書側を訂正する。

### 3.4 変更時の不変条件（壊してはいけないもの）

- **専ブラ互換**: dat の `<>` 5 フィールド構造、本文フィールド前後の半角スペース、エラー時も HTTP 200 + `<!-- 2ch_X:error -->` マーカー、`Range` / `304` の挙動。
- **Shift_JIS 出力**: 公開エンドポイントの文字コードを変えない。
- **ID / トリップ / キャップの計算結果**: 同一入力に対し同一の値が出ること（ハッシュ仕様の変更は既存ユーザーの ID 変化＝互換性破壊）。
- **ストレージスキーマ**: step 4 までは DO / R2 / KV のキーとフォーマットを変更しない。

### 3.5 コーディング規約

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
- **Scaladoc / JSDoc は簡潔に**（2026-09-24 ユーザー指示）: ドキュメントコメントには「何をするか」と
  利用者が知るべき制約（意図的な v5 との差異の KB 番号など）だけを 1〜3 行で書く。
  経緯・却下した代案・実測値・性能上の理由・元コードの逐語引用などの**背景情報は `docs/` 配下に書き**、
  コメントからは文書名と節で参照するだけにする。
  - 理由: 長い doc コメントはコードを読む妨げになり、方針変更のたびに文書と食い違う
    （`SjisEncoding.scala` の冒頭 48 行の Scaladoc が実例。背景は `docs/sjis差異一覧.md` へ移した）。
- **Durable Object はインフラ操作だけを持つ**（2026-10-16 ユーザー指示）: DO（`bbs/.../durable/`）に書いてよいのは
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

### 3.6 セキュリティ

- `wrangler.jsonc` の `salt` や `ADMIN_PASSWORD` 等の秘匿値をログ・コミットメッセージ・外部 CLI への送信内容に含めない。
- 管理画面は Basic 認証 + Origin 検証 + no-store + CSP 前提。この防御を弱めない。
