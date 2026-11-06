# 認証実装の引き継ぎ

作業ブランチは `ccr-9c002284-dbai2f`。2026-11-07（日本時間）の段Bの途中成果を区切りとして引き継ぐ。ビルドとテストを通した小さな単位ごとにcommit・pushし、完了範囲と残件をこの文書へ記録する。

## 前提と現在の範囲

最初に [AGENTS.md](../AGENTS.md) を読む。`AGENTS.md` が作業規約の原本。[認証設計](auth-phoebe-rate-limit-design.md) §3.8・§6.3・§10・§12、[画面モック](ui/auth.html)、[レビュー記録](auth-implementation-review.md) も参照する。

前回のレビュー修正は `d02da38` を目印にしている。DDD化と段Aの確認では `sbt test` 843件、`npm test` 47件とビルド・型チェックの成功を記録済み。この件数は段Bを含む今回の結果ではない。

段BではAccount・AccountIndex・AuthRate DO、CookieとSessionReader、端末情報の照合、クライアントIDの登録、Turnstileと同意確認、専ブラ用キー、投稿認証と追加ヘッダーを実装した。新UIには認証ページ、投稿中の認証モーダル、ヘッダーの表示切り替え、アカウントのキー操作を追加した。認証設定がない環境では従来の投稿・画面を維持する。

画面へ渡すセッションは `uid`・`lv` のみ。アカウント画面にキー平文・ハッシュを出さない。発行直後のPOST応答の平文だけを一度表示する。認証通知は表示更新用で、権限の判定はサーバーで行う。

## 残件と次の順序

段Bを完了扱いにしない。キャップのアカウントへの紐づけ・発行・失効を操作する画面、利用ログなど、設計書と実装を照合して残件を整理する。アカウント画面は現時点で公開ID・Lvと専ブラ用キーの操作が中心。設計書にある全操作を提供してはいない。

AS・地域・UA・日別識別子の観測集計（Account.seen）、AccountDirectoryへの反映、管理者用RPC・役割の管理も未実装。専ブラの表示名は製品名・版・既知のOSを抽出する限定的な実装で、段Cのv3解析表の移植とは区別する。接続元の表示は現在取得できるAS番号と地域・国を使い、AS名の取得は後続で揃える。iOSの既知プロファイル表はR2から読む口だけを用意しており、本番の表データを作成したわけではない。

次の実施順は **E-1 → C → D → E-2**。段Bの残件と境界の確認を先に行い、この区切りではE-1の機能を追加しない。

| 段 | 次に実装する内容 |
| --- | --- |
| E-1 | ID・パスワード登録、ログイン、ロック、Google認証の基本部分。現在の準備中UIを実際の処理へ接続する |
| C | PHOEBEの昇格・投稿数と経過時間、識別子の書式、Lv設定とコマンド、連投規制へのアカウントID連携を設計書と照合する |
| D | 権限・回避レベル・管理画面のアカウント認証と操作画面、スレ立て規制の設定を進める。管理画面の別計画も参照する |
| E-2 | メール認証、パスワード再設定、方式移行など。メール送信APIの提供元は未定のため、送信ポートと提供元の設定を分ける |

## 外部資料

端末ハッシュの原本は `MontereyLangit/v6-base` の `develop`。`common` の端末関連実装、`common/src/jsMain/kotlin/fc/pages/AuthPage.kt` の `collectFp`・`detectFonts`、`docs/feature/ninja_auth_system.md` を参照した。OS軸はUA、arch軸は `navigator.platform`。候補フォント、順序、計測文字列などは互換材料なので、独自の別方式へ置き換えない。

設計が参照するv3のUA解析表全体は未確認。段Cで元の資料を探してから移植する。Google OAuthのクライアント登録とメール送信APIの選定も後続作業。

## 設定と検証

[認証の検証設定](auth-integration.md) に従う。`AUTH_SECRET` がないと認証は有効にならない。登録には `AGREEMENT_VERSION`、`TURNSTILE_SITE_KEY`、`TURNSTILE_SECRET_KEY` も必要。秘密鍵は `.dev.vars` または環境のsecretで管理し、この文書やコミットへ書かない。

ローカルは `http://localhost:8787/` を使う。Secure CookieのHTTP開発例外はlocalhostだけで、127.0.0.1では発行しない。SQLite DOの `v2` はAccount・AccountIndex・AuthRateの3クラス。AccountDirectoryは後続で別の移行タグを追加し、適用済みタグを書き換えない。

通常の開発環境では次のコマンドを使う。今回の環境専用のラッパーは前提にしない。

```bash
npm ci
npm run v6:build
sbt test
npm test
npm run v6:typecheck
```

| 検証 | この区切りの結果 |
| --- | --- |
| Scala単体テスト | sbt test 1,001件成功（commonJVM 103、commonJS 108、codegen 27、bbsClient 2、bbs 761）。既存の投稿goldenは変更なし |
| workerd・SSR・hydration | npm test 62件成功、skipなし。npmのpretestも成功。実DOの競合・保存、Turnstile応答の差し替え、Cookie・再認証・キー失効・ログアウト、既存投稿、SSR・hydrationを検証 |
| ビルド | npm run v6:build成功。Workerの最終出力に5つのDOが公開されることも検証 |
| dev起動 | npm run devをローカル・ダミーsaltで起動。トップ200、bbs.cgiのHTTP 200とエラーマーカー、管理者パスワード未設定時の503、各応答のAccept-CHを確認 |
| 型チェック | npm run v6:typecheck成功 |
| コミット・push | この文書を含む検証済みの段Bの区切りとして作業ブランチへ反映する |

本番へのデプロイ、secretの登録、本番データの変更はこの区切りには含めない。
