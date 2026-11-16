// hx4s を hx4s.lock.json の commit と版でローカル公開し、3ch の npm 依存を入れる（docs/development.md の「hx4s」）。
//   1. hx4s の checkout が記録した commit で、作業ツリーがクリーンであることを確かめる
//   2. hx4s の scripts/publish-local.mjs --version <版> で sbt の成果物を ~/.ivy2/local に、hx4s-vite を tgz にする
//   3. 3ch で npm ci（package-lock.json どおり）の後、hx4s-vite の tgz を --no-save で入れる（tgz のハッシュを lockfile に載せない）
// hx4s の場所は環境変数 HX4S_DIR。無ければ、このリポジトリから親をたどって最初に見つかる兄弟の hx4s ディレクトリを使う。
// 使い方: node scripts/setup-hx4s.mjs [--skip-publish]（--skip-publish は同じ版の公開済みの成果物があるときだけ使える）
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
const lock = JSON.parse(readFileSync(path.join(root, "hx4s.lock.json"), "utf8"));
if (!/^[0-9a-f]{40}$/.test(lock.commit)) throw new Error(`hx4s.lock.json: commit must be a full SHA-1, got ${lock.commit}`);
if (!/^\d+\.\d+\.\d+-local\.\d+$/.test(lock.version)) throw new Error(`hx4s.lock.json: version must look like 0.1.0-local.N, got ${lock.version}`);

const args = process.argv.slice(2);
for (const arg of args) if (arg !== "--skip-publish") throw new Error(`unknown argument ${arg}`);
const skipPublish = args.includes("--skip-publish");

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

function run(command, commandArgs, cwd) {
  console.log(`> (${path.relative(root, cwd) || "."}) ${command} ${commandArgs.join(" ")}`);
  // Windows の npm.cmd はシェル経由でしか起動できない。引数に空白や引用符を含めない
  const result = spawnSync(command, commandArgs, { cwd, stdio: "inherit", shell: windows });
  if (result.status !== 0) throw new Error(`${command} ${commandArgs.join(" ")} failed with exit code ${result.status}`);
}

function git(hx4s, gitArgs) {
  const result = spawnSync("git", gitArgs, { cwd: hx4s, encoding: "utf8" });
  if (result.status !== 0) throw new Error(`git ${gitArgs.join(" ")} failed: ${result.stderr}`);
  return result.stdout;
}

const npm = windows ? "npm.cmd" : "npm";
const hx4s = findHx4s();
const head = git(hx4s, ["rev-parse", "HEAD"]).trim();
if (head !== lock.commit) {
  throw new Error(`${hx4s} is at ${head}, but hx4s.lock.json pins ${lock.commit}. Run \`git -C ${hx4s} checkout ${lock.commit}\`.`);
}
const dirty = git(hx4s, ["status", "--porcelain", "--untracked-files=all"]);
if (dirty !== "") throw new Error(`${hx4s} has uncommitted changes or untracked files:\n${dirty}`);

const tgz = path.join(hx4s, "target", "local", "hx4s-vite.tgz");
const versionFile = path.join(hx4s, "target", "local", "hx4s-version.txt");
if (skipPublish) {
  const published = existsSync(versionFile) ? readFileSync(versionFile, "utf8").trim() : "";
  const ivy = path.join(os.homedir(), ".ivy2", "local", "io.github.montereylangit", "sbt-hx4s", "scala_2.12", "sbt_1.0", lock.version);
  if (published !== lock.version || !existsSync(tgz) || !existsSync(ivy)) {
    throw new Error(`hx4s ${lock.version} is not published locally. Run without --skip-publish.`);
  }
} else {
  // publish-local.mjs は hx4s のワークスペースで hx4s-vite をビルドするので、hx4s 側の依存が要る
  if (!existsSync(path.join(hx4s, "node_modules"))) run(npm, ["ci"], hx4s);
  run("node", ["scripts/publish-local.mjs", "--version", lock.version], hx4s);
}

run(npm, ["ci"], root);
run(npm, ["install", "--no-save", "--no-audit", "--no-fund", path.relative(root, tgz).replaceAll("\\", "/")], root);
console.log(`hx4s ${lock.version} (${lock.commit.slice(0, 10)}) is ready`);
