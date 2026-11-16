# codegen の framework 分離・型付き island props のレビュー

2026-11-16。

## 対象と手段

対象は `f438cc9..f9fe432` の 8 コミット（codegen の `io._3ch.codegen.framework` 分離、`@islands[P]` の型付き props、
`JsShape` の検証、Island / HonoContext の汎用化、`CodegenConfig`・`CodegenMain`、`vite/scalajs-honox.ts`）。
framework 部分は後で独立 OSS の hx4s へ移すので、3ch 固有の知識を入れないことも確かめた。

レビューはセルフレビューと Codex CLI 0.163.0 で行った。Codex にはファイルの変更・ビルド・テストの実行と、
`.dev.vars`・`wrangler.jsonc` の参照をさせていない。各指摘はコードで確かめてから直した。

## Codex の指摘

| 指摘 | 判断・対応 |
| --- | --- |
| X1（高）symlink 経由で出力ルートの外に書ける。`OutputPath.resolve` は字句的な検査だけで、既存ファイルの検査と書き込みはリンクを辿る | 事実。出力ルート・途中のディレクトリ・出力先・古い生成物を探す出力ディレクトリの中に、シンボリックリンクかジャンクションがあれば辿らずに `CodegenError` にした。壊れたリンクも `exists(NOFOLLOW_LINKS)` で拾う。判定は `BasicFileAttributes`（`NOFOLLOW_LINKS`）の `isSymbolicLink \|\| isOther`。Windows で `mklink /J` のジャンクションを作り、Java が `isSymbolicLink=false`・`isDirectory=true`・`isOther=true` を返すことを確かめた。`Files.walk` はジャンクションを辿るので、古い生成物の探索は自前の走査に替えた。親・出力先・壊れたリンク・出力ルート・出力ディレクトリ内のリンクの負のテストと、ジャンクションのテスト（Windows のみ）を足した。この環境はシンボリックリンクの作成権限が無く、そのテストは `assume` で飛ぶ（ジャンクションのテストは通る） |
| X2（高）空の `generatedMarker` で手書きファイルの保護が無効になる | 事実（`""` はどの文字列の接頭辞にもなる）。空・空白だけ・`//` だけ・改行を含むマーカーを `Codegen.validate` と `OutputWriter.write` の両方で拒否する。`CodegenMain` も引数の解釈の最後に `Codegen.validate` を呼ぶ。バナーの 1 行目がマーカーで始まらない設定は既に拒否していたので、テストを足した。空のマーカーで生成しても手書きの route が残る回帰テストを足した |
| X3（中）ファイルとディレクトリの衝突を事前に検出せず、古い生成物を消してから失敗する。I/O 例外が `Either` にならない | 事実。出力どうしの `a.ts` と `a.ts/b.ts`、親が既存の通常ファイル、出力先が既存のディレクトリを計画の段階で拒否する。書き込みは「大文字小文字だけ違う古い生成物の削除 → 書き込み → 残りの古い生成物の削除」の順にした。前者を先に消すのは、大文字小文字を区別しないファイルシステムで同じ実体を上書きしたあとに消してしまわないため。I/O 例外は `CodegenError` にして返す。読み取り専用のファイルで書き込みを失敗させ、古い生成物が残ることと、直して再実行すると収束する（2 回目は `wrote 0`）ことをテストした |
| X4（中）同じメンバに複数の `@islands` を付けると同名・同シグネチャの SSR メソッドができる | 事実。1 つのメンバの `@islands` は 1 つまでにした。export 名は既にメンバをまたいで一意なので、island ごとの export 名も一意になる。island entry 全体の export 名の重複検査も足そうとしたが、この 2 つの規則のもとでは到達しないので入れていない |
| X5（中）型付き props の実行時検証がアカウント・認証系に無い | 事実。props を持つ 11 個すべての island に `JsComponent` を置き、`JsShape` で検証してから描画する形に揃えた（`Main.scala` は `JsComponent` を export する）。失敗時は板top の 3 個と同じく `console.error` に出して何も描画しない。サーバーが必ず送るキー（`AuthFormProps.login` など、キーの `issuedAt`・`recentPosts`・`warnings`、`AccountMenuProps.activeCount`・`nowSeconds`、`PostingFormProps.authEnabled`・`requiredLevel`・`returnPath`、ヘッダーの `authEnabled`・`returnPath` など）は必須に戻した。サーバーが組み立てる anonymous class のメンバの並びは変えていないので、JSON の形とキーの並びは変わらない。テストの props をサーバーと同じ完全な形にし、欠落・型違い・null のテストを Scala（`IslandPropsSuite`）と jsdom（`tests/board-islands.test.js`）に足した。依頼文の「SSR の markup を残す」は既存の 3 個の挙動とは違う。検証は SSR でも同じ props で動くので、不正な props では SSR もクライアントも空になる（`docs/island-props型付け計画.md` 7 章に記録） |
| X6（低）`JsKind.Num` が NaN・無限大を通し、`Int` の props に小数を許す | 事実。`Num` は有限の数だけを通す。`JsKind.Int` を足し、整数であることと Int の範囲を確かめる。`PostingFormProps.requiredLevel`・`AccountPostItemProps.resNum`・`AccountMenuProps.activeCount` に使う。`SessionProps.lv` は `Double` で受けているので `Num` のまま。NaN・無限大・小数・範囲外のテストを足した |

## セルフレビュー

| 指摘 | 判断・対応 |
| --- | --- |
| Y1 `@route` の `HEAD` が黙って無視される。HonoX が route の glob から外すファイル名も通る | `node_modules/honox/dist/server/server.js` の `METHODS` は GET, POST, PUT, DELETE, OPTIONS, PATCH だけで、HEAD は登録されない。`HttpMethod` から HEAD を外し、理由付きの誤りにした。`with-defaults.js` の route の glob は `_*`・`-*`・`$*`・`*.test.*`・`*.spec.*`・`-*/**` を除く。Vite の `import.meta.glob` は `exhaustive` でなければ `dot: false` で `node_modules` も除く（`vite/dist/node/chunks/node.js`）ので、ドットで始まる名前と `node_modules` も拒否する。HonoX が別の glob で拾う先頭の `.well-known/` は許す。island にもドットで始まるディレクトリと `node_modules` の規則を足した |
| Y2 `CodegenConfig` に既定値が無く、3ch の `GenerateAppFiles` が HonoX の既定まで書いている | `CodegenConfig.default(projectRoot, serverSources, clientSources, scalaPackage, islandBridge, annotationPackage)` を足した。`app/routes`・`app/islands`・`/app/islands/`・`server` / `client`・`scalajs:`・`IslandComponents`・既定のバナーとマーカーは framework 側の既定にした。`GenerateAppFiles` はソース・パッケージ名・ブリッジ・アノテーションのパッケージを渡し、バナーだけ上書きする。`CodegenMain` も省いたキーをこの既定値にする |
| Y3 3ch の文書が実装と食い違う | `docs/handler-rendering.md` の `RouteTable.routes` / `RouteTable.islands` 登録・`LegacyDispatch` の記述を、`@route` / `@islands` と `app/server.ts` の `app.all("*")` に合わせて短く直した。`docs/islands-ssr.md` の HonoX の版を `package-lock.json` の 0.1.62 に直した。`AGENTS.md` 1.3 の手書き route を、`git ls-files app/routes` と生成マーカーで確かめた `index.ts`・`admin/index.ts`・`auth.js.ts`・`bbsmenu.html.ts`・`_renderer.tsx` に直した |
| Y4 前回の `fix(codegen)` コミット以降、`npm run v6:build` / `npm test` を最終 HEAD で実行していない | 今回の修正の後にすべて実行した（下の「検証」） |

## Codex が問題なしとした点

通常のサーバー生成 props では、`BoardDetailsProps` の入れ子化と `js.UndefOr` の省略に SSR と hydration の不一致は見つからなかった。
server のコードへの client 依存の混入、`sourceGenerators` の循環、AGENTS.md 3.2 の不変条件（専ブラ互換・Shift_JIS 出力・保存形式）の変更も無い。
framework からの 3ch の型への直接の依存は解消されていて、残る React・HonoX・scalajs-react への依存は基盤の目的に沿う。

## 検証

最終 HEAD で次を実行した。

- `sbt test`: 成功。commonJVM 218 件、codegen 56 件（うちシンボリックリンクの 2 件は作成権限が無く skip）、commonJS 232 件、bbsClient 2 件、bbs 950 件
- `npm run v6:build`: 成功。`route / island files: wrote 0, unchanged 17, deleted 0`、`island components: wrote 0, unchanged 1`。ビルド後の `git status` に生成物の差分なし
- `sbt codegen/run` を 2 回続けて実行し、2 回とも `wrote 0, unchanged 17, deleted 0`
- `npm run v6:typecheck`: 成功
- `npm test`: 80 件すべて成功
- `sbt 'set ThisBuild / scalacOptions += "-no-indent"' Test/compile`: 成功

## 残課題

- リンクの確認から書き込みまでの間にパスを差し替えられる競合は防いでいない（codegen は開発者の手元で動く前提）
- シンボリックリンクのテストは作成権限のある環境（Linux・macOS、または Windows の開発者モード）でしか動かない
- `SessionProps.lv` は `Double` のまま。`Int` にすると `JsKind.Int` で検証できるが、サーバーの組み立てと `AuthClient` の読み取りも変わるので今回は見送った
