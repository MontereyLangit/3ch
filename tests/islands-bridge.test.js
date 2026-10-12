import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { build, loadConfigFromFile } from 'vite';
import { islandComponents } from 'honox/vite';

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));

test('Workers resolve Scala client exports separately and link projects sequentially', async () => {
  const loaded = await loadConfigFromFile({ command: 'build', mode: 'workers' }, path.join(root, 'vite.config.ts'));
  assert.equal(loaded.config.resolve.alias['scalajs:client.js'], 'scalajs-client:client.js');
  const plugins = loaded.config.plugins.flat(Infinity);
  const scalaPlugins = plugins.filter((plugin) => plugin?.name === 'scalajs:sbt-scalajs-plugin');
  assert.equal(scalaPlugins.length, 2);
  for (const plugin of scalaPlugins) assert.equal(plugin.buildStart.sequential, true);
});

test('HonoX wraps a generated island in React SSR and serializes its props', async () => {
  const fixture = await mkdtemp(path.join(root, 'tests', '.islands-bridge-'));
  try {
    await mkdir(path.join(fixture, 'app/islands'), { recursive: true });
    // This fixture represents linked Scala client exports, not a Scala compiler test.
    await writeFile(path.join(fixture, 'client.js'), `import { createElement } from 'react';
export const Probe = ({ label }) => createElement('button', null, label);
`);
    await writeFile(path.join(fixture, 'app/islands/probe.tsx'), `import { Probe } from 'scalajs:client.js';
export default Probe;
`);
    await writeFile(path.join(fixture, 'entry.ts'), `import { createElement } from 'react';
import { renderToString } from 'react-dom/server';
import Probe from './app/islands/probe';
import { Probe as RawProbe } from 'scalajs:client.js';
export const html = renderToString(createElement(Probe, { label: 'from Scala' }));
export const rawHtml = renderToString(createElement(RawProbe, { label: 'from Scala' }));
`);
    await build({
      configFile: false,
      root: fixture,
      logLevel: 'silent',
      plugins: [islandComponents({ reactApiImportSource: 'react' })],
      resolve: { alias: { 'scalajs:client.js': path.join(fixture, 'client.js') } },
      esbuild: { jsx: 'automatic', jsxImportSource: 'react' },
      ssr: { noExternal: ['honox'] },
      build: {
        ssr: path.join(fixture, 'entry.ts'),
        outDir: path.join(fixture, 'out'),
        rollupOptions: { output: { entryFileNames: 'entry.mjs' } },
      },
    });
    const { html, rawHtml } = await import(pathToFileURL(path.join(fixture, 'out/entry.mjs')).href);
    assert.match(html, /<honox-island/);
    assert.match(html, /component-name="\/app\/islands\/probe.tsx"/);
    assert.match(html, /data-serialized-props="\{&quot;label&quot;:&quot;from Scala&quot;\}"/);
    assert.match(html, /<button>from Scala<\/button>/);
    // rawElement has the same React element shape, but bypasses HonoX's island transform.
    assert.equal(rawHtml, '<button>from Scala</button>');
    assert.doesNotMatch(rawHtml, /honox-island|component-name|data-serialized-props/);
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
});
