# 開発環境について

## hx4s（Scala.js × HonoX の基盤）

`@route` / `@islands` の配線生成、island の境界と props の検証、Vite の設定は別リポジトリの hx4s が持つ（`docs/codegen.md`、`docs/islands-ssr.md`）。
hx4s はまだ Maven Central / npm に公開していないので、hx4s の checkout からローカルに公開したものを使う。

| ファイル | 内容 |
| --- | --- |
| `hx4s.lock` | 使う hx4s の commit（完全な SHA）と版（`<版>-local.g<commit 10 桁>`）を `key=value` で書く。`project/plugins.sbt` が版を読む |
| `scripts/setup-hx4s.mjs`（`npm run hx4s:setup`） | hx4s の checkout を確かめ、公開記録を照合して必要なら公開し直し、3ch の npm 依存を入れる |

lock ファイルと読み方は hx4s の推奨形（hx4s の `docs/hx4s/local-dependency.md` 3 章）に合わせている。以前は `hx4s.lock.json` と `0.1.0-local.N` の版を使っていたが、
版を手で上げる運用では commit と版の食い違いを機械的に見つけられない。hx4s の既定の版は commit から作られるので、セットアップスクリプトは
lock の版が commit の先頭 10 桁で終わることを確かめ、食い違う lock を公開の前に止める。

### 手順

```bash
git clone <hx4s のリポジトリ> ../hx4s          # 3ch の親をたどった兄弟の hx4s を探す。別の場所なら HX4S_DIR で指定する
git -C ../hx4s checkout <hx4s.lock の commit>
npm run hx4s:setup                              # = node scripts/setup-hx4s.mjs
npm run v6:build
```

`scripts/setup-hx4s.mjs` は次を順に行う。どれかが失敗したら止まる。

1. lock の版が commit と対応していること、hx4s の checkout の HEAD が lock の commit と一致し、作業ツリーに未コミットの変更・未追跡のファイルが無いことを確かめる
2. hx4s の `node scripts/publish-local.mjs --verify --version <版> --commit <commit>` で公開記録を照合する。記録・HEAD・`~/.ivy2/local` の全成果物・tgz の SHA-256 が
   一致すれば公開を省く。合わなければ `publish-local.mjs --version <版> --reuse-node-modules` で公開し直し、もう一度照合する。
   hx4s 側の `npm ci` は publish-local が行い、前回の `npm ci` から hx4s の `package-lock.json` が変わっていないときだけ省く（SHA-256 で比べる）。
   sbt の成果物（`sbt-hx4s`・`hx4s-core`・`hx4s-react`・`hx4s-codegen`）は `~/.ivy2/local/io.github.montereylangit/` に、
   `hx4s-vite` は hx4s の `target/local/hx4s-vite.tgz` に置かれる
3. 3ch で `npm ci` し、`hx4s-vite` の tgz を `npm install --no-save` で入れる

npm・publish-local はシェルを介さずに起動する（npm は実行中の Node で `npm-cli.js` を直接起動する）。Windows でもパスに空白や `&` があってよい。

`hx4s-vite` は `package.json` にも `package-lock.json` にも書かない。tgz の整合性ハッシュは公開のたびに変わるので、lockfile に載せると
同じ commit から作り直しただけで lockfile が変わる（hx4s の examples と同じ方式）。そのため **`npm ci` を単体で実行すると hx4s-vite が消える**。
依存を入れ直すときは `npm run hx4s:setup` を使う。公開済みなら照合が通るので、2 回目以降は公開を省いて 3 だけを行う。

hx4s を更新するときは、hx4s 側で commit してから `hx4s.lock` の `commit` と `version`（`<版>-local.g<新しい commit の先頭 10 桁>`）を書き換える。
照合が通らなくなるので公開し直される。その後 `npm run v6:build` → `sbt test` → `npm test`。

### 生成物の照合

`npm test` の pretest は、最後に sbt のコマンド `generatedAppCheck`（`build.sbt`）で 3ch の設定のまま route / island を `target/generated-app-check/` に生成する。
`tests/generated-app.test.js` はそれとコミット済みの `app/routes`・`app/islands` を比べ、生成マーカーのあるファイルが過不足なく同じ内容であること、
手書きの route 5 件（`_renderer.tsx`・`admin/index.ts`・`auth.js.ts`・`bbsmenu.html.ts`・`index.ts`）が生成対象にならないことを確かめる。
`app/` は書き換えない。`generatedAppCheck` は `set` でその sbt の実行の出力先を変えるので、同じ実行の中では最後に置く。

本番デプロイ（`npm run deploy`）も、ローカル依存の間は hx4s を公開した手元の環境からだけ行える。

### CI への組み込み案（未適用）

3ch には現在 `.github/workflows/` が無い。hx4s のリポジトリが GitHub に push され、CI から読めるようになったら、次の形で組み込む。

```yaml
- uses: actions/checkout@v4
- uses: actions/checkout@v4
  with:
    repository: <owner>/hx4s
    ref: ${{ steps.lock.outputs.commit }}   # hx4s.lock の commit 行から読んだ値
    path: hx4s
- uses: actions/setup-java@v4   # JDK 17 以上、sbt 1.13
- uses: actions/setup-node@v4   # Node.js 22.12 以上
- run: node scripts/setup-hx4s.mjs
  env:
    HX4S_DIR: ${{ github.workspace }}/hx4s
- run: npm run v6:build
- run: test -z "$(git status --porcelain --untracked-files=all)"   # 生成物がコミット済みと一致する
- run: sbt test
- run: npm test
```

- `~/.ivy2/local` と `node_modules` はキャッシュしない（毎回 hx4s の固定した commit から公開する。手元に残った成果物に頼って通ることを避ける）。
  coursier のキャッシュ（`~/.cache/coursier`）は Maven Central の依存だけなのでキャッシュしてよい。
- 公開を伴う解決方式（Maven Central / npm）は hx4s の Phase 5 で決める。そうなったら setup の手順は通常の依存解決に置き換える。

## IntelliJ IDEA
### Scala プラグインの注意点

Scala 3 の `object` は最初から final なので、`object` の中の `case object` に `final` は付けないのが正しい。
IntelliJ はこの書き方に「内側のケースオブジェクトを final にする」という警告を出す。
付けると今度は「オブジェクトの 'final' 修飾子は冗長です」が出て、どちらの書き方でも警告が残る。
前者は Scala 2 向けの検査なので、無効にしてください。

- 警告の箇所で Alt+Enter →「内側のケースオブジェクトを final にする」の右の ▸ →「検査の編集」/「無効にする」
- または Settings → Editor → Inspections → Scala で、該当の検査のチェックを外す
