# RendererShell 移植レビュー

2026-10-05。`../ch3s-migration` の `_renderer.tsx`、`RendererShell`、`Main.renderShell` を移植。

## セルフレビュー

| 指摘・確認事項 | 判断・対応 |
| --- | --- |
| 移植元の TypeScript は `any` と型エラー抑制を使用 | 新しい export の型宣言と renderer の `Props.title` 拡張で置換。 |
| 移植元の Scala はインデント構文と長い背景コメントを使用 | 波括弧と簡潔な Scaladoc に調整。HTML 構成は維持。 |
| React の依存が移植先にない | 移植元と同じ React / React DOM 19.2.8、react-renderer 1.0.1、scalajs-react core 4.0.0 と React 型定義を追加。 |
| 既存の公開エンドポイントとの互換性 | 既存ハンドラは Response を直接返すため renderer を使用しない。公開 HTML・Shift_JIS・ストレージの実装は変更なし。 |
| 全体テストの板トップ文言の不一致 | 利用者より意図的な文言変更と確認。KB-29 に記録し、古い期待値を板タイトルと管理画面リンクの検証へ更新。 |

## 検証

- `npm run v6:typecheck`: 成功。
- `npm run v6:build`: 成功。
- `npm test`: 2 件成功（実 workerd の管理・投稿統合検証、クライアントバンドル検査）。
- `sbt test`: 成功。common JVM / JS は各 21 件、bbs-client は 1 件、bbs は 134 件成功（KB-29 の古い期待値を更新して再実行）。
- 追加した `RendererShellSuite`: 2 件成功。React DOM による HTML 化、子要素、タイトルのエスケープと省略、開発・本番用 script URL を確認。
- リンク済み `server.js` の `renderShell` を Node から import し、React 要素としての妥当性と HTML 出力を確認。

## Claude Code レビュー

利用者の指示により Codex CLI → Gemini CLI の代わりに `claude` で実施。
秘匿値を除いた対象コード・差分・移植元コード・検証結果を渡し、ツールを無効化して静的レビューを依頼した。
Claude 自身によるファイル探索やテスト実行は行っていない。

重大度 Critical / High / Medium の指摘なし。Low 1 件と検証範囲についての注記あり。

対応後に `npm run v6:typecheck` と `git diff --check` を再実行し、成功。変更は型宣言とコメントのみで、実行コードへの追加変更はない。

| 指摘 | 判断・対応 |
| --- | --- |
| Low: `scalajs:server.js` の型宣言が `renderShell` のみで、既存 DO の import エラーが `@ts-expect-error` に隠れる | 採用。再 export 用の DO クラス宣言を追加し、`app/server.ts` の抑制と「.d.ts 無し」という説明を削除。抑制自体は従来から存在したが、新しい型宣言に合わせて整備した。 |
| workerd 統合テストでは既存ルートが Response を直接返すため renderer 自体を実行しない | 検証範囲の制約として採用。現時点では Scala/Node の React DOM 描画と Worker ビルド・既存経路を検証。最初の `c.render()` 利用ページ追加時に workerd 上の描画検証を追加する。 |
