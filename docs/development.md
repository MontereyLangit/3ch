# 開発環境について

## hx4s（Scala.js × HonoX の基盤）

`@route` / `@islands` の配線生成、island の境界と props の検証、Vite の設定は別リポジトリの hx4s が持つ（`docs/codegen.md`、`docs/islands-ssr.md`）。
hx4s はまだ Maven Central / npm に公開していないので、hx4s の checkout からローカルに公開したものを使う。

| ファイル | 内容 |
| --- | --- |
| `hx4s.lock.json` | 使う hx4s の commit（完全な SHA）と版（`0.1.0-local.N`）。`project/plugins.sbt` が版を読む |
| `scripts/setup-hx4s.mjs`（`npm run hx4s:setup`） | hx4s の checkout を確かめてローカル公開し、3ch の npm 依存を入れる |

### 手順

```bash
git clone <hx4s のリポジトリ> ../hx4s          # 3ch の親をたどった兄弟の hx4s を探す。別の場所なら HX4S_DIR で指定する
git -C ../hx4s checkout <hx4s.lock.json の commit>
npm run hx4s:setup                              # = node scripts/setup-hx4s.mjs
npm run v6:build
```

`scripts/setup-hx4s.mjs` は次を順に行う。どれかが失敗したら止まる。

1. hx4s の checkout の HEAD が `hx4s.lock.json` の commit と一致し、作業ツリーに未コミットの変更・未追跡のファイルが無いことを確かめる
2. hx4s に `node_modules` が無ければ `npm ci` し、`node scripts/publish-local.mjs --version <版>` を実行する。
   sbt の成果物（`sbt-hx4s`・`hx4s-core`・`hx4s-react`・`hx4s-codegen`）は `~/.ivy2/local/io.github.montereylangit/` に、
   `hx4s-vite` は hx4s の `target/local/hx4s-vite.tgz` に置かれる
3. 3ch で `npm ci` し、`hx4s-vite` の tgz を `npm install --no-save` で入れる

`hx4s-vite` は `package.json` にも `package-lock.json` にも書かない。tgz の整合性ハッシュは公開のたびに変わるので、lockfile に載せると
同じ commit から作り直しただけで lockfile が変わる（hx4s の examples と同じ方式）。そのため **`npm ci` を単体で実行すると hx4s-vite が消える**。
依存を入れ直すときは `npm run hx4s:setup` を使う。同じ版を公開済みなら `node scripts/setup-hx4s.mjs --skip-publish` で 2 を飛ばせる。

hx4s を更新するときは、hx4s 側で commit してから `hx4s.lock.json` の commit を書き換え、版の N を 1 つ上げる（同じ版の上書きは、
依存解決のキャッシュが古い成果物を返す原因になるので避ける）。その後 `npm run hx4s:setup` → `npm run v6:build` → `sbt test` → `npm test`。

本番デプロイ（`npm run deploy`）も、ローカル依存の間は hx4s を公開した手元の環境からだけ行える。

### CI への組み込み案（未適用）

3ch には現在 `.github/workflows/` が無い。hx4s のリポジトリが GitHub に push され、CI から読めるようになったら、次の形で組み込む。

```yaml
- uses: actions/checkout@v4
- uses: actions/checkout@v4
  with:
    repository: <owner>/hx4s
    ref: ${{ steps.lock.outputs.commit }}   # hx4s.lock.json の commit を jq で読んだ値
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
