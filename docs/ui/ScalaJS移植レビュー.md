# 板トップ UI の Scala.js 移植レビュー

2026-10-30。対象モック: `board.html` / `thread-list.html`。

## セルフレビュー

| 確認事項・指摘                    | 判断・対応                                                                                                           |
|----------------------------|-----------------------------------------------------------------------------------------------------------------|
| 共通部品の配置                    | `components/layout/` に Header / RightSidebar / Footer を分離。板固有データは props から渡す。                                   |
| 公開 Component の宣言           | 型注釈に関する警告を避け、既存の書式に合わせて全7コンポーネントを `final val Component` に復元。                                                    |
| 本文の切り替え                    | `BoardIndexProps.type` が `feed` / `subback` のとき ThreadFeed / Subback を表示。URL の `?type=` で切り替え、検索語・並び順を維持する。     |
| フィード取得 API が未実装            | `DummyThreadFeed` にデータを隔離。画面にダミー表示と明記し、架空スレへの返信は disabled fieldset で送信不可にする。新着レス欄も同じダミーを表示。                     |
| スレ一覧のデータ                   | BoardRepository の subject を使用。検索とレス数順・勢い順に対応。「新着順」は subject の並び順。subject には最終更新日時がないため `—` を表示する。               |
| 既存 subject パーサーの不整合（KB-37） | CSV を想定した既存パーサーが実データを読めない問題を台帳に記録し、独立コミット `daadcc2` で修正。JVM / JS 両方の読み書き・不正行テストで確認。                             |
| 全板共通スレ                     | 900 / 924 で始まるキーを折りたたみ欄へ分離し、閲覧先の板を 924 とする。勢いは計算せず `—`。                                                         |
| 保存済みタイトルの文字参照              | 一度だけ文字へ戻し、React のテキストとして描画。HTML を実行しない。数値文字参照の範囲外は原文を保持。                                                        |
| モックの崩れた form               | 全フィールドを form 内へ配置。既存 bbs.cgi へ UTF-8 の通常 POST を行い、`submit=書き込む` を維持する。画像アップロードや投稿後の部分更新は今回の対象外。                 |
| 上部ボタンと右サイドバー               | ボタンで本文上部のパネルを個別開閉。右サイドバーは常時表示。PC は右側 25%、768px 未満では本文末尾へ配置。JS 無効時は noscript CSS で上部パネルを表示し、スレ立て欄を利用可能にする。       |
| ヘッダー追従・新着数                 | クライアントでスクロール方向に応じてヘッダーを隠す。スレ閲覧時のレス数を板/キーごとに localStorage へ保存し、次回の一覧で増分を表示。保存できなくてもリンクは機能する。                     |
| フッターの version / 更新日        | sbt の project version と JST のビルド日を BuildInfo に生成。更新日はビルド日の意味とする。リンク・著者名・指定スタイルは依頼内容に合わせる。                       |
| 本番 CSS                     | Vite が生成する `/static/assets/client.css` を HTML シェルから明示的に読み込む。                                                    |
| Tailwind への統一              | 独自の board.css を削除し、Scala コンポーネントのユーティリティクラスへ移行。例外は依頼で指定された著者リンクの inline style と、JS 無効時の表示を成立させる noscript style。 |
| 専ブラ互換                      | 公開 dat / subject.txt / Shift_JIS 応答・ハッシュ・ストレージスキーマには変更を加えない。                                                    |

## Claude Code レビュー

利用者の「claude codeに送信する」という指示に従い、今回は AGENTS.md 3.2 の Codex CLI → Gemini CLI に代えて Claude Code
に依頼。明示的な送信許可を得て、秘匿設定を除いたモック・変更コード・関連テストを送信した。ツール使用と MCP
は無効化し、静的レビューのみ実施。モデル指定なし、実行モデルは `claude-sonnet-5-5`。

| 指摘                                      | 判断・対応                                                                                                                                                        |
|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 共通 CSS の Tailwind preflight が投稿結果ページへ影響 | 不採用。投稿結果・確認・エラーは PostingPages の専用 HTML で、RendererShell.render を通さず CSS link も出力しない。clientScript の存在だけでは共通シェル利用の根拠にならない。通常シェル内の preflight は Tailwind 利用方針に従う。 |
| 未検証の bbs がパスやクエリへ直接挿入される                | 採用。新規 UI の URL 生成時に板名をエンコードする。既存のハイフン・アンダースコアを含む板名は許容し、板名の新たな制限は加えない。                                                                                        |
| head.txt の二重埋め込み                        | モックと要求に合わせて上部パネルと常時表示サイドバーを維持。id・script を含む HTML は重複するという制約を記録し、今回のレビューだけでは表示仕様を変えない。                                                                        |
| 中クリックで閲覧履歴が更新されない                       | 採用。左クリックに加えて中クリックを記録する。記録値が一覧取得時点のレス数である制約は残る。                                                                                                               |
| 共通スレの勢い計算が未来のキーを時刻として扱う                 | 採用。共通スレは勢い計算を行わず、勢い順でも元の順序を保つ。                                                                                                                               |
| 隠れたヘッダーのフォーカス・検索リンク                     | 採用。ヘッダー内へフォーカスしたら再表示し、検索リンクは入力欄へフォーカスする。                                                                                                                     |
| 過去ログ導線と共通スレの参照先                         | 確認済み。PublicRoutes が /板/kako/ と read.cgi を既存 3chiko に渡す。共通スレは既存の 924 板を参照する。                                                                                  |
| 日本語 POST の検証不足                          | PostingSuite に日本語のタイトル・本文を使う UTF-8 投稿と dat 照合が既にある。                                                                                                          |
| CSS/JS のキャッシュ・共通スレの新着集約・取得最適化           | 今回は保留。既存配信方式と UI データ取得の後続作業として扱う。                                                                                                                            |

その後、利用者の「CSSはTailWindにする」に従い、独自 board.css を削除して Tailwind ユーティリティへ移した。

## 検証

- `sbt test`: 全225件成功（bbs 159、common JVM 24、common JS 24、codegen 17、bbsClient 1）。
- 新設の `BoardIndexSuite` で feed / subback の切り替え、実スレとダミーの分離、検索・レス数順、文字参照の安全な描画、共通レイアウト・フッター、投稿フィールドを検証。
- `npm run v6:build`: 成功。クライアント CSS と Workers バンドルを生成。
- `npm run v6:typecheck`: 成功。
- `npm test`: 全8件成功、skip なし。実 workerd で実スレ投稿後の subback 描画、feed のダミー表示、指定フッター、CSS の HTTP
  配信を確認。既存の管理・投稿・Islands SSR/hydration 検証も成功。
- `git diff --check`: 成功。
- ブラウザでの見た目の確認は未実施。

### Claude Code 指摘対応・Tailwind 移行後

- `sbt "bbs/testOnly ch3s.bbs.components.BoardIndexSuite ch3s.bbs.handler.BoardHandlerSuite"`: 全12件成功。板名の URL
  符号化と共通スレの勢い順を追加検証。
- 検索フォーカス・ヘッダーフォーカス・左/中/右クリックを jsdom で検証する3件を追加。
- `npm run v6:build` / `npm run v6:typecheck`: 成功。生成 CSS にレスポンシブ境界、header-hidden variant、CSS 変数を使う高さ計算と
  scroll-margin-top が含まれることを確認。
- `npm test`: 全11件成功、skip なし。追加の操作検証と実 workerd の UI・投稿・管理・CSS 配信、既存 Islands 検証を含む。
- `git diff --check`: 成功。ブラウザでの外観確認は未実施。

### Component 宣言の修正後

- 全7コンポーネントの `Component` を既存の書式どおり `final val` に復元。
- `sbt bbs/compile`: 成功。途中の増分コンパイル用ファイル移動エラーは sbt が再コンパイルして復旧し、最終終了コードは 0。
