import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { build } from 'vite';
import { islandComponents } from 'honox/vite';
import { JSDOM } from 'jsdom';
import { act } from 'react';

const root = fileURLToPath(new URL('../', import.meta.url));
const bridge = path.join(root, 'bbs/target/scala-3.3.8/bbs-test-fastopt/island-test.js');

test('Scala Island rawElement supports HonoX discovery, nested islands and element props', async (t) => {
  // npm pretest links the real Scala bridge; do not silently substitute a JS implementation.
  await access(bridge);
  // Keep external React and React DOM in the same mode when Vite runs inside this process.
  const previousNodeEnv = process.env.NODE_ENV;
  process.env.NODE_ENV = 'development';
  const fixture = await mkdtemp(path.join(root, 'tests/.islands-hydration-'));
  try {
    await mkdir(path.join(fixture, 'app/islands'), { recursive: true });
    await writeFile(path.join(fixture, 'components.js'), `
import { createElement, useState } from 'react';
export function Counter({ label }) {
  const [count, setCount] = useState(0);
  return createElement('button', { onClick: () => setCount(n => n + 1) }, label + ':' + count);
}
export function Parent(props) { return createElement('div', null, createElement(Counter, props)); }
export function Slot({ children }) { return createElement('section', null, children); }
`);
    for (const [file, name] of [['counter', 'Counter'], ['parent', 'Parent'], ['slot', 'Slot']]) {
      await writeFile(path.join(fixture, `app/islands/${file}.tsx`),
        `import { ${name} } from '../../components.js';\nexport default ${name};\n`);
    }
    // SSR imports the linked Scala facade and raw components, never app/islands/*.tsx.
    await writeFile(path.join(fixture, 'server.js'), `
import { createElement, cloneElement } from 'react';
import { renderToString } from 'react-dom/server';
import { renderIslandTest } from 'scala-island-test';
import { Counter, Slot } from './components.js';
const island = (Component, props, name) => renderIslandTest(Component, props, '/app/islands/' + name + '.tsx');
const NestedParent = props => createElement('div', null, island(Counter, props, 'counter'));
export function renderCase(kind) {
  const props = { label: '日本語 "<&>' };
  if (kind === 'nested') return renderToString(island(NestedParent, props, 'parent'));
  if (kind === 'slot') return renderToString(island(Slot, {
    children: [createElement('span', { key: 'text' }, 'slot <&>'),
      cloneElement(island(Counter, props, 'counter'), { key: 'counter' })]
  }, 'slot'));
  return renderToString(island(Counter, props, 'counter'));
}
`);
    await writeFile(path.join(fixture, 'client.js'), `
import { createClient } from 'honox/client';
import { createElement } from 'react';
import { hydrateRoot } from 'react-dom/client';
export const roots = [];
export const errors = [];
export function hydrate() {
  return createClient({
    createElement,
    hydrate: (element, root) => roots.push(hydrateRoot(root, element, {
      onRecoverableError: error => errors.push(error.message)
    }))
  });
}
`);
    const config = {
      configFile: false,
      root: fixture,
      logLevel: 'silent',
      resolve: { alias: {
        'scala-island-test': bridge,
        'cloudflare:workers': path.join(root, 'bbs/test-stubs/cloudflare-workers-stub.mjs'),
      } },
      ssr: { noExternal: ['honox'] },
    };
    await build({ ...config,
      plugins: [islandComponents({ reactApiImportSource: 'react' })],
      build: { ssr: path.join(fixture, 'server.js'), outDir: path.join(fixture, 'server-out'),
        rollupOptions: { output: { entryFileNames: 'entry.mjs' } } },
    });
    // Node-targeted output lets jsdom execute the real client discovery and React hydration.
    // As in production's client build, no SSR island wrapping plugin is installed here.
    await build({ ...config,
      build: { ssr: path.join(fixture, 'client.js'), outDir: path.join(fixture, 'client-out'),
        rollupOptions: { output: { entryFileNames: 'entry.mjs', chunkFileNames: '[name]-[hash].mjs' } } },
    });
    const { renderCase } = await import(pathToFileURL(path.join(fixture, 'server-out/entry.mjs')).href);
    const client = await import(pathToFileURL(path.join(fixture, 'client-out/entry.mjs')).href);

    for (const kind of ['simple', 'nested', 'slot']) {
      await t.test(kind, async () => {
        const html = renderCase(kind);
        const dom = new JSDOM(`<!DOCTYPE html><html><body>${html}</body></html>`);
        const globals = { window: dom.window, document: dom.window.document, Node: dom.window.Node,
          IS_REACT_ACT_ENVIRONMENT: true };
        const previous = new Map(Object.keys(globals).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
        for (const [key, value] of Object.entries(globals)) {
          Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
        }
        try {
          const document = dom.window.document;
          assert.equal(document.querySelectorAll('honox-island').length, 1);
          const boundary = document.querySelector('honox-island');
          assert.equal(boundary.getAttribute('component-name'), `/app/islands/${kind === 'simple' ? 'counter' : kind === 'nested' ? 'parent' : 'slot'}.tsx`);
          if (kind === 'slot') {
            assert.equal(boundary.getAttribute('data-serialized-props'), '{}');
            const template = boundary.querySelector('template[data-hono-template="children"]');
            assert.ok(template);
            assert.equal(template.content.querySelectorAll('honox-island').length, 1);
          } else {
            assert.deepEqual(JSON.parse(boundary.getAttribute('data-serialized-props')), { label: '日本語 "<&>' });
          }
          const originalButton = document.querySelector('button');
          await act(async () => { await client.hydrate(); });
          assert.deepEqual(client.errors, [], 'hydration must match the SSR markup');
          assert.equal(client.roots.length, 1, 'nested islands must not create competing hydration roots');
          assert.equal(document.querySelector('button'), originalButton, 'hydration must reuse the server DOM');
          if (kind === 'slot') assert.equal(document.querySelector('span').textContent, 'slot <&>');
          await act(async () => { originalButton.click(); });
          assert.equal(originalButton.textContent, '日本語 "<&>:1');
        } finally {
          await act(async () => { for (const reactRoot of client.roots.splice(0)) reactRoot.unmount(); });
          client.errors.length = 0;
          dom.window.close();
          for (const [key, descriptor] of previous) {
            if (descriptor) Object.defineProperty(globalThis, key, descriptor);
            else delete globalThis[key];
          }
        }
      });
    }
  } finally {
    await rm(fixture, { recursive: true, force: true });
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});
