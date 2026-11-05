import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, access, cp } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { build } from 'vite';
import { islandComponents } from 'honox/vite';
import { JSDOM } from 'jsdom';
import { act } from 'react';

// 板topの実 SSR HTML（bbs のテスト用 export）を、HonoX の createClient で hydrate する。
// 3 つの island がすべて hydrate され、サーバーの DOM を再利用し、操作が効くことを確かめる。
const root = fileURLToPath(new URL('../', import.meta.url));
const bridge = path.join(root, 'bbs/target/scala-3.3.8/bbs-test-fastopt/island-test.js');
const clientModule = path.join(root, 'bbs-client/target/scala-3.3.8/bbsclient-fastopt/client.js');

const subjectText = '1800000001.dat<>実スレ &amp; <b> (3)\n1800000002.dat<>次の話題 (7)\n9240000001.dat<>共通のお知らせ (12)\n';
const sample = {
  title: 'テスト板', headText: '<b>ローカルルール</b>', bbs: 'sample', type: 'subback', subjectText,
  time: '1800000100', imgurClientId: 'cid', now: 1800000100000, settings: { BBS_TITLE: 'テスト' },
};

test('板topの実 SSR HTML を HonoX の createClient で hydrate できる', async () => {
  await access(bridge);
  await access(clientModule);
  const previousNodeEnv = process.env.NODE_ENV;
  process.env.NODE_ENV = 'development';
  const fixture = await mkdtemp(path.join(root, 'tests/.board-top-hydration-'));
  let dom;
  const previous = new Map();
  let hydrator;
  try {
    // HonoX は Vite root 基準の import.meta.glob('/app/islands/**/*.tsx') で island を探す。生成済みの island を複製する。
    await mkdir(path.join(fixture, 'app'), { recursive: true });
    await cp(path.join(root, 'app/islands'), path.join(fixture, 'app/islands'), { recursive: true });
    await writeFile(path.join(fixture, 'server.js'), `
import { renderBoardTopTest } from 'scala-island-test';
export const render = raw => renderBoardTopTest(raw);
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
        'scalajs:client.js': clientModule,
        'cloudflare:workers': path.join(root, 'bbs/test-stubs/cloudflare-workers-stub.mjs'),
      } },
      ssr: { noExternal: ['honox'] },
    };
    await build({ ...config,
      plugins: [islandComponents({ reactApiImportSource: 'react' })],
      build: { ssr: path.join(fixture, 'server.js'), outDir: path.join(fixture, 'server-out'),
        rollupOptions: { output: { entryFileNames: 'entry.mjs' } } },
    });
    await build({ ...config,
      build: { ssr: path.join(fixture, 'client.js'), outDir: path.join(fixture, 'client-out'),
        rollupOptions: { output: { entryFileNames: 'entry.mjs', chunkFileNames: '[name]-[hash].mjs' } } },
    });
    // react-dom は読み込み時に window の有無を見る（input イベント対応の判定など）ため、import より前にグローバルを用意する。
    dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://board.example/sample/' });
    const { window } = dom;
    window.performance.getEntriesByType = () => [];
    const globals = {
      window, document: window.document, navigator: window.navigator, localStorage: window.localStorage,
      HTMLElement: window.HTMLElement, HTMLInputElement: window.HTMLInputElement, HTMLTextAreaElement: window.HTMLTextAreaElement,
      Node: window.Node, Event: window.Event, MouseEvent: window.MouseEvent, FormData: window.FormData, File: window.File,
      IS_REACT_ACT_ENVIRONMENT: true,
    };
    for (const key of Object.keys(globals)) previous.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    for (const [key, value] of Object.entries(globals)) {
      Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
    }
    const server = await import(pathToFileURL(path.join(fixture, 'server-out/entry.mjs')).href);
    hydrator = await import(pathToFileURL(path.join(fixture, 'client-out/entry.mjs')).href);
    window.document.body.innerHTML = server.render(sample);

    const { document } = window;
    assert.equal(document.querySelectorAll('honox-island').length, 3, 'island 境界は 3 つ（新規スレッド作成欄は board-details 内の通常コンポーネント）');
    const names = [...document.querySelectorAll('honox-island')].map(el => el.getAttribute('component-name')).sort();
    assert.deepEqual(names, ['/app/islands/board-details.tsx', '/app/islands/site-header.tsx', '/app/islands/thread-list.tsx']);

    const before = {
      list: document.querySelector('#thread-list'),
      form: document.querySelector('form.posting-form'),
      header: document.querySelector('header.site-header'),
      button: document.querySelector('[data-board-panel]'),
      reload: document.querySelector('[data-board-reload]'),
    };
    for (const [name, element] of Object.entries(before)) assert.ok(element, `${name} が SSR に存在する`);
    const panel = document.getElementById(before.button.dataset.boardPanel);
    assert.equal(panel.hidden, true);

    const consoleErrors = [];
    const originalError = console.error;
    console.error = (...args) => { consoleErrors.push(args.map(String).join(' ')); };
    try {
      await act(async () => { await hydrator.hydrate(); });
      await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });

      assert.deepEqual(hydrator.errors, [], 'hydration は SSR の HTML と一致する');
      assert.equal(hydrator.roots.length, 3, '3 つの island が hydrate された');
      assert.equal(document.querySelector('#thread-list'), before.list);
      assert.equal(document.querySelector('form.posting-form'), before.form);
      assert.equal(document.querySelector('header.site-header'), before.header);
      assert.equal(document.querySelector('[data-board-panel]'), before.button);
      assert.equal(document.querySelector('[data-board-reload]'), before.reload);

      assert.equal(before.reload.querySelector('img').getAttribute('src'), '/static/reload.svg');
      assert.ok(document.querySelector('[data-thread-key="924/9240000001"]'));

      await act(async () => { before.button.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true })); });
      assert.equal(before.button.getAttribute('aria-expanded'), 'true');
      assert.equal(panel.hidden, false, 'hydration 後はボタンでパネルが開く');

      const textarea = before.form.querySelector('textarea[name="MESSAGE"]');
      await act(async () => {
        Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set.call(textarea, '本文');
        textarea.dispatchEvent(new window.Event('input', { bubbles: true }));
      });
      assert.deepEqual(JSON.parse(window.localStorage.getItem('3ch:draft:sample/new')), { subject: '', MESSAGE: '本文' });
      assert.equal(document.querySelector('form.posting-form'), before.form);
    } finally {
      console.error = originalError;
    }
    assert.deepEqual(consoleErrors, [], 'console.error が出ていない');
  } finally {
    await act(async () => { for (const reactRoot of (hydrator?.roots ?? []).splice(0)) reactRoot.unmount(); });
    dom?.window.close();
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
    await rm(fixture, { recursive: true, force: true });
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});
