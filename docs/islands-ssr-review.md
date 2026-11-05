# Scala Islands SSR レビュー

2026-10-13。利用者の指示により、SSR 側から生成 TSX を import せず `HonoXIsland` facade を使用する方式を実装。

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| `rawElement` 単体では hydration 情報が付かない | `Island` が `HonoXIsland` の React 要素を作り、`VdomElement` を返す。 |
| SSR の識別子とクライアント TSX の対応 | `@islands` から両方生成し、生成 Scala は raw な client export を import する。 |
| HonoX の Hook を直接呼んでしまう懸念 | native 関数を直接実行せず `React.createElement` に渡す。Vite の React 向け変換を明示する。 |
| 通常の入れ子と React 要素の props | HonoX の Context / template 処理に任せ、実 Scala リンク成果物から SSR・hydration・クリックを検証する。 |
| 新しい本番 UI がまだない | テスト専用 export を `bbs/src/test` に置く。本番ルート・ダミー Island は追加しない。 |
| ブラウザへのサーバー処理混入 | サーバーとクライアントの独立リンクを維持する。 |
| テスト内の React モード不一致 | Node で import 済みの React と Vite build 後に読む React DOM が一致するよう、fixture 実行中は development に固定して復元する。 |

## 検証結果

- `npm run v6:build`: 成功（client / Workers）。
- `npm run v6:typecheck` と `git diff --check`: 成功。
- `npm test`: 8件成功。既存 workerd 統合に加え、通常・入れ子・要素 props の SSR / hydration / クリックを確認。
- `codegen/test`: 17件成功。識別子の対応、空の生成物、重複 export、手書き Scala ファイル保護を含む。
- 一時的な7個の `@islands` 宣言から非空 API を生成して `bbs/compile`: 成功。`toString` / `equals` / `js` / `ch3s` / `IslandComponent0` / `match` を含む export 名を確認。宣言は元のバイト列に復元し、0 Islands に再生成済み。
- `sbt test`: bbs は145件中144件成功。既存の `HonoRenderSuite` の doctype 期待値不一致（KB-33）のみ失敗。common JVM / JS、client、codegen は成功。

DOM 検証には jsdom を開発依存として追加した。実ブラウザのレイアウト検証は含まない。

## 外部レビュー

Codex CLI → Gemini CLI は未実施。AGENTS.md §3.2 の「外部 CLI にコードを送信する前に、利用者の許可を取ること」に基づき、外部送信は許可待ち。
