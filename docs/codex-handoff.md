# Codex 引き継ぎプロンプト — 3ch v5 → Scala.js 移植（step 3 途中）

> **完了済みの履歴資料（2026-10-02）:** step 3 は完了し本番は v6 に切り替えた。本書の `src/` と「差分テスト基盤」は廃止済み（v5 はタグ `v5-final`）。現在の方針は AGENTS.md を参照。
>
> **履歴資料:** 2026-09-23の続行により、以下の進捗・Shift_JIS方針・KB-01/02/16の説明は更新されている。現在地は `docs/v6s-review.md` と `docs/移植方針_step3_ScalaJS.md` を参照。実コードに無い二重更新等を再導入しない。

> このファイルをそのまま Codex CLI に渡す想定。冒頭から末尾まで自己完結している。
> 実行例:
> ```bash
> codex exec --sandbox workspace-write "$(cat docs/codex-handoff.md)" < /dev/null
> ```
> **`< /dev/null` は必須。** 付けないと `codex exec` が stdin を待って無応答のまま固まる（実際に踏んだ）。

---

## 0. あなたの役割

`C:\project\3ch\ch3s`（ブランチ `develop_v6s`）で、2ch 互換掲示板「3ch」v5 の
バックエンドを **Scala 3 / Scala.js + HonoX** へ移植する作業を引き継ぐ。
前任（Claude）が step 1〜2 と step 3 の第0〜1層まで完了させ、第2層の途中で中断した。

**絶対要件は「v5 とバイト単位で同一の出力」。** 2ch 専用ブラウザ（専ブラ）が dat / subject.txt の
フォーマットに厳密に依存しており、既存利用者のトリップ・ID が変わると互換性が壊れる。

---

## 1. プロジェクトの前提

- `ch3s` = 3ch v5 = **現行稼働システム**。Cloudflare Workers + Durable Objects + R2 + KV、素の JavaScript（`src/` 配下、約2,500行）。
- 移植先は Scala 3 / Scala.js + HonoX。参照実装が `C:\project\3ch\ch3s-migration` にある（動く部分移植。構成・パターンはここから流用する）。
- フロントエンド（`3chiko.html`）は**別リポジトリ**。step 3 では触らない。
- **DB / ストレージのスキーマ変更なし**（DO / R2 / KV のキーとフォーマットはそのまま）。

### 必読（この順で）

| ファイル | 内容 |
| :--- | :--- |
| `AGENTS.md`（唯一の原本。Claude Code・Codex CLI・Gemini CLI とも、このファイルを読む） | プロジェクト概要・6段階のロードマップ・作業規約。**§3.3 が最重要ルール** |
| `docs/移植方針_step3_ScalaJS.md` | step 3 の方針。参照実装から引き継ぐもの／自前で作るもの、**等価性リスク R-01〜R-07** |
| `docs/既知不具合台帳.md` | 既知不具合 KB-01〜KB-26。**移植中に直してはいけないものの一覧** |
| `docs/現行システム仕様書.md` | v5 の全ロジック抽出（版 2.1.0）。§3 データ形式、§4 DO の契約、§5 投稿パイプライン |
| `docs/v6s-review.md` | Codex / Gemini のレビュー記録と**未解決事項** |
| `../ch3s-migration/GEMINI.md` | Scala.js × HonoX の構成規約 |

---

## 2. 絶対に破ってはいけないルール

### 2.1 バグは直さない。記録するだけ

**移植中にバグを見つけても直さないこと。** `docs/既知不具合台帳.md` に `KB-nn` を採番して追記するだけにする。

> 理由: 移植は「v5 とバイト単位で同一の出力」を検証の軸にしている。移植と同時にバグを直すと、
> 出力差分が「移植の誤り」なのか「意図した修正」なのか切り分けられなくなる。

**Scala 版が JS より「正しく」なっていたら、それは移植の失敗。** 特に以下は意図的に壊れたまま移すこと:

- **KB-01**: `addRes` が `bbs.cgi` の更新後に Board DO を**二重更新**し、`mail.includes("sage")` しか渡さないため強制 sage が打ち消される。整理したくなるが、しない。
- **KB-02**: 1000行判定が `>= 1000` なので実質 999 レスで死ぬ。かつ判定が `blockConcurrencyWhile` の**外**にある。両方そのまま。
- **KB-03**: `MESSAGE.replace("<br>", " <br> ")` が最初の1箇所しか置換しない。
- **KB-14**: `sha1Base64` が Shift_JIS バイト文字列を**再度 UTF-8 化**して SHA-1 を取る。直すと既存トリップが全部変わる。
- **KB-19**: `translateSaltChar` の演算子優先順位バグでほぼ全入力が `undefined`。
- **KB-16**: 旧 `.cgi` 移行が dat のみ復元し settings / log を捨てる。

### 2.2 例外: Shift_JIS だけは移植と同時に修正する — **ただし現在ブロック中**

> **停止中（2026-09-23）。** 下記の方針で着手したが、受け入れ基準に抵触する差異が見つかったため
> 差し替えを停止している。**再開前に §3.3 を読み、利用者の判断を仰ぐこと。**

### 2.2.1 当初の方針

**2026-09-23、利用者の指示による方針変更。** Shift_JIS 変換は自前テーブルを忠実に移植せず、
**`encoding-japanese` へ移行する**。これにより KB-04 / KB-17 / KB-18 が移植と同時に解消される。

受け入れ基準（`docs/移植方針_step3_ScalaJS.md` R-01）:
- v5 が**正しく変換できていた文字**は、移行後も**バイト単位で一致しなければならない**。崩れたら失敗。
- v5 が変換できなかった文字（0xF0–0xFC 行・絵文字）と不正バイトの decode のみ、意図的な差異として許容。
- **差異は全件列挙して確定リストにする。** 「たぶんここだけ」は不可。

### 2.3 その他

- **`src/` は変更禁止。** v5 は本番稼働中。既存の `tests/*.test.js` も変更しない（`tests/golden/` への追加は可）。（履歴: 現在は削除済み）
- `wrangler.jsonc` は本番 worker の設定。**触らない**。v6 の設定は `wrangler.v6.jsonc`（分離済み）。
- `wrangler.jsonc` に本番の `salt` が入っている。**読まない・ログに出さない・外部に送らない。**
- コンパイル警告・Lint 警告は抑制せず解消する。
- **ドキュメントにリテラルの制御文字（NUL 等）を書かない。** 実際に台帳へ NUL を書いてしまい、git がバイナリ扱いして diff が効かなくなった。`\x00` のようなエスケープ表記で書く。

---

## 3. 現在地

### 3.1 コミット済み（ブランチ `develop_v6s`、`50bd838` 以降）

```
845fdd5 feat: 移植 第1層 — Shift_JIS/MD5/crypt(3)/SHA を Scala へ移植
47d9f7d test: Codex レビュー指摘に基づきキャラクタリゼーションテストを強化
64c6648 build: Scala.js x HonoX のビルド基盤を追加（移植の土台、ロジックなし）
e116758 test: 移植前の仕様固定化としてキャラクタリゼーションテストを追加
5776442 docs: v6s 移植計画と現行システム仕様書を追加
```

状態: `npm test` **134件緑** / `sbt test` **28件緑**。

### 3.2 未コミットの作業ツリー — **全て未コミット、品質未検証**

> **注意: 作業ツリーには下記(A)(B)以外にも、レビュー・検証を経ていない実装が多数ある。**
> `bbs/src/main/scala/ch3s/bbs/durable/{Thread,Board}.scala`、
> `bbs/src/main/scala/ch3s/bbs/handler/{Posting,Admin,Moderation,PublicRoutes,Responses,...}.scala` など、
> 第2層以降に相当するファイルが存在する。**これらは私（前任）がレビューしておらず、
> 等価性の検証も外部レビューも受けていない。** 内容を信用せず、必ず自分で確認すること。
> `git status` で全体を把握してから着手すること。
> なお `npm test`（v5 側、134件）は緑、`src/` は無変更であることは確認済み。`sbt test` は未確認。

以下は特に状態を把握している2件:

```
 M bbs/src/main/scala/ch3s/bbs/external/cloudflare/Bindings.scala
 M docs/既知不具合台帳.md
 M docs/移植方針_step3_ScalaJS.md
 M package.json / package-lock.json
?? bbs/src/main/scala/ch3s/bbs/util/SjisEncoding.scala
?? docs/v6s-review.md
?? tests/golden/analyze-sjis-diff.mjs
?? tests/golden/generate-sjis-diff.mjs
?? tests/golden/sjis-diff-exceptions.json
```

**この2つは完了していない。まず現状を自分の目で確認してから続きをやること。**

#### (A) Shift_JIS の `encoding-japanese` 移行 — 途中

- 済: `encoding-japanese@2.2.0` を `package.json` に追加。`bbs/.../util/SjisEncoding.scala` を作成（参照実装 `ch3s-migration` の同名ファイルが手本）。差異列挙スクリプト2本と `tests/golden/sjis-diff-exceptions.json`（84KB）を生成。
- **未**: 旧実装 `common/src/main/scala/ch3s/common/sjis/Sjis.scala` と `SjisTableData.scala`（74KB の埋め込みテーブル）が**残ったまま**。差し替えが完了していない。
- **未**: `docs/sjis差異一覧.md`（人間可読の差異まとめ）が未作成。
- **未**: 既存の Scala テストが v5 の golden テーブルと厳密一致を要求したままなので、差異集合を例外として扱う形に組み替える必要がある。**例外は明示的なデータとして持ち、未登録の新しい差異は必ずテストが落ちるようにすること。** 緩くしてはいけない。
- 注意: `encoding-japanese` は npm パッケージなので、変換器は `common`（cross-built・純粋）に置けない。`bbs`（JS 専用）へ移す。

**最重要**: 差異列挙の結果に、**KB-04/17/18 で説明できない差異**（v5 が正しく変換できていた文字なのに `encoding-japanese` では違うバイトになる）が混じっていないか必ず確認すること。混じっていたら既存の投稿を壊す重大な発見なので、作業を止めて報告する。

#### (B) Durable Objects の移植 — ほぼ手つかず

- 済: `Bindings.scala` に `ThreadDoStub` / `BoardDoStub` / `ThreadInspectResult` / KV の `get(keys)`・`put(entries)` などのファサードを追加（+68行）。
- **未**: `Thread` / `Board` の DO 実装そのもの（`bbs/src/main/scala/ch3s/bbs/durable/` に作る）。
- 移植元: `src/durable_objects/thread.js`（289行）、`src/durable_objects/board.js`（108行）。
- 振る舞いの仕様は `tests/thread.test.js` と `docs/現行システム仕様書.md` §4。
- `wrangler.v6.jsonc` のバインディングと、`vite.config.ts` の `entryContentAfterHooks` の再エクスポート一覧（現在プレースホルダ DO しか書いていない）を更新すること。

### 3.3 **ブロッカー: `encoding-japanese` が既存データを読めない（要判断）**

Shift_JIS 差し替えの受け入れ基準は「**v5 が正しく変換できていた文字はバイト単位で一致すること**」だった。
実測の結果、**この基準に抵触する差異が確認されたため作業を停止している。**

#### 確認された事実（独立に再現済み）

CP932 には**同じ文字が2箇所にエンコードされている「重複符号」**がある。
v5 の表（Node の `TextDecoder('sjis')` 由来）は **NEC 特殊文字の 0x87xx 側**を採用しているのに対し、
`encoding-japanese` は **JIS X 0208 標準の 0x81xx 側**を採用する。

| 文字 | v5 の encode | encoding-japanese の encode |
| :-- | :-- | :-- |
| `≒` | `87 90` | `81 E0` |
| `≡` | `87 91` | `81 DF` |
| `∫` | `87 92` | `81 E7` |
| `√` | `87 95` | `81 E3` |
| `⊥` | `87 96` | `81 DB` |
| `∠` | `87 97` | `81 DA` |
| `∵` | `87 9A` | `81 E6` |
| `∩` | `87 9B` | `81 BF` |
| `∪` | `87 9C` | `81 BE` |

そして決定的なのが **decode 方向**:

```
v5.decode([0x87, 0x90]) === "≒"
encoding-japanese.decode([0x87, 0x90]) === "?"
```

※ `①`(`87 40`) や `纊`(`ED 40`) は両者一致する。問題は**重複符号の文字に限られる**。

#### なぜ止めたか

**既存の dat には `87 xx` のバイトが実際に入っている。**
`encoding-japanese` はそれを `?` にしか復号できないため、**過去ログの該当文字が全て `?` に化ける**。
これは「未対応文字が直る」話（KB-04/17/18）ではなく、**v5 が正しく扱えていたデータを壊す退行**であり、
受け入れ基準の明示的な停止条件に該当する。

#### 選択肢（利用者の判断が必要）

| 案 | 内容 | 評価 |
| :-- | :-- | :-- |
| **A** | `encoding-japanese` を使いつつ、**NEC 重複符号の互換マッピングを双方向で被せる**。decode は `87 xx` を受理し、encode は v5 と同じ `87 xx` を出す | **推奨。** 既存データを壊さず、新規投稿のバイト列も v5 と一致する。KB-04/17/18 の改善も得られる |
| **B** | `encoding-japanese` をそのまま使い、encode のみ `87 xx` に寄せる | decode 側が直らないため過去ログが化ける。不可 |
| **C** | 当初方針（自前テーブルの忠実移植）に戻す | 既存データは安全だが KB-04/17/18 が残り、R-06（変換表がランタイム依存）も残る |

**A を推奨する。** ただし互換マッピングの対象を「上表の9文字」で決め打ちにせず、
`tests/golden/sjis-table.json`（v5 の実表 9,027 件）と `encoding-japanese` を全件突き合わせて
**重複符号の全件を機械的に洗い出すこと。** 手で数えた9件が全部だという保証はない。

---

## 4. 残タスク（優先順）

0. **【最優先・要判断】Shift_JIS のブロッカー解消** — §3.3 の選択肢 A/B/C について**利用者の判断を仰ぐ**。
   判断が出るまで Shift_JIS 差し替えを再開しないこと。
1. **未コミット実装の棚卸し** — 作業ツリーにある未検証の実装（DO・各ハンドラ）を確認し、
   使えるもの／捨てるものを判断する。**そのまま信用してコミットしないこと。**
2. **(A) Shift_JIS 移行の完遂** — 方針確定後。旧実装の削除、差異一覧の文書化、テストの組み替え。
3. **(B) 第2層: Durable Objects の移植** — `Thread` / `Board`。
3. **golden の網羅不足を解消**（Gemini 指摘、`docs/v6s-review.md` 参照）:
   - `sha1base64.json` に**高位バイト（0x80〜0xFF）を含む文字列**が無く、**KB-14 の二重エンコードを実質的に縛れていない**。本番で実際に起きる入力なので追加必須。
   - `sjis-encode` 系に孤立サロゲートの境界値が無い。
   - `UnixCrypt.cryptBytes`（配列版）のテストが未実装。
4. **第3層: `moderation`**（`src/moderation.js`、41行）。
5. **第4層: 投稿パイプライン**（`src/bbs.cgi.js`、971行）— 最大の難所。
6. **第5層: ルーティング + HonoX 配線**（`src/main.js`、598行）。
7. **第6層: 管理画面**（`src/admin.js`、97行）。
8. **差分テスト基盤** — v5 と v6 を別ポートの `wrangler dev` で同時起動し、同一リクエスト列に対する
   ステータス・ヘッダ・**ボディのバイト列**を突き合わせる。時刻とスレッドキーのみ正規化。
   投稿系は `Date.now()` 依存で絶対値を再現できないため、golden 固定ではなく差分方式を採る。

**step 4 以降（フロントの Next.js 化、DB 変更、データ移行）には進まないこと。**

---

## 5. 実際に踏んだ落とし穴

- **`@hono/vite-build/cloudflare-workers` は既定エクスポートしか再エクスポートしない。** Durable Object の named export が黙って落ち、Wrangler が "Durable Object Class Not Found" で落ちる。`vite.config.ts` の `entryContentAfterHooks` に再エクスポート文を追加して回避している（既定配列に**追記**すること。置き換えるとプリセットの既定挙動を失う）。
- **`nodejs_compat` フラグが必須。** Hono 本体が `node:async_hooks` を使うため、React を使わなくても必要。
- 参照実装の `vite.config.ts` にある `sequentialBuildStart` は**定義されているだけで呼ばれていない死にコード**。sbt 二重起動を防いでいる実体は単一 sbt プロジェクト構成（moduleID `"server"` / `"client"` で分ける）の方。
- **`bbs` のテストは `cloudflare:workers` のスタブローダが要る。** `Test / jsEnv` の `NodeJSEnv` に `--experimental-loader` を渡している（`bbs/test-stubs/`）。これが無いとテストが起動しない。
- **JS の `String.replace(文字列, 文字列)` は最初の1件のみ置換。Scala は全件置換。** 取り違えると KB-03 等が「直って」しまう。
- **JS の `undefined` の扱い。** `crypt` でソルトが1文字未満のとき `undefined` が混入し、「NUL だが `== 0` ではない」ためフォールバック分岐が発火しない（KB-26）。Scala では負の番兵値で再現している。素直に `0` を入れると出力が変わる。

---

## 6. 検証コマンド

```bash
npm test                 # v5 の JS テスト（134件）。常に緑を維持すること
sbt -batch test          # Scala のテスト（28件）
sbt codegen/run          # RouteTable.scala から app/routes/ を生成
npx vite build --mode client
npx vite build --mode workers
npx wrangler dev --config wrangler.v6.jsonc   # http://127.0.0.1:8801
```

**コンパイルが通るだけでは不十分。** Durable Object はバインドに失敗してもコンパイル自体は通るので、
実際に `wrangler dev` を起動して DO が生成され RPC が往復することまで確認すること。

---

## 7. 完了条件

- `npm test`（v5 側）が緑のまま。`src/` と `tests/*.test.js` は無変更。
- `sbt test` が緑。コンパイル警告なし。
- 既知不具合 KB-01〜KB-26 が**再現されている**こと（Shift_JIS の KB-04/17/18 を除く）。直っていたら移植の失敗として扱う。
- 新たに見つけた不具合は `docs/既知不具合台帳.md` に記録のみ（修正しない）。
- 作業後は `docs/v6s-review.md` の書式でレビュー記録を残す。

## 8. 報告してほしいこと

- **Scala 版が JS と一致しているか確信が持てなかった箇所**を全て挙げること。黙って近似しない。
- 忠実に再現するために意図的に壊したまま移した箇所。
- 新たに見つけた v5 の不具合。
