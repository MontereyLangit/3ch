# 認証実装の引き継ぎ

作業ブランチは `ccr-9c002284-dbai2f`。2026-11-07（日本時間）の段Bの成果と、段E-1（ID・パスワードとGoogleログイン）の完了を区切りとして引き継ぐ。ビルドとテストを通した小さな単位ごとにcommit・pushし、完了範囲と残件をこの文書へ記録する。

## 前提と現在の範囲

最初に [AGENTS.md](../AGENTS.md) を読む。`AGENTS.md` が作業規約の原本。[認証設計](auth-phoebe-rate-limit-design.md) §3.8・§6.3・§10・§12、[画面モック](ui/auth.html)、[レビュー記録](auth-implementation-review.md) も参照する。

前回のレビュー修正は `d02da38` を目印にしている。DDD化と段Aの確認では `sbt test` 843件、`npm test` 47件とビルド・型チェックの成功を記録済み。この件数は段Bを含む今回の結果ではない。

段B・E-1途中のレビュー指摘と対応は [レビュー記録](auth-implementation-review.md) の「段 B / E-1 途中のレビュー指摘と対応」にまとめた。

段BではAccount・AccountIndex・AuthRate DO、CookieとSessionReader、端末情報の照合、クライアントIDの登録、Turnstileと同意確認、専ブラ用キー、投稿認証と追加ヘッダーを実装した。新UIには認証ページ、投稿中の認証モーダル、ヘッダーの表示切り替え、アカウントのキー操作を追加した。認証は `AUTH_SECRET`（32文字以上）・`AGREEMENT_VERSION`・Turnstileの2つの鍵が揃ったときだけ有効になり、揃わない環境では従来の投稿・画面を維持する（`/auth/` も404）。

画面へ渡すセッションは `uid`・`lv` のみ。アカウント画面にキー平文・ハッシュを出さない。発行直後のPOST応答の平文だけを一度表示する。認証通知は表示更新用で、権限の判定はサーバーで行う。

## 残件と次の順序

段Bを完了扱いにしない。キャップのアカウントへの紐づけ・発行・失効を操作する画面、利用ログ、退会など、設計書と実装を照合して残件を整理する。アカウント画面は公開ID・登録方法と有効期限・Lv・この端末の粒度・専ブラ用キーの操作・ログアウトまで。Lvの進み具合（段C）は出していない。

AS・地域・UA・日別識別子の観測集計（Account.seen）、AccountDirectoryへの反映、管理者用RPC・役割の管理も未実装。専ブラの表示名は製品名・版・既知のOSを抽出する限定的な実装で、段Cのv3解析表の移植とは区別する。接続元の表示は現在取得できるAS番号と地域・国を使い、AS名の取得は後続で揃える。iOSの既知プロファイル表はR2から読む口だけを用意しており、本番の表データを作成したわけではない。

次の実施順は **C → D → E-2**。E-1は完了した（下記「E-1の実装範囲」）。段Bの残件と境界も引き続き確認する。

| 段 | 次に実装する内容 |
| --- | --- |
| E-1 | 完了。残りはGoogle OAuthのクライアント登録と本番での接続確認（外部設定）だけ |
| C | PHOEBEの昇格・投稿数と経過時間、識別子の書式、Lv設定とコマンド、連投規制へのアカウントID連携を設計書と照合する |
| D | 権限・回避レベル・管理画面のアカウント認証と操作画面、スレ立て規制の設定を進める。管理画面の別計画も参照する |
| E-2 | メール認証（登録画面のメール欄もここで出す）、パスワード変更・再設定のHTTP（照合と `setPassword` のRPCはE-1で用意済み）、クライアントIDからの方式移行、退会、エッジ防御など。メール送信APIの提供元は未定のため、送信ポートと提供元の設定を分ける |

## 外部資料

端末ハッシュの原本は `MontereyLangit/v6-base` の `develop`。`common` の端末関連実装、`common/src/jsMain/kotlin/fc/pages/AuthPage.kt` の `collectFp`・`detectFonts`、`docs/feature/ninja_auth_system.md` を参照した。OS軸はUA、arch軸は `navigator.platform`。候補フォント、順序、計測文字列などは互換材料なので、独自の別方式へ置き換えない。

設計が参照するv3のUA解析表全体は未確認。段Cで元の資料を探してから移植する。メール送信APIの選定も後続作業。Google OAuthのクライアント登録はまだで、本番のGoogleとの接続は確認していない（[認証の検証設定](auth-integration.md)）。

## E-1の実装範囲

E-1はID・パスワードの登録・ログイン・ロックと、Googleでのログイン・登録まで。設計書§3.9・§3.10の基本部分にあたる。レビューと設計からの差分は [レビュー記録](auth-implementation-review.md) の「段 E-1」にまとめた。

純粋モデルはcommonのLoginId、Password、LoginThrottle、PasswordCredential、GoogleIdToken（クレームの検証）、OAuthState（認可Cookieの書式）と、AccountStateの照合・利用開始・パスワード変更。PBKDF2はPBKDF2-HMAC-SHA256・100,000回・16バイトのソルトで、プラットフォームの上限は未測定。

Account DOのRPCは `create`（資格情報付き）、`activate`、`verifyPassword`、`setPassword`、`describeGoogle`。`verifyPassword` はDOのロック内でPBKDF2を計算し、接続元ごとの失敗計数とロックを同じ直列化の中で保存する。ロック中も計算を省かない。存在しないIDは固定の `Account("dummy")` に同じRPCを送り、固定の資格情報で計算して失敗にする（dummyのDOには何も保存しない）。応答の遅延（5回目から1秒ずつ、上限8秒）はDOのロック外、HTTP層で待つ。

登録は uid と login（Googleなら google）の索引を予約し、作成→login（google）索引の確定→uid索引の確定→利用開始（activate）の順に進める。uidの確定が拒否されたら両方の予約を外し、応答だけが失われたらuidの所有者を確かめてから続ける。利用開始の前に止まったアカウントは、正しいパスワード（Googleなら検証済みのIDトークン）での次のログインで確定を再開する。既存の10分の予約期限は変えていない。[実装メモ](auth-password-implementation-notes.md) §4の候補（Accountに確定要求を記録する方式）は採らなかった。

HTTPは `POST /auth/register`・`/auth/login`・`/auth/google/start` と `GET /auth/google/callback/`。登録とログインはTurnstileと端末申告の突合を必須にし、AuthRateの register（1日5件）・login（1時間30回）を接続元ごとに数え、計数できなければ拒否する。ログインの失敗（未登録・不一致・ロック中）は同じ401と文言。使えないユーザーID（予約語・R2の `reserved-ids.txt`・既存の板名・使用済み）は同じ409と文言。

GoogleはOpenID Connectの認可コード＋PKCE。state・nonce・verifierを署名付きの `ch3s_oauth` Cookie（`Path=/auth/google`、10分）に保存し、callbackで一度だけ使う。IDトークンはGoogleのJWKS（RS256）で署名を検証してからクレームを確かめ、subのHMACで `AccountIndex("google")` を引く。未登録なら入力したユーザーIDでLv10のアカウントを作る。`GOOGLE_CLIENT_ID` と `GOOGLE_CLIENT_SECRET` が揃わない環境では画面は「準備中」で、開始とcallbackは404。

認証画面は「新しく登録する」「ログイン」のタブで、登録方法にかんたん登録・ID・パスワード・Googleを並べる。メールアドレス欄はメール認証を実装するE-2まで出さない。認証モーダルは同じ画面を埋め込むので、ID・パスワードもそのまま使える。Googleは埋め込み時に別のウィンドウで開き、BroadcastChannelと既存のポーリングで親へ伝える。

E-1に含めなかったもの: クライアントIDからの方式移行（E-2）、パスワード変更・再設定・メール認証のHTTP（E-2）、Googleの開始へのエッジ防御（E-2）。ログイン中に新規登録すると別のアカウントになり、Cookieは新しいアカウントに切り替わる（移行はE-2）。

## 設定と検証

[認証の検証設定](auth-integration.md) に従う。`AUTH_SECRET` がないと認証は有効にならない。登録には `AGREEMENT_VERSION`、`TURNSTILE_SITE_KEY`、`TURNSTILE_SECRET_KEY` も必要。Googleログインには `GOOGLE_CLIENT_ID` と `GOOGLE_CLIENT_SECRET` を足す。秘密鍵は `.dev.vars` または環境のsecretで管理し、この文書やコミットへ書かない。

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
| Scala単体テスト | sbt test 1,181件成功（commonJVM 162、commonJS 165、codegen 27、bbsClient 2、bbs 825）。既存の投稿スナップショット・PostingSuite・goldenは変更なし。`-no-indent` のTest/compileも成功 |
| workerd・SSR・hydration | npm test 70件成功、skipなし。実workerdで登録→ログアウト→ログイン→投稿、未登録・不一致の同じ応答、十回の失敗による接続元のロックと応答の遅延、別の接続元からのログイン、認証画面の方式の表示を検証。board-islandsでタブを替えてもTurnstileを作り直さないこと、ログインと登録の送信内容、Googleの開始フォームを検証 |
| ビルド | npm run v6:build成功 |
| 型チェック | npm run v6:typecheck成功 |
| コミット | E-1はRPC・登録とログイン・HTTP・画面・Google・文書の順に小さく分けてコミットした（pushはオーケストレーターが行う） |

本番へのデプロイ、secretの登録、本番データの変更はこの区切りには含めない。
