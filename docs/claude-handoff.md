# 認証実装の引き継ぎ

作業ブランチは `ccr-9c002284-dbai2f`。2026-11-07（日本時間）の段BとE-1の途中成果を区切りとして引き継ぐ。ビルドとテストを通した小さな単位ごとにcommit・pushし、完了範囲と残件をこの文書へ記録する。

## 前提と現在の範囲

最初に [AGENTS.md](../AGENTS.md) を読む。`AGENTS.md` が作業規約の原本。[認証設計](auth-phoebe-rate-limit-design.md) §3.8・§6.3・§10・§12、[画面モック](ui/auth.html)、[レビュー記録](auth-implementation-review.md) も参照する。

前回のレビュー修正は `d02da38` を目印にしている。DDD化と段Aの確認では `sbt test` 843件、`npm test` 47件とビルド・型チェックの成功を記録済み。この件数は段Bを含む今回の結果ではない。

段B・E-1途中のレビュー指摘と対応は [レビュー記録](auth-implementation-review.md) の「段 B / E-1 途中のレビュー指摘と対応」にまとめた。

段BではAccount・AccountIndex・AuthRate DO、CookieとSessionReader、端末情報の照合、クライアントIDの登録、Turnstileと同意確認、専ブラ用キー、投稿認証と追加ヘッダーを実装した。新UIには認証ページ、投稿中の認証モーダル、ヘッダーの表示切り替え、アカウントのキー操作を追加した。認証は `AUTH_SECRET`（32文字以上）・`AGREEMENT_VERSION`・Turnstileの2つの鍵が揃ったときだけ有効になり、揃わない環境では従来の投稿・画面を維持する（`/auth/` も404）。

画面へ渡すセッションは `uid`・`lv` のみ。アカウント画面にキー平文・ハッシュを出さない。発行直後のPOST応答の平文だけを一度表示する。認証通知は表示更新用で、権限の判定はサーバーで行う。

## 残件と次の順序

段Bを完了扱いにしない。キャップのアカウントへの紐づけ・発行・失効を操作する画面、利用ログ、退会など、設計書と実装を照合して残件を整理する。アカウント画面は公開ID・登録方法と有効期限・Lv・この端末の粒度・専ブラ用キーの操作・ログアウトまで。Lvの進み具合（段C）は出していない。

AS・地域・UA・日別識別子の観測集計（Account.seen）、AccountDirectoryへの反映、管理者用RPC・役割の管理も未実装。専ブラの表示名は製品名・版・既知のOSを抽出する限定的な実装で、段Cのv3解析表の移植とは区別する。接続元の表示は現在取得できるAS番号と地域・国を使い、AS名の取得は後続で揃える。iOSの既知プロファイル表はR2から読む口だけを用意しており、本番の表データを作成したわけではない。

次の実施順は **E-1 → C → D → E-2**。段Bの基盤は `38615ac` でpush済み。後続で端末情報のUnicode空白の互換性を修正し、E-1の純粋モデルに着手した。段Bの残件と境界も引き続き確認する。

| 段 | 次に実装する内容 |
| --- | --- |
| E-1 | 入力規則・ログイン失敗計数・PBKDF2・資格情報codec・登録準備状態まで実装。パスワードの作成・照合RPC、HTTP登録・ログイン、Google認証と準備中UIの接続は未実装 |
| C | PHOEBEの昇格・投稿数と経過時間、識別子の書式、Lv設定とコマンド、連投規制へのアカウントID連携を設計書と照合する |
| D | 権限・回避レベル・管理画面のアカウント認証と操作画面、スレ立て規制の設定を進める。管理画面の別計画も参照する |
| E-2 | メール認証、パスワード再設定、方式移行など。メール送信APIの提供元は未定のため、送信ポートと提供元の設定を分ける |

## 外部資料

端末ハッシュの原本は `MontereyLangit/v6-base` の `develop`。`common` の端末関連実装、`common/src/jsMain/kotlin/fc/pages/AuthPage.kt` の `collectFp`・`detectFonts`、`docs/feature/ninja_auth_system.md` を参照した。OS軸はUA、arch軸は `navigator.platform`。候補フォント、順序、計測文字列などは互換材料なので、独自の別方式へ置き換えない。

設計が参照するv3のUA解析表全体は未確認。段Cで元の資料を探してから移植する。Google OAuthのクライアント登録とメール送信APIの選定も後続作業。

## E-1の実装範囲

LoginId、Password、LoginThrottle、PasswordCredentialをcommonに追加した。NFC後のUTF-8長、孤立サロゲート、予約語と板名、5回目からの遅延、10回目の接続元ロック、期限と別IPへの影響をJVM・Scala.jsで検証する。ID・パスワード方式で実際に登録・ログインできる段階ではない。

全体の失敗窓は1時間とし、成功は該当する接続元の失敗だけを消す。登録時のパスワードでは、ASCIIの大文字小文字を区別せずIDの包含を拒否する。[実装メモ](auth-password-implementation-notes.md) の登録回復方式は未採用の候補であり、既存Bの10分の予約期限を変更していない。

入力規則は `8f73077` でpush済み。後続のPasswordHasherはPBKDF2-HMAC-SHA256・100,000回・16バイト乱数ソルトで資格情報を作り、保存済みの方式と反復数で照合する。Password.parseは照合用のNFC・Unicode・長さだけを確認し、登録用のID包含規則を適用しない。暗号APIの欠落・同期例外・Promise拒否では利用不可を返す。

ScalaのプロバイダはNodeの実WebCryptoと独立した固定値で検証した。実workerdの追加テストはネイティブAPIの100,000回・100,001回の計算をNodeと照合するもので、ScalaプロバイダをDOから呼ぶ統合テストは次の区切りで追加する。今回の設定値は100,000回のままで、プラットフォーム上限は未測定。

PBKDF2プロバイダは `70087bd` でpush済み。後続では、凍結などのprofile.statusから登録準備状態を分離し、activationがpendingのアカウントを認証に使わないようにした。readyのPasswordアカウントには正しい資格情報とUIDに一致するログインIDが必要。古いClient保存値にactivationが無い場合だけreadyとして読み、PasswordとGoogleの欠損・不明な準備状態は拒否する。

資格情報は厳密なcodecで読み書きする。ソルト16バイトとハッシュ32バイトは小文字の16進数で保存し、必須項目・反復数・時刻・失敗計数を検証する。本人向けinspectと認証DTOには資格情報やactivationを含めない。登録を有効にするRPCと、実際のパスワード照合RPCはまだ追加していない。

この保存基盤は `a418e22` でpush済み。次のDO接続に向け、独立したテスト専用SQLite DOで同期KVとSQLの同時確定・同時rollbackを確認した。async getのキャッシュ整合と保存形式の相互読出しも検証している。これは基盤APIの検証であり、Accountの照合処理を実装済みという意味ではない。

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
| Scala単体テスト | sbt test 1,135件成功（commonJVM 151、commonJS 154、codegen 27、bbsClient 2、bbs 801）。既存の投稿スナップショット・PostingSuite・goldenは変更なし。`-no-indent` のTest/compileも成功 |
| workerd・SSR・hydration | npm test 67件成功、skipなし。npmのpretestも成功。実DOの競合・保存、Turnstile応答の差し替え、Cookie・再認証・キー失効と索引の削除、HTTPのログアウト（無効なセッションでもCookieを消す）、未ログインのアカウント設定の302、設定不足の404、既存投稿、SSR・hydration、ネイティブPBKDF2、SQLと同期KVの原子性を検証。画面のログアウトはboard-islandsで送信の形（フォーム形式の本文）と表示の切り替えを検証 |
| ビルド | npm run v6:build成功。Workerの最終出力に5つのDOが公開されることも検証 |
| dev起動 | npm run devをローカル・ダミーsaltで起動。トップ200、bbs.cgiのHTTP 200とエラーマーカー、管理者パスワード未設定時の503、各応答のAccept-CHを確認 |
| 型チェック | npm run v6:typecheck成功 |
| コミット・push | 段B基盤は38615acで反映済み。レビュー指摘の修正は6d745cfから小さく分けてコミットした |

本番へのデプロイ、secretの登録、本番データの変更はこの区切りには含めない。
