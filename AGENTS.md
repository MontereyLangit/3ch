# 3ch 掲示板システム (ch3s) 開発ガイド

AI アシスタント（Claude Code / Gemini CLI / Codex CLI）および開発者向けの、本リポジトリにおける
プロジェクト概要・移植計画・作業規約。

> **本ファイル `AGENTS.md` が唯一の原本。** 各エージェントは次の経路でこれを読む。
> - Codex CLI: `AGENTS.md` を直接読む。
> - Claude Code: v2.1.277 以降は、`CLAUDE.md` がなければ `AGENTS.md` を読む（Bedrock / Vertex / Foundry 経由は未対応）。
> - Gemini CLI: `.gemini/settings.json` の `context.fileName` で `AGENTS.md` を指定。
>
> `CLAUDE.md` や `GEMINI.md` を作らないこと（`CLAUDE.md` があると Claude Code は `AGENTS.md` を読まなくなる）。以前は3ファイルに同じ内容を
> 持たせていたが、片方だけ直した結果、Shift_JIS の方針が別ファイルで逆向きに書き換わる事故が起きた。

---

## 1. プロジェクト概要

### 1.1 これは何か

`ch3s` は 2ch 互換掲示板「3ch（３ちゃんねる）」の **現行稼働システム = v5** のリポジトリ。
Cloudflare Workers / Durable Objects / R2 / KV 上で動く、フルスクラッチの JavaScript 実装。
専用ブラウザ（専ブラ, Monazilla 系）互換の dat / subject.txt プロトコルを提供する。

本リポジトリのゴールは、この v5 を **Scala.js + HonoX ベースの v6 系へ段階的にリプレイスする**こと。

### 1.2 3ch 系プロジェクトの位置づけ（`C:/project/3ch/`）

| PJ | 内容 |
| :--- | :--- |
| `2ch`, `readcgi`, `gns`, `bbs18c` | 参考用の歴史的実装（perl / C / PHP） |
| `v3`, `delightly-v2`(=v4) | 旧 3ch システム（PHP）。現在ほぼ未使用 |
| **`ch3s`（本リポジトリ）** | **v5 = 現行稼働システム**。Cloudflare Workers + DO + R2 + KV / JS |
| `ch3s-migration` | **Scala.js × HonoX 移植の参照実装**（後述 step 3 の手本） |
| `ch3/3chiko` | フロントエンド（read.cgi 相当の閲覧 UI, `3chiko.html`）。**別リポジトリ** |
| `3ch_ui` | 新 UI のデザイン案・HTML モック |
| `v6`, `v6-s`, `v6-k`, `v6-s2`, `v6-bp` | v6 検討・試作（Kotlin/Spring Boot 版含む） |
| `kako-data` | 過去ログデータ（step 6 の移行対象） |
| `3ch_workers` | v5 から途中で分離した別系統（`mmr-3ch/3ch_workers`）。TS + Hono JSX 移行試作を含む（1.7） |

### 1.3 技術スタック（現行 v5）

- **ランタイム**: Cloudflare Workers (ES Modules, `compatibility_date: 2025-09-19`)
- **状態管理**: Durable Objects — `Board`（板: スレ一覧 / 削除済み一覧）, `Thread`（スレ: dat / log / settings / hidden / stopped / movedTo）
- **オブジェクトストレージ**: R2 `ch3www`（`SETTING.TXT`, `head.txt`, `bbsmenu.html`, `headline.txt`, `deny.cgi`, `ngwords.cgi`, 過去ログ `.cgi`）
- **KV**: `CAP`（キャップ: パスワードハッシュ → 表示名）, `TEST`
- **暗号**: Web Crypto (SHA-1 / SHA-256) + 自前 MD5 / Unix crypt(3) DES（トリップ用）
- **文字コード**: 自前 Shift_JIS (JIS X 0208) エンコーダ/デコーダ

### 1.4 ディレクトリ構成

```
src/
  main.js              ルーティング・公開エンドポイント (dat, subject.txt, SETTING.TXT, bbsmenu 等)
  bbs.cgi.js           投稿・スレ立てパイプライン（検証 → 整形 → ID/トリップ/キャップ → 永続化）
  common.js            Shift_JIS 変換, MD5, crypt(3), SHA ユーティリティ, 板設定既定値
  moderation.js        アクセス規制・NG ワード判定 (deny.cgi / ngwords.cgi)
  admin.js             Web 管理画面 /admin/*（Basic 認証）
  durable_objects/
    board.js           Board DO
    thread.js          Thread DO
tests/                 node:test による単体 / 統合テスト
docs/                  仕様書・設計メモ（後述）
wrangler.jsonc         現行の Wrangler 設定（npm スクリプトはこちらを明示指定）
wrangler.toml          旧構成。参照のみ、使用しない
```

### 1.5 必読ドキュメント

| ファイル | 位置づけ |
| :--- | :--- |
| **`docs/現行システム仕様書.md`** | **最重要。v5 の全ロジックを抽出した仕様書（版 2.0.0）。** 移植の際の「正」はコードとこの文書。第11章に既知の不具合とv6推奨仕様の対照表あり |
| **`docs/既知不具合台帳.md`** | **既知不具合の一覧（KB-nn）。移植完了までは修正せず記録のみ。** 新たに見つけた不具合もここに追記する |
| `docs/v6s実装メモ.md` | Scala.js 実装（`bbs/`）の設計背景。doc コメントから参照される（3.5） |
| `docs/admin.md` | 管理画面の利用手順・規制ルール書式 |
| `docs/admin-review.md` | 管理画面追加時のレビュー記録（セルフ / Gemini CLI） |
| `docs/実装方針251030v1_ストレージ改修.md` | ストレージ改修の実装方針 |
| `docs/詳細設計たたき台_ストレージ改修.md` / `docs/詳細設計仮案(AI)1_ストレージ改修.md` | 同上の設計案 |
| `../ch3s-migration/GEMINI.md` | Scala.js × HonoX 構成の規約（step 3 で準拠する） |

### 1.6 開発コマンド

```bash
npm ci                 # 依存導入
npm run dev            # wrangler dev --config wrangler.jsonc → http://127.0.0.1:8787
npm test               # node --test tests/*.test.js
npm run deploy         # wrangler deploy --config wrangler.jsonc --minify
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

本リポジトリで進める作業の全体像。**上から順に実施する。**

### Step 1. 作業ブランチ整備
`develop` から作業用ブランチ **`develop_v6s`** を作成し、`feature/add_controll_panel`（管理画面 + 投稿モデレーション）を取り込む。以降の作業はこのブランチを起点とする。

### Step 2. キャラクタリゼーションテスト追加（仕様固定化）
Scala.js 移植の前提として、**現行 v5 の振る舞いをテストで固定する**。
- 目的は「正しさ」の検証ではなく **現行の実動作の写し取り**。
- 重点範囲: dat フォーマット（`<>` 区切り・本文前後の半角スペース・第5フィールド）、ID / 識別ID / トリップ / キャップ生成、Shift_JIS 変換、投稿パイプラインの検証順序とエラー文言、Range / If-Modified-Since、規制判定、Board/Thread DO の状態遷移。

### Step 3. JS → Scala.js 移植 + HonoX 導入
`ch3s-migration` を参照実装として、**バックエンドをリプレイス**する。
- **仕様は step 2 で固定したものから変えない。**
- フロントエンドは **`3chiko.html` のまま**（別リポジトリ、変更しない）。
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

#### 等価性の検証方式（重要）
step 2 のキャラクタリゼーションテストは `src/*.js` を直接 import する単体テストであり、
**それだけでは Scala.js 版を検証できない**（import 先が変わる）。そこで二段構えにする。

- **単体レベル**: 純粋関数（Shift_JIS 変換・各種ハッシュ・トリップ生成）は、JS 版で記録した
  入出力表を `tests/golden/` に固定値として書き出し、Scala 版の単体テストからも同じ表を読んで照合する。
  実装言語に依存しないデータとして持つのがポイント。
- **HTTP レベル**: **差分テスト（differential testing）** を採る。v5(JS) と v6(Scala.js) を
  別ポートの `wrangler dev` で同時に起動し、**同一のリクエスト列を両方に投げてレスポンスを突き合わせる**。
  - 比較対象: ステータス / ヘッダ / **ボディのバイト列**。
  - 時刻・スレッドキーなど本質的に変動する値は、比較前に正規化（マスク）する。
    マスクした箇所が仕様上重要な場合は、別途フォーマット検査で担保する。
  - 絶対値を固定する golden 方式ではなく差分方式にするのは、投稿系が `Date.now()` に依存していて
    絶対値を再現できないため。両者に同じ入力を与えて**相対的に一致すること**を示す方が強い保証になる。

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

### 3.3 既知不具合の扱い — 移植完了まで修正しない、記録のみ

**バグを見つけても直さないこと。`docs/既知不具合台帳.md` に記録するだけにする。**

> 理由: step 3 の Scala.js 移植は「v5 とバイト単位で同一の出力」を検証の軸にしている。
> 移植と同時にバグを直すと、出力差分が「移植の誤り」なのか「意図した修正」なのか切り分けられなくなる。

見つけたときの手順:
1. `docs/既知不具合台帳.md` の表に `KB-nn` を採番して追記する。
2. 現行の壊れた挙動をキャラクタリゼーションテストで固定し、そのアサーションに `// KB-nn: <一行説明>` を付ける。
3. 仕様書の記述と実動作が食い違う場合は、**コードの実動作を正として**仕様書側を訂正する。

修正に着手してよいのは step 3 完了後（Scala.js 版が v5 と同一出力であると確認できた後）。

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
- テストは `npm test` で通ること。統合テストは実 Wrangler をローカルストレージで起動して検証する（本番ストレージは使わない）。

### 3.6 セキュリティ
- `wrangler.jsonc` の `salt` や `ADMIN_PASSWORD` 等の秘匿値をログ・コミットメッセージ・外部 CLI への送信内容に含めない。
- 管理画面は Basic 認証 + Origin 検証 + no-store + CSP 前提。この防御を弱めない。
