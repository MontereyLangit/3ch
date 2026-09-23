// Reuse the unchanged characterization assertions against linked Scala implementations.
// Only implementation imports/loaders change; assertions and the v5 source stay untouched.
import { resolve as resolveWorkers } from '../../bbs/test-stubs/cloudflare-workers-loader.mjs';
const server = new URL('../../bbs/target/scala-3.3.8/bbs-fastopt/server.js', import.meta.url).href;
export async function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith('/moderation.js') || specifier.endsWith('/bbs.cgi.js') || specifier.endsWith('/admin.js')) return { url: server, shortCircuit: true };
  return resolveWorkers(specifier, context, nextResolve);
}
const targets = new Set(['thread.test.js', 'bbscgi.characterization.test.js', 'main.characterization.test.js'].map(name => new URL('../' + name, import.meta.url).href));
export async function load(url, context, nextLoad) {
  const result = await nextLoad(url, context);
  if (!targets.has(url)) return result;
  let source = String(result.source);
  if (url.endsWith('/main.characterization.test.js')) {
    const main = /async function loadMain\(\) \{[\s\S]*?\r?\n\}/;
    if (!main.test(source)) throw new Error('Main loader changed');
    source = source.replace(main, `async function loadMain() { return (await import(${JSON.stringify(server)})).publicFetch; }`);
  }
  const pattern = /async function loadClass\(file, name\) \{[\s\S]*?\r?\n\}/;
  if (!pattern.test(source)) throw new Error('Characterization loader changed: ' + url);
  return { ...result, source: source.replace(pattern, `async function loadClass(file, name) { return (await import(${JSON.stringify(server)}))[name]; }`) };
}
