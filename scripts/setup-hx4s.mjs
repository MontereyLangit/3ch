// hx4s を hx4s.lock の commit と版でローカル公開し、3ch の npm 依存を入れる（docs/development.md の「hx4s」）。
//   1. hx4s の checkout が lock の commit で、作業ツリーがクリーンであることを確かめる
//   2. hx4s の publish-local.mjs --verify で公開記録を照合し、通らなければ公開し直してもう一度照合する
//   3. 3ch で npm ci（package-lock.json どおり）の後、hx4s-vite の tgz を --no-save で入れる（tgz のハッシュを lockfile に載せない）
// hx4s の場所は環境変数 HX4S_DIR。無ければ、このリポジトリから親をたどって最初に見つかる兄弟の hx4s ディレクトリを使う。
// 外部コマンドはシェルを介さずに起動する（パスに空白や & があっても壊れない）。npm は実行中の Node で npm-cli.js を直接起動する。
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
if (process.argv.length > 2) throw new Error(`unknown argument ${process.argv[2]}`);

const lock = Object.fromEntries(
  readFileSync(path.join(root, "hx4s.lock"), "utf8")
    .split(/\r?\n/)
    .map((line) => /^(\w+)=(.+)$/.exec(line.trim()))
    .filter(Boolean)
    .map((match) => [match[1], match[2]]),
);
if (!/^[0-9a-f]{40}$/.test(lock.commit ?? "")) throw new Error(`hx4s.lock: commit must be the full 40-character SHA, got ${lock.commit}`);
// 版は hx4s の既定（<版>-local.g<commit 10 桁>）にそろえ、commit と食い違う lock を公開の前に止める
if (!new RegExp(`^\\d+\\.\\d+\\.\\d+-local\\.g${lock.commit.slice(0, 10)}$`).test(lock.version ?? "")) {
  throw new Error(`hx4s.lock: version must be <X.Y.Z>-local.g${lock.commit.slice(0, 10)} for commit ${lock.commit}, got ${lock.version}`);
}

function isHx4s(dir) {
  return existsSync(path.join(dir, "scripts", "publish-local.mjs")) && existsSync(path.join(dir, "sbt-plugin"));
}

function findHx4s() {
  if (process.env.HX4S_DIR) {
    const dir = path.resolve(process.env.HX4S_DIR);
    if (!isHx4s(dir)) throw new Error(`HX4S_DIR=${dir} is not an hx4s checkout`);
    return dir;
  }
  for (let dir = root; path.dirname(dir) !== dir; dir = path.dirname(dir)) {
    const candidate = path.join(path.dirname(dir), "hx4s");
    if (isHx4s(candidate)) return candidate;
  }
  throw new Error("hx4s checkout not found. Clone it next to this repository or set HX4S_DIR.");
}

const npmCli = [
  process.env.npm_execpath,
  path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js"),
  path.join(path.dirname(process.execPath), "..", "lib", "node_modules", "npm", "bin", "npm-cli.js"),
].find((file) => file && path.basename(file) === "npm-cli.js" && existsSync(file));
if (!npmCli) throw new Error("npm-cli.js was not found next to the running Node");

function npm(...args) {
  console.log(`> npm ${args.join(" ")}`);
  const result = spawnSync(process.execPath, [npmCli, ...args], { cwd: root, stdio: "inherit" });
  if (result.status !== 0) throw new Error(`npm ${args.join(" ")} failed with exit code ${result.status}`);
}

const hx4s = findHx4s();
const git = (...args) => execFileSync("git", ["-C", hx4s, ...args], { encoding: "utf8" }).trim();
const publishLocal = (...args) => {
  console.log(`> (hx4s) node scripts/publish-local.mjs ${args.join(" ")}`);
  return spawnSync(process.execPath, [path.join(hx4s, "scripts", "publish-local.mjs"), ...args], { cwd: hx4s, stdio: "inherit" }).status === 0;
};

const head = git("rev-parse", "HEAD");
if (head !== lock.commit) {
  throw new Error(`${hx4s} is at ${head}, but hx4s.lock pins ${lock.commit}. Run: git -C "${hx4s}" checkout ${lock.commit}`);
}
const dirty = git("status", "--porcelain", "--untracked-files=all");
if (dirty !== "") throw new Error(`${hx4s} has uncommitted changes or untracked files:\n${dirty}`);

const expected = ["--version", lock.version, "--commit", lock.commit];
if (!publishLocal("--verify", ...expected)) {
  console.log("hx4s is not published for hx4s.lock; publishing it again");
  // hx4s 側の npm ci は publish-local が行う。前回の npm ci と package-lock.json が同じときだけ省く
  if (!publishLocal("--version", lock.version, "--reuse-node-modules")) throw new Error("hx4s publish-local failed");
  if (!publishLocal("--verify", ...expected)) throw new Error("hx4s was published but does not match hx4s.lock");
}

npm("ci", "--no-audit", "--no-fund");
npm("install", "--no-save", "--no-audit", "--no-fund", path.relative(root, path.join(hx4s, "target", "local", "hx4s-vite.tgz")).replaceAll("\\", "/"));
console.log(`hx4s ${lock.version} (${lock.commit.slice(0, 10)}) is ready`);
