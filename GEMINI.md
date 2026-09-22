# GEMINI.md — 3ch 掲示板システム (ch3s) 開発ガイド

AI アシスタント（Claude Code / Gemini CLI / Codex CLI）および開発者向けの、本リポジトリにおける
プロジェクト概要・移植計画・作業規約。**CLAUDE.md は本ファイルと同一内容**（どちらを読んでも同じ）。

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

---

## 2. 移植計画（ロードマップ）

本リポジトリで進める作業の全体像。**上から順に実施する。**

### Step 1. 作業ブランチ整備
`develop` から作業用ブランチ **`develop_v6s`** を作成し、`feature/add_controll_panel`（管理画面 + 投稿モデレーション）を取り込む。以降の作業はこのブランチを起点とする。

### Step 2. キャラクタリゼーションテスト追加（仕様固定化）
Scala.js 移植の前提として、**現行 v5 の振る舞いをテストで固定する**。
- 目的は「正しさ」の検証ではなく **現行の実動作の写し取り**。`docs/現行システム仕様書.md` 第11章に挙がっている既知の不具合も、この段階では**そのまま固定する**（直さない）。
- 重点範囲: dat フォーマット（`<>` 区切り・本文前後の半角スペース・第5フィールド）、ID / 識別ID / トリップ / キャップ生成、Shift_JIS 変換、投稿パイプラインの検証順序とエラー文言、Range / If-Modified-Since、規制判定、Board/Thread DO の状態遷移。

### Step 3. JS → Scala.js 移植 + HonoX 導入
`ch3s-migration` を参照実装として、**バックエンドをリプレイス**する。
- **仕様は step 2 で固定したものから変えない。**
- フロントエンドは **`3chiko.html` のまま**（別リポジトリ、変更しない）。
- **DB / ストレージ構成の変更なし**（DO / R2 / KV のスキーマそのまま）。
- 構成規約は `../ch3s-migration/GEMINI.md` に準拠（Scala 3 / Scala.js, HonoX, React 19, Vite, 単一 sbt プロジェクト, ルーティングは `RouteTable.scala` からの自動生成、Scala↔TS 境界型の制約）。

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

### 3.3 変更時の不変条件（壊してはいけないもの）
- **専ブラ互換**: dat の `<>` 5 フィールド構造、本文フィールド前後の半角スペース、エラー時も HTTP 200 + `<!-- 2ch_X:error -->` マーカー、`Range` / `304` の挙動。
- **Shift_JIS 出力**: 公開エンドポイントの文字コードを変えない。
- **ID / トリップ / キャップの計算結果**: 同一入力に対し同一の値が出ること（ハッシュ仕様の変更は既存ユーザーの ID 変化＝互換性破壊）。
- **ストレージスキーマ**: step 4 までは DO / R2 / KV のキーとフォーマットを変更しない。

### 3.4 コーディング規約
- 既存コードのスタイルに合わせる。不要なコメント・過剰な抽象化を入れない。
- TypeScript の `any`、Scala の未型付け構造型を避ける。コンパイル / Lint 警告は抑制せず解消する。
- テストは `npm test` で通ること。統合テストは実 Wrangler をローカルストレージで起動して検証する（本番ストレージは使わない）。

### 3.5 セキュリティ
- `wrangler.jsonc` の `salt` や `ADMIN_PASSWORD` 等の秘匿値をログ・コミットメッセージ・外部 CLI への送信内容に含めない。
- 管理画面は Basic 認証 + Origin 検証 + no-store + CSP 前提。この防御を弱めない。
