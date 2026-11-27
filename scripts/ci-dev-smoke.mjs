// npm run dev を起動し、wrangler dev が応答することを確かめて終了する（CI 用）
import { spawn, spawnSync } from 'node:child_process';

const origin = 'http://127.0.0.1:8787';
const checks = [
  { path: '/', ok: status => status < 500 },
  { path: '/3chiko.html', ok: status => status === 200 },
];
const timeoutMs = Number(process.env.DEV_SMOKE_TIMEOUT_MS ?? 15 * 60 * 1000);
const isWindows = process.platform === 'win32';

const child = spawn('npm', ['run', 'dev'], {
  stdio: 'inherit',
  shell: isWindows,
  detached: !isWindows,
  env: { ...process.env, WRANGLER_SEND_METRICS: 'false' },
});

let exited = false;
child.on('exit', code => {
  exited = true;
  console.error(`npm run dev が終了しました (code ${code})`);
});

function stop() {
  if (exited) return;
  if (isWindows) {
    spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch {
      // 既に終了している
    }
  }
}

async function status(path) {
  try {
    const res = await fetch(origin + path, { signal: AbortSignal.timeout(10_000) });
    await res.arrayBuffer();
    return res.status;
  } catch {
    return undefined;
  }
}

async function main() {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && !exited) {
    if ((await status('/')) !== undefined) break;
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  if (exited || Date.now() >= deadline) {
    throw new Error('wrangler dev が応答しませんでした');
  }
  for (const { path, ok } of checks) {
    const code = await status(path);
    console.log(`GET ${path} -> ${code}`);
    if (code === undefined || !ok(code)) throw new Error(`GET ${path} が想定外の応答です (${code})`);
  }
}

main().then(
  () => {
    stop();
    process.exit(0);
  },
  error => {
    console.error(error.message);
    stop();
    process.exit(1);
  },
);
