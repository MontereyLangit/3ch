// .agents/skills/ のスキルを Claude Code が読む .claude/skills/ へ写す（docs/ai-skills.md）。
// Codex と Gemini CLI は .agents/skills/ を直接読む。原本は .agents/skills/ の1か所だけで、.claude/skills/ の写しは Git に入れない。
// 写し先は毎回消してから写すので、原本から消したファイルも写し先に残らない。
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const source = path.join(root, ".agents", "skills");
const target = path.join(root, ".claude", "skills");

if (existsSync(source)) {
  mkdirSync(target, { recursive: true });
  for (const entry of readdirSync(source, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const dest = path.join(target, entry.name);
    rmSync(dest, { recursive: true, force: true });
    cpSync(path.join(source, entry.name), dest, { recursive: true });
    console.log(`synced .claude/skills/${entry.name}`);
  }
}
