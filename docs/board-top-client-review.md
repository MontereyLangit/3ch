# 板top クライアント機能 レビュー

2026-11-02。対象は `feature/board_top` の板top クライアント機能の追加（リロード、名前欄/メール欄記憶、
スレタイ/本文下書き復元、imgur 画像アップロード、スレ立て時コマンド補助）。

当初 `app/board-client.ts` / `app/posting-client.ts`（TS）で実装したが、利用者指示（2026-11-02）により
既存の板top クライアント処理も含めて `bbs-client` の Scala.js islands へ移した（後述「islands 化」）。

## 仕様（利用者決定）

| 項目 | 決定 |
| --- | --- |
| リロード | 一覧部分だけ更新する。islands 化後は `/<bbs>/subject.txt` を取り直し（Shift_JIS を `TextDecoder` で復号）、`BoardListJs` を再描画する。入力中のフォームや開いたパネルは維持 |
| 名前・E-mail 記憶 | localStorage `3ch:postName` / `3ch:postMail`（全板共通）。空欄にだけ復元し、復元したら「名前・E-mail」を開く |
| 下書き | localStorage `3ch:draft:<bbs>/<key または new>` に `{subject, MESSAGE}`。送信時に `sent` を付け、次の通常遷移では破棄、戻る（back_forward）では復元 |
| imgur | Client-ID は旧3chのものを `wrangler.jsonc` の `vars.IMGUR_CLIENT_ID` から SSR で渡す。未設定ならアップロード欄を出さない。板top の新規スレッド作成欄のみ |
| コマンド補助 | UI だけ用意。候補 `ThreadCommand.all` は空（サーバーがコマンドを解釈するようになったら追加） |

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| 通常 POST では投稿成否が分からず、下書きを「成功時だけ消す」（`docs/ui/板top_書き込み欄と投稿後更新の方針.md` 2章）ができない | 送信時に `sent` 印を付け、戻るボタン以外の遷移で破棄する方式にした。エラー後に戻るを使わず板top を開き直した場合は下書きが消える。fetch 投稿（同文書 2章）の実装時に成功判定へ置き換える |
| file input が bbs.cgi に送られないか | `name` 属性なしで描画し、`BoardIndexSuite` で固定。bbs.cgi は urlencoded のみ受け付けるため |
| 名前欄のトリップキー（`#...`）が localStorage に平文で残る | 専ブラ・旧 UI と同等の挙動として許容。共用端末への注意は今後の UI 文言で検討 |
| リロード後の一覧内フォーム | islands 化で解消（一覧は `BoardListJs` の React 描画になり、DOM の差し替えをしない） |
| `tests/admin.integration.test.js` の `/ダミー/` が 5499458（Coming Soon 化）以降失敗していた | 本作業と無関係の既存不具合。期待値を `Coming Soon…` に更新 |
| 公開エンドポイントの文字コード・dat 構造（AGENTS.md 3.4） | 変更なし。板top の HTML と `vars` の追加のみ |

## Codex CLI レビュー

2026-11-02、利用者の指示により実施（`codex exec --sandbox read-only`）。秘匿値を含む `wrangler.jsonc` は差分から除き、開かないよう指示した。

| 指摘 | 判断・対応 |
| --- | --- |
| [P2] imgur アップロード中に送信でき、画像 URL を含まない投稿になる（遷移でアップロードも中断されうる） | 妥当。アップロード中は submit を `preventDefault` して案内を表示。止めた送信で下書きに `sent` が付かないよう、imgur の submit リスナーを下書きより先に登録し、下書き側は `defaultPrevented` を見る。保留中の送信テストを追加 |

## islands 化（2026-11-02）

| island（`app/islands/*.tsx` は codegen 生成） | 内容 |
| --- | --- |
| `SiteHeaderJs` | ヘッダー。スクロール時の非表示、フォーカスで再表示、`--header-height` の更新、「検索」リンク |
| `BoardDetailsJs` | 板概要・板設定・新着レス一覧・新規スレッド作成の開閉ボタンとパネル（新規スレッド作成欄は入れ子 island ではなく通常コンポーネント） |
| `PostingFormJs` | 書き込みフォーム一式（名前/メール記憶、下書き、imgur、アップロード中の送信抑止、コマンド補助） |
| `BoardListJs` | `#board-list`。リロード、スレ一覧（subback）、フィード枠、既読 `+N` |

| 確認事項 | 判断・対応 |
| --- | --- |
| subject.txt と板top の一覧が同じ行になるか | `PublicRoutes` は Board DO の `getSubject()` を trim・空行除去して返し、`BoardRepository.getSubjects` と同じ元。Shift_JIS にない文字は `&#cp;` になり `SubjectText.decode`（common へ移動）が戻す。`&#128512;` でテスト |
| hydration の不一致 | localStorage・`performance`・`window` は `componentDidMount` 以降。勢いはサーバー描画時刻 `now` を props で渡し、スレ立て時刻は Intl を使わず JST で手書き整形 |
| island props の重複 | subject の原文を props に持つため、700 スレで最大 50KB 程度 HTML が増える。許容 |
| ブラウザが復元したフォーム値 | 制御コンポーネントが空で始まるため、hydration 前にブラウザが入れた値は消えうる。下書き復元で実害は小さいが未対応 |
| 板設定・新着レス一覧のパネル内容 | 右サイドバー（bbs 側）と同じ内容を island 側でも描画しており重複。共通化は今後 |
| `sbt bbs/test` で island の SSR | `scalajs:client.js` を bbsClient の fastLinkJS 出力へ、honox 内の `hono/jsx` を `react` へ解決する loader を追加（本番は Vite の `reactApiImportSource` が同じ置換をする） |
| リロード欄の UI | 中央寄せ・全幅のリンク風ボタンに `/static/reload.svg` のアイコンを付け、読み込み中は回転（`animate-spin`）。`tests/board-islands.test.js` でアイコンと回転クラスを確認 |
| TS 版の Codex 指摘（アップロード中の送信） | island でも同じく抑止し、`sent` を付けない。`tests/board-islands.test.js` で確認 |

## Codex CLI レビュー（islands 版、2026-11-02）

利用者の指示により bbs-client の修正を対象に実施（`codex exec --sandbox read-only`）。React / HonoX / scalajs-react の作法も観点に含めた。`wrangler.jsonc` は差分から除き、開かないよう指示した。利用者は #1 #2 #3 #5 の修正を承認し、#4 は見送り（計画は `docs/island-props型付け計画.md`）とした。

| 指摘 | 判断・対応 |
| --- | --- |
| [P2][正しさ] `BoardListIsland.markSeen` が localStorage だけ更新し state を更新しないため、中クリック等で別タブに開いても元の一覧の `+N` が消えない。他タブの既読も反映されない | 修正済み。`markSeen` が state の `seen` を更新し、反映後に localStorage へ保存する。`storage` イベントの listener で他タブの変更も `seen` へ反映（キー削除・全消去を含む）。`tests/board-islands.test.js` に、左/中クリックで即座にバッジが消える・右クリックは不変・`storage` イベントで減少/増加を反映・無関係なキーは無視、を追加 |
| [P3][作法] unmount 後も Future 完了で `modState(...).runNow()` が走る。`uploading` が React state の外（Backend の var） | 修正済み。`uploading` を State へ移し、アップロードごとに `AbortController` を持って unmount で中断、完了は unmount 後に無視。subject.txt リロードも `AbortController`（unmount・新しいリロード開始で中断）。テストで、保留中アップロード/リロードの unmount 中断（`signal.aborted`、完了後に `console.error` なし）と、2 件並行アップロードで両方終わるまで送信が止まることを確認 |
| [P3][作法] `modState` の更新関数内で localStorage を読み書きしている（更新関数は純粋であるべき） | 修正済み。localStorage の読込と復元の判断は更新関数の外で行い、保存・削除は `modState` の確定後 Callback で行う（既存の下書き・既読テストで確認） |
| [P3][作法] island props の契約がサーバーとクライアントで別々の文字列キーと `js.Dynamic` で定義され、型で検証されない | 別作業として見送り（利用者判断）。計画は `docs/island-props型付け計画.md` |
| [P3][テスト] 実際の4 island をサーバーラッパー＋HonoX `createClient` 経由で hydrate するテストがない | 修正済み。テスト用 export `renderBoardTopTest`（`IslandTestExports`）で板top の実 SSR HTML を得て、Vite ビルドした HonoX `createClient` で hydrate する `tests/board-top-hydration.test.js` を追加。island 境界 3 つ（新規スレッド作成欄は board-details 内）が hydrate され、DOM が再利用され、recoverable error・`console.error` なし、パネル開閉・下書き保存・リロードアイコンの動作を確認 |

総評（Codex）: ScalaComponent builder の採用、SSR と揃えた初期描画、通常 POST の維持、イベント解除、JSON 安全な props、入れ子 island の回避は適切。祖先への CSS 変数設定と検索欄フォーカスは限定的な DOM 連携として妥当。改善点は副作用の配置・非同期処理の寿命・境界の型付け。

## Gemini CLI レビュー

未実施（外部送信の許可待ち）。

## 検証

- islands 化後（Codex 指摘の修正後）: `sbt test` 全件成功（bbs 164、common JVM/JS 各 25、codegen 17。bbs-client の単体テストは無し）。`-no-indent` 付き `Test/compile` 成功。
- `npm run v6:build` 後の `npm test`: 37 件成功、skip 0（実リンクした client.js を JSDOM で動かす `tests/board-islands.test.js` 28 件、板top の実 SSR HTML を HonoX で hydrate する `tests/board-top-hydration.test.js` 1 件を含む）。`tsc --noEmit` 成功。
