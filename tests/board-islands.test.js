import assert from 'node:assert/strict';
import { access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test, { beforeEach } from 'node:test';
import { JSDOM } from 'jsdom';

// 板topの island（bbs-client の Scala.js）を、リンク済みの実モジュールのまま jsdom 上で動かす。
// npm pretest が bbsClient/fastLinkJS を実行する。JS で再実装せず、実物の export だけを使う。
const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const clientJs = path.join(root, 'bbs-client/target/scala-3.3.8/bbsclient-fastopt/client.js');
await access(clientJs);

// react-dom は読み込み時に window の有無を見るため、import より前にグローバルを用意する。
const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://board.example/sample/' });
const { window } = dom;
const shared = {
  window, document: window.document, navigator: window.navigator, localStorage: window.localStorage,
  HTMLElement: window.HTMLElement, HTMLInputElement: window.HTMLInputElement, HTMLTextAreaElement: window.HTMLTextAreaElement,
  Node: window.Node, Event: window.Event, MouseEvent: window.MouseEvent, FormData: window.FormData, File: window.File,
  IS_REACT_ACT_ENVIRONMENT: true,
};
for (const [key, value] of Object.entries(shared)) {
  Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
}
window.performance.getEntriesByType = () => [];

const { act, createElement } = await import('react');
const { createRoot, hydrateRoot } = await import('react-dom/client');
const { renderToString } = await import('react-dom/server');
const client = await import(pathToFileURL(clientJs).href);

const scrollIntoViewCalls = [];
window.HTMLElement.prototype.scrollIntoView = function (options) { scrollIntoViewCalls.push({ element: this, options }); };
Object.defineProperty(window.HTMLElement.prototype, 'offsetHeight', {
  get() { return this.tagName === 'HEADER' ? 64 : 0; }, configurable: true,
});

const mounted = [];
beforeEach(async () => {
  await act(async () => { for (const entry of mounted.splice(0)) entry.unmount(); });
  document.body.innerHTML = '';
  window.localStorage.clear();
  window.performance.getEntriesByType = () => [];
  scrollIntoViewCalls.length = 0;
  Object.defineProperty(window, 'scrollY', { value: 0, configurable: true });
});

async function mount(Component, props, parent = document.body) {
  const container = parent.appendChild(document.createElement('div'));
  const reactRoot = createRoot(container);
  mounted.push(reactRoot);
  await act(async () => { reactRoot.render(createElement(Component, props)); });
  return container;
}

const flush = () => act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
const query = (selector, scope = document) => scope.querySelector(selector);

function setValue(element, value) {
  const proto = element instanceof window.HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(element, value);
}

async function type(selector, value) {
  const element = query(selector);
  await act(async () => {
    setValue(element, value);
    element.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
}

const click = (element, init = {}) => act(async () => {
  element.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, ...init }));
});

// ---------------------------------------------------------------- スレ一覧（ThreadListJs）

const subjectText = '1800000001.dat<>実スレ &amp; <b> (3)\n1800000002.dat<>次の話題 (7)\n9240000001.dat<>共通のお知らせ (12)\n';
const listProps = { bbs: 'sample', type: 'subback', subjectText, query: '', sort: 'new', now: 1800000100000 };

function sjis(...parts) {
  return Buffer.concat(parts.map(part => (typeof part === 'string' ? Buffer.from(part, 'latin1') : Buffer.from(part))));
}
// 「日本語」の Shift_JIS（「新」は 0x90 0x56）
const NIHONGO = [0x93, 0xfa, 0x96, 0x7b, 0x8c, 0xea];

function subjectResponse(buffer) {
  return { ok: true, status: 200, arrayBuffer: async () => buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength) };
}

test('スレ一覧: subject を描画し、全板共通スレとエスケープ・リンクを保つ', async () => {
  await mount(client.ThreadListJs, listProps);
  const rows = [...document.querySelectorAll('[data-thread-key]')];
  assert.deepEqual(rows.map(row => row.dataset.threadKey), ['924/9240000001', 'sample/1800000001', 'sample/1800000002']);
  assert.equal(query('[data-thread-key="sample/1800000001"] a').textContent, '実スレ & <b>');
  assert.equal(query('[data-thread-key="sample/1800000001"] a').getAttribute('href'), '/test/read.cgi/sample/1800000001/');
  assert.equal(query('[data-thread-key="924/9240000001"]').dataset.common, 'true');
  assert.match(query('[data-thread-key="sample/1800000001"] .created').textContent, /^2027\/01\/15 \d\d:\d\d$/);
  assert.equal(query('[data-board-reload]').textContent, 'リロード');
  const icon = query('[data-board-reload] img');
  assert.equal(icon.getAttribute('src'), '/static/reload.svg');
  assert.equal(icon.getAttribute('alt'), '');
});

test('スレ一覧: feed は準備中表示、検索とレス数順を適用する', async () => {
  await mount(client.ThreadListJs, { ...listProps, type: 'feed', subjectText: '' });
  assert.match(query('#thread-list').textContent, /Coming Soon…/);
  assert.equal(query('[data-thread-key]'), null);
  document.body.innerHTML = '';
  await mount(client.ThreadListJs, { ...listProps, sort: 'res' });
  const keys = [...document.querySelectorAll('.thread-list [data-thread-key]')].map(row => row.dataset.threadKey);
  assert.deepEqual(keys, ['sample/1800000002', 'sample/1800000001']);
  document.body.innerHTML = '';
  await mount(client.ThreadListJs, { ...listProps, query: '実スレ &' });
  assert.deepEqual([...document.querySelectorAll('.thread-list [data-thread-key]')].map(row => row.dataset.threadKey), ['sample/1800000001']);
});

test('スレ閲覧履歴は左クリックと中クリックで記録し、右クリックでは記録しない', async () => {
  const key = '3ch:threadSeen:sample/1800000001';
  window.localStorage.setItem(key, '1');
  await mount(client.ThreadListJs, listProps);
  const row = query('[data-thread-key="sample/1800000001"]');
  assert.equal(query('.new', row).textContent, '+2');
  assert.equal(query('[data-thread-key="sample/1800000002"] .new').textContent, '', '保存が無い行はバッジなし');
  const link = query('a.thread-link', row);
  await act(async () => { link.dispatchEvent(new window.MouseEvent('auxclick', { bubbles: true, button: 2 })); });
  assert.equal(window.localStorage.getItem(key), '1');
  await act(async () => { link.dispatchEvent(new window.MouseEvent('auxclick', { bubbles: true, button: 1 })); });
  assert.equal(window.localStorage.getItem(key), '3');
  window.localStorage.setItem(key, '1');
  await click(link);
  assert.equal(window.localStorage.getItem(key), '3');
});

test('リロードは subject.txt（Shift_JIS）で一覧だけ差し替え、開閉・既読バッジを維持する', async () => {
  window.localStorage.setItem('3ch:threadSeen:sample/1800000001', '3');
  await mount(client.ThreadListJs, listProps);
  query('.common-threads').open = true;
  const requests = [];
  let release;
  globalThis.fetch = (url, init) => new Promise(resolve => {
    requests.push({ url, init });
    release = () => resolve(subjectResponse(sjis('1800000001.dat<>', NIHONGO, ' (8)\n\n  \n1800000003.dat<>', [0x90, 0x56], '&#128512; (1)\n')));
  });
  const button = query('[data-board-reload]');
  await click(button);
  assert.equal(button.disabled, true);
  assert.equal(query('[data-board-reload-status]').textContent, '更新中…');
  await act(async () => { release(); await new Promise(resolve => setTimeout(resolve, 20)); });
  assert.equal(requests[0].url, '/sample/subject.txt');
  assert.equal(requests[0].init.cache, 'no-store');
  assert.equal(query('[data-thread-key="sample/1800000001"] a').textContent, '日本語');
  assert.equal(query('[data-thread-key="sample/1800000003"] a').textContent, '新😀', '非 Shift_JIS 文字は数値参照から戻る');
  assert.equal(query('[data-thread-key="sample/1800000002"]'), null);
  assert.equal(query('.common-threads').open, true, '開いた共通スレは維持');
  assert.equal(query('[data-thread-key="sample/1800000001"] .new').textContent, '+5');
  assert.equal(button.disabled, false);
  assert.equal(query('[data-board-reload-status]').textContent, '');
  await click(query('[data-thread-key="sample/1800000001"] a.thread-link'));
  assert.equal(window.localStorage.getItem('3ch:threadSeen:sample/1800000001'), '8');
});

test('リロード失敗時は一覧を変えずエラーを表示する', async () => {
  await mount(client.ThreadListJs, listProps);
  const before = query('#thread-list .subback').innerHTML;
  const failures = [async () => ({ ok: false, status: 500, arrayBuffer: async () => new ArrayBuffer(0) }), async () => { throw new Error('offline'); }];
  for (const fetcher of failures) {
    globalThis.fetch = fetcher;
    await click(query('[data-board-reload]'));
    await flush();
    assert.equal(query('[data-board-reload-status]').textContent, '更新できませんでした');
    assert.equal(query('#thread-list .subback').innerHTML, before);
    assert.equal(query('[data-board-reload]').disabled, false);
  }
});

// ---------------------------------------------------------------- ヘッダー（SiteHeaderJs）

async function mountHeader() {
  const board = document.body.appendChild(document.createElement('div'));
  board.className = 'board-ui';
  const search = board.appendChild(document.createElement('input'));
  search.id = 'board-search';
  await mount(client.SiteHeaderJs, {}, board);
  return { board, search, header: query('header', board) };
}

const scrollTo = y => act(async () => {
  Object.defineProperty(window, 'scrollY', { value: y, configurable: true });
  window.dispatchEvent(new window.Event('scroll'));
});

test('ヘッダーは下スクロールで隠れ、上スクロールで現れ、--header-height を更新する', async () => {
  const { board, header } = await mountHeader();
  assert.equal(board.style.getPropertyValue('--header-height'), '64px');
  await scrollTo(100);
  assert.equal(header.classList.contains('header-hidden'), true);
  assert.equal(board.style.getPropertyValue('--header-height'), '0px');
  await scrollTo(102);
  assert.equal(header.classList.contains('header-hidden'), true, '5px 未満の動きは無視');
  await scrollTo(50);
  assert.equal(header.classList.contains('header-hidden'), false);
  assert.equal(board.style.getPropertyValue('--header-height'), '64px');
});

test('隠れたヘッダーはフォーカスで現れ、フォーカス中はスクロールしても隠れない', async () => {
  const { board, search, header } = await mountHeader();
  await scrollTo(100);
  assert.equal(header.classList.contains('header-hidden'), true);
  await act(async () => { query('header button', board).focus(); });
  assert.equal(header.classList.contains('header-hidden'), false);
  assert.equal(board.style.getPropertyValue('--header-height'), '64px');
  await scrollTo(200);
  assert.equal(header.classList.contains('header-hidden'), false);
  await act(async () => { search.focus(); });
  await scrollTo(300);
  assert.equal(header.classList.contains('header-hidden'), true);
});

test('ヘッダーの検索リンクは入力欄へフォーカスを移してスクロールする', async () => {
  const { search, header } = await mountHeader();
  const event = new window.MouseEvent('click', { bubbles: true, cancelable: true });
  await act(async () => { query('a[href="#board-search"]', header).dispatchEvent(event); });
  assert.equal(event.defaultPrevented, true);
  assert.equal(document.activeElement, search);
  assert.equal(scrollIntoViewCalls.length, 1);
  assert.equal(scrollIntoViewCalls[0].element, search);
  assert.equal(scrollIntoViewCalls[0].options.block, 'start');
});

// ---------------------------------------------------------------- 板の開閉パネル（BoardDetailsJs）

const detailsProps = { bbs: 'sample', headText: '<b>ルール</b>', settingsText: 'BBS_TITLE=テスト\nBBS_NONAME_NAME=名無し', time: '1', imgurClientId: '', commands: [] };

test('パネルのボタンは開閉と aria-expanded・矢印を切り替える', async () => {
  await mount(client.BoardDetailsJs, detailsProps);
  const buttons = [...document.querySelectorAll('[data-board-panel]')];
  assert.deepEqual(buttons.map(button => button.dataset.boardPanel), ['board-rules', 'board-settings', 'board-headline', 'board-new-thread']);
  for (const button of buttons) {
    assert.equal(button.getAttribute('aria-expanded'), 'false');
    assert.equal(button.getAttribute('aria-controls'), button.dataset.boardPanel);
    assert.equal(query('span', button).textContent, '▸ ');
    assert.equal(document.getElementById(button.dataset.boardPanel).hidden, true);
  }
  assert.equal(query('#board-rules b').textContent, 'ルール');
  assert.equal(query('#board-settings pre').textContent, 'BBS_TITLE=テスト\nBBS_NONAME_NAME=名無し');
  assert.equal(query('#board-new-thread form.posting-form').dataset.draftKey, 'sample/new');
  await click(buttons[0]);
  assert.equal(buttons[0].getAttribute('aria-expanded'), 'true');
  assert.equal(query('span', buttons[0]).textContent, '▾ ');
  assert.equal(document.getElementById('board-rules').hidden, false);
  assert.equal(document.getElementById('board-settings').hidden, true);
  await click(buttons[0]);
  assert.equal(buttons[0].getAttribute('aria-expanded'), 'false');
  assert.equal(document.getElementById('board-rules').hidden, true);
});

// ---------------------------------------------------------------- 書き込みフォーム（PostingFormJs）

const formProps = { bbs: 'sample', time: '1800000100', imgurClientId: 'cid', commands: [
  { text: '!cmd', label: 'cmd', field: 'MESSAGE', description: 'コマンド' },
  { text: 'sage', label: 'sage', field: 'mail', description: 'sage' },
] };
const field = name => query(`[name="${name}"]`);
const DRAFT = '3ch:draft:sample/new';

const mountForm = async (props = formProps) => mount(client.PostingFormJs, props);

test('フォーム: 空欄だけ名前・メールを復元して details を開き、入力で保存する', async () => {
  window.localStorage.setItem('3ch:postName', '名無し');
  window.localStorage.setItem('3ch:postMail', 'sage');
  await mountForm();
  assert.equal(field('FROM').value, '名無し');
  assert.equal(field('mail').value, 'sage');
  assert.equal(query('details').open, true);
  await type('[name="FROM"]', 'ほげ');
  assert.equal(window.localStorage.getItem('3ch:postName'), 'ほげ');
  await type('[name="mail"]', '');
  assert.equal(window.localStorage.getItem('3ch:postMail'), '');
});

test('フォーム: 保存値が無ければ details は閉じたまま', async () => {
  await mountForm();
  assert.equal(query('details').open, false);
  assert.equal(field('FROM').value, '');
});

test('フォーム: SSR 相当の初期描画は通常 POST 用の name・hidden を持つ', async () => {
  await mountForm({ ...formProps, imgurClientId: '' });
  const form = query('form.posting-form');
  assert.equal(form.method, 'post');
  assert.equal(form.getAttribute('action'), '/test/bbs.cgi');
  assert.equal(field('bbs').value, 'sample');
  assert.equal(field('time').value, '1800000100');
  assert.equal(field('key'), null);
  assert.equal(query('[data-imgur-upload]'), null);
  assert.equal(form.dataset.imgurClientId, undefined);
  document.body.innerHTML = '';
  await mount(client.PostingFormJs, { ...formProps, threadKey: '1800000001', imgurClientId: '' });
  assert.equal(field('key').value, '1800000001');
  assert.equal(field('subject'), null);
  assert.equal(query('[data-command-helper]'), null, '返信フォームにコマンド補助は出さない');
  assert.equal(query('form').dataset.draftKey, 'sample/1800000001');
});

test('フォーム: 下書きを保存・復元し、両方空なら削除する', async () => {
  await mountForm();
  await type('[name="subject"]', 'タイトル');
  await type('[name="MESSAGE"]', '本文');
  assert.deepEqual(JSON.parse(window.localStorage.getItem(DRAFT)), { subject: 'タイトル', MESSAGE: '本文' });
  await type('[name="subject"]', '');
  await type('[name="MESSAGE"]', '');
  assert.equal(window.localStorage.getItem(DRAFT), null);
  document.body.innerHTML = '';
  window.localStorage.setItem(DRAFT, JSON.stringify({ subject: 'S', MESSAGE: 'M' }));
  await mountForm();
  assert.equal(field('subject').value, 'S');
  assert.equal(field('MESSAGE').value, 'M');
});

test('フォーム: 送信済み下書きは通常遷移では破棄し、back_forward では復元して sent を外す', async () => {
  const seed = () => window.localStorage.setItem(DRAFT, JSON.stringify({ subject: 'S', MESSAGE: 'M', sent: 1 }));
  seed();
  await mountForm();
  assert.equal(field('MESSAGE').value, '');
  assert.equal(window.localStorage.getItem(DRAFT), null);
  document.body.innerHTML = '';
  seed();
  window.performance.getEntriesByType = () => [{ type: 'back_forward' }];
  await mountForm();
  assert.equal(field('MESSAGE').value, 'M');
  assert.deepEqual(JSON.parse(window.localStorage.getItem(DRAFT)), { subject: 'S', MESSAGE: 'M' });
});

test('フォーム: submit で sent を記録し、bfcache 復帰では sent なしで再保存する', async () => {
  await mountForm();
  await type('[name="MESSAGE"]', '本文');
  const submit = new window.Event('submit', { bubbles: true, cancelable: true });
  await act(async () => { query('form').dispatchEvent(submit); });
  assert.equal(submit.defaultPrevented, false);
  assert.equal(typeof JSON.parse(window.localStorage.getItem(DRAFT)).sent, 'number');
  const pageshow = new window.Event('pageshow');
  Object.defineProperty(pageshow, 'persisted', { value: true });
  await act(async () => { window.dispatchEvent(pageshow); });
  assert.deepEqual(JSON.parse(window.localStorage.getItem(DRAFT)), { subject: '', MESSAGE: '本文' });
});

async function upload(file) {
  const input = query('[data-imgur-upload]');
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  await act(async () => { input.dispatchEvent(new window.Event('change', { bubbles: true })); });
  await flush();
}

const png = () => new window.File(['x'], 'a.png', { type: 'image/png' });
const imgurOk = () => ({ ok: true, json: async () => ({ data: { link: 'https://i.imgur.com/a.png' } }) });

test('フォーム: imgur アップロード成功でリンクを本文へ追記し、下書きも保存する', async () => {
  const calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ url, init }); return imgurOk(); };
  await mountForm();
  await type('[name="MESSAGE"]', '本文');
  await upload(png());
  assert.equal(field('MESSAGE').value, '本文\nhttps://i.imgur.com/a.png');
  assert.equal(query('[data-imgur-status]').textContent, '画像をアップロードしました');
  assert.equal(JSON.parse(window.localStorage.getItem(DRAFT)).MESSAGE, '本文\nhttps://i.imgur.com/a.png');
  assert.equal(calls[0].url, 'https://api.imgur.com/3/image');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.headers.Authorization, 'Client-ID cid');
  assert.equal(calls[0].init.body.get('image').name, 'a.png');
  assert.equal(query('[data-imgur-upload]').hasAttribute('name'), false);
});

test('フォーム: 本文が空なら改行なしで、改行終わりならそのまま追記する', async () => {
  globalThis.fetch = async () => imgurOk();
  await mountForm();
  await upload(png());
  assert.equal(field('MESSAGE').value, 'https://i.imgur.com/a.png');
  await type('[name="MESSAGE"]', '行\n');
  await upload(png());
  assert.equal(field('MESSAGE').value, '行\nhttps://i.imgur.com/a.png');
});

test('フォーム: imgur アップロード中は送信を止め、sent も記録しない。完了後は送信できる', async () => {
  let finish;
  globalThis.fetch = () => new Promise(resolve => { finish = () => resolve(imgurOk()); });
  await mountForm();
  await type('[name="MESSAGE"]', '本文');
  await upload(png());
  assert.equal(query('[data-imgur-status]').textContent, 'アップロード中…');
  const blocked = new window.Event('submit', { bubbles: true, cancelable: true });
  await act(async () => { query('form').dispatchEvent(blocked); });
  assert.equal(blocked.defaultPrevented, true);
  assert.equal(query('[data-imgur-status]').textContent, 'アップロード中です。完了してから送信してください');
  assert.equal(JSON.parse(window.localStorage.getItem(DRAFT)).sent, undefined);
  await act(async () => { finish(); await new Promise(resolve => setTimeout(resolve, 20)); });
  const allowed = new window.Event('submit', { bubbles: true, cancelable: true });
  await act(async () => { query('form').dispatchEvent(allowed); });
  assert.equal(allowed.defaultPrevented, false);
  assert.equal(typeof JSON.parse(window.localStorage.getItem(DRAFT)).sent, 'number');
});

test('フォーム: imgur アップロード失敗と非画像ファイルはエラー表示にする', async () => {
  let called = 0;
  globalThis.fetch = async () => { called++; return { ok: false, json: async () => ({}) }; };
  await mountForm();
  await upload(png());
  assert.equal(query('[data-imgur-status]').textContent, 'アップロードできません');
  assert.equal(field('MESSAGE').value, '');
  await upload(new window.File(['x'], 'a.txt', { type: 'text/plain' }));
  assert.equal(query('[data-imgur-status]').textContent, '画像ファイルを選んでください');
  assert.equal(called, 1);
  globalThis.fetch = async () => { throw new Error('offline'); };
  await upload(png());
  assert.equal(query('[data-imgur-status]').textContent, 'アップロードできません');
});

test('フォーム: コマンドボタンは本文先頭への挿入・削除と aria-pressed を切り替える', async () => {
  await mountForm();
  const [message, mail] = document.querySelectorAll('[data-command]');
  await type('[name="MESSAGE"]', '本文');
  await click(message);
  assert.equal(field('MESSAGE').value, '!cmd\n本文');
  assert.equal(message.getAttribute('aria-pressed'), 'true');
  assert.deepEqual(JSON.parse(window.localStorage.getItem(DRAFT)), { subject: '', MESSAGE: '!cmd\n本文' });
  await click(message);
  assert.equal(field('MESSAGE').value, '本文');
  assert.equal(message.getAttribute('aria-pressed'), 'false');
  await type('[name="MESSAGE"]', '!cmd\nx');
  assert.equal(message.getAttribute('aria-pressed'), 'true');
  await type('[name="mail"]', 'abc');
  await click(mail);
  assert.equal(field('mail').value, 'abc sage');
  assert.equal(mail.getAttribute('aria-pressed'), 'true');
  assert.equal(window.localStorage.getItem('3ch:postMail'), 'abc sage');
  await click(mail);
  assert.equal(field('mail').value, 'abc');
});

test('フォーム: localStorage が使えなくても入力できる', async () => {
  const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
  Object.defineProperty(window, 'localStorage', { get() { throw new Error('denied'); }, configurable: true });
  try {
    await mountForm();
    await type('[name="MESSAGE"]', '本文');
    assert.equal(field('MESSAGE').value, '本文');
    await mount(client.ThreadListJs, listProps);
    assert.equal(query('[data-thread-key="sample/1800000001"] .new').textContent, '');
  } finally {
    Object.defineProperty(window, 'localStorage', original);
  }
});

// ---------------------------------------------------------------- 既読バッジの即時反映と他タブ同期

const SEEN = id => `3ch:threadSeen:sample/${id}`;
const badgeOf = id => query(`[data-thread-key="sample/${id}"] .new`).textContent;
const storageEvent = (key, newValue) => act(async () => {
  window.dispatchEvent(new window.StorageEvent('storage', { key, newValue }));
});

test('既読バッジ: 左クリックと中クリックで即座に消え、右クリックでは変わらない', async () => {
  window.localStorage.setItem(SEEN('1800000001'), '1');
  await mount(client.ThreadListJs, listProps);
  const link = query('[data-thread-key="sample/1800000001"] a.thread-link');
  assert.equal(badgeOf('1800000001'), '+2');
  await act(async () => { link.dispatchEvent(new window.MouseEvent('auxclick', { bubbles: true, button: 2 })); });
  assert.equal(badgeOf('1800000001'), '+2');
  assert.equal(window.localStorage.getItem(SEEN('1800000001')), '1');
  await act(async () => { link.dispatchEvent(new window.MouseEvent('auxclick', { bubbles: true, button: 1 })); });
  assert.equal(badgeOf('1800000001'), '', '中クリックで表示も消える');
  assert.equal(window.localStorage.getItem(SEEN('1800000001')), '3');
  window.localStorage.setItem(SEEN('1800000001'), '1');
  await storageEvent(SEEN('1800000001'), '1');
  assert.equal(badgeOf('1800000001'), '+2');
  await click(link);
  assert.equal(badgeOf('1800000001'), '', '左クリックで表示も消える');
  assert.equal(window.localStorage.getItem(SEEN('1800000001')), '3');
});

test('既読バッジ: storage イベントで減少・増加を反映し、無関係なキーは無視する', async () => {
  window.localStorage.setItem(SEEN('1800000001'), '1');
  await mount(client.ThreadListJs, listProps);
  assert.equal(badgeOf('1800000001'), '+2');
  await storageEvent(SEEN('1800000001'), '3');
  assert.equal(badgeOf('1800000001'), '', '他タブで既読になればバッジが消える');
  await storageEvent(SEEN('1800000001'), '2');
  assert.equal(badgeOf('1800000001'), '+1', '既読数が減れば新しい +N');
  await storageEvent('3ch:postName', '名無し');
  await storageEvent('other:threadSeen:sample/1800000001', '0');
  assert.equal(badgeOf('1800000001'), '+1', '無関係なキーでは変わらない');
  assert.equal(badgeOf('1800000002'), '');
  await storageEvent(SEEN('1800000002'), '2');
  assert.equal(badgeOf('1800000002'), '+5');
  await storageEvent(SEEN('1800000002'), null);
  assert.equal(badgeOf('1800000002'), '', '削除されればバッジなし');
});

// ---------------------------------------------------------------- アンマウント・中断・並行アップロード

async function captureConsoleErrors(body) {
  const errors = [];
  const original = console.error;
  console.error = (...args) => { errors.push(args.map(String).join(' ')); };
  try {
    await body();
  } finally {
    console.error = original;
  }
  return errors;
}

const unmountLast = () => act(async () => { mounted.pop().unmount(); });
const settle = () => act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });

test('アンマウント: 保留中の imgur アップロードは中断され、完了しても何も起きない', async () => {
  const calls = [];
  let finish;
  globalThis.fetch = (url, init) => new Promise(resolve => {
    calls.push(init);
    finish = () => resolve(imgurOk());
  });
  const errors = await captureConsoleErrors(async () => {
    await mountForm();
    await upload(png());
    assert.equal(calls[0].signal.aborted, false);
    await unmountLast();
    assert.equal(calls[0].signal.aborted, true, 'アンマウントで中断される');
    finish();
    await settle();
  });
  assert.deepEqual(errors, []);
  assert.equal(window.localStorage.getItem(DRAFT), null, '完了は反映されず下書きも書かれない');
});

test('アンマウント: 保留中の subject.txt リロードは中断され、完了しても何も起きない', async () => {
  const calls = [];
  let finish;
  globalThis.fetch = (url, init) => new Promise(resolve => {
    calls.push(init);
    finish = () => resolve(subjectResponse(sjis('1800000009.dat<>x (1)\n')));
  });
  const errors = await captureConsoleErrors(async () => {
    const container = await mount(client.ThreadListJs, listProps);
    await click(query('[data-board-reload]'));
    assert.equal(calls[0].signal.aborted, false);
    await unmountLast();
    assert.equal(calls[0].signal.aborted, true);
    finish();
    await settle();
    assert.equal(container.innerHTML, '');
  });
  assert.deepEqual(errors, []);
});

test('リロード中はボタンが無効で、再度押しても二重に取得しない', async () => {
  const calls = [];
  let finish;
  globalThis.fetch = (url, init) => new Promise(resolve => {
    calls.push(init);
    finish = () => resolve(subjectResponse(sjis('1800000009.dat<>', NIHONGO, ' (1)\n')));
  });
  await mount(client.ThreadListJs, listProps);
  const button = query('[data-board-reload]');
  await click(button);
  await click(button);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].signal.aborted, false);
  assert.match(query('[data-board-reload] img').getAttribute('class'), /animate-spin/);
  await act(async () => { finish(); await new Promise(resolve => setTimeout(resolve, 20)); });
  assert.deepEqual([...document.querySelectorAll('.thread-list [data-thread-key]')].map(row => row.dataset.threadKey), ['sample/1800000009']);
  assert.doesNotMatch(query('[data-board-reload] img').getAttribute('class'), /animate-spin/);
  await click(button);
  assert.equal(calls.length, 2, '完了後は再度リロードできる');
  assert.equal(calls[0].signal.aborted, false, '完了済みの要求は中断されない');
});

test('フォーム: 並行する 2 件のアップロードは両方終わるまで送信を止める', async () => {
  const finishers = [];
  globalThis.fetch = () => new Promise(resolve => {
    const n = finishers.length;
    finishers.push(() => resolve({ ok: true, json: async () => ({ data: { link: `https://i.imgur.com/${n}.png` } }) }));
  });
  await mountForm();
  await upload(png());
  await upload(png());
  assert.equal(finishers.length, 2);
  const submitOnce = async () => {
    const event = new window.Event('submit', { bubbles: true, cancelable: true });
    await act(async () => { query('form').dispatchEvent(event); });
    return event.defaultPrevented;
  };
  assert.equal(await submitOnce(), true);
  await act(async () => { finishers[0](); await new Promise(resolve => setTimeout(resolve, 20)); });
  assert.equal(await submitOnce(), true, '1 件残っている間は止める');
  assert.equal(query('[data-imgur-status]').textContent, 'アップロード中です。完了してから送信してください');
  await act(async () => { finishers[1](); await new Promise(resolve => setTimeout(resolve, 20)); });
  assert.equal(await submitOnce(), false, '全件終われば送信できる');
  assert.equal(field('MESSAGE').value, 'https://i.imgur.com/0.png\nhttps://i.imgur.com/1.png');
});

// ---------------------------------------------------------------- SSR との一致（hydration）

test('SSR の描画と最初のクライアント描画が一致する（保存値があっても hydration エラーにならない）', async () => {
  window.localStorage.setItem('3ch:postName', '名無し');
  window.localStorage.setItem('3ch:threadSeen:sample/1800000001', '1');
  const cases = [
    [client.SiteHeaderJs, {}],
    [client.BoardDetailsJs, detailsProps],
    [client.PostingFormJs, formProps],
    [client.PostingFormJs, { ...formProps, threadKey: '1800000001' }],
    [client.ThreadListJs, listProps],
    [client.ThreadListJs, { ...listProps, type: 'feed' }],
  ];
  for (const [Component, props] of cases) {
    const container = document.body.appendChild(document.createElement('div'));
    const html = renderToString(createElement(Component, props));
    container.innerHTML = html;
    const errors = [];
    let reactRoot;
    await act(async () => {
      reactRoot = hydrateRoot(container, createElement(Component, props), { onRecoverableError: error => errors.push(error.message) });
    });
    mounted.push(reactRoot);
    assert.deepEqual(errors, [], `${Component.name || 'island'} が SSR と一致しません`);
  }
});
