import assert from 'node:assert/strict';
import { access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test, { beforeEach } from 'node:test';
import { JSDOM } from 'jsdom';

// 管理画面の SPA を、リンク済みの adminClient のまま jsdom で動かす（API は差し替え）
// npm pretest が adminClient/fastLinkJS を実行
const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const mainJs = path.join(root, 'admin-client/target/scala-3.3.8/adminclient-fastopt/main.js');
await access(mainJs);

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'https://admin.example/admin/' });
const { window } = dom;
const shared = {
  window, document: window.document, navigator: window.navigator, location: window.location, history: window.history,
  HTMLElement: window.HTMLElement, HTMLInputElement: window.HTMLInputElement, HTMLTextAreaElement: window.HTMLTextAreaElement,
  HTMLSelectElement: window.HTMLSelectElement, Node: window.Node, Event: window.Event, MouseEvent: window.MouseEvent, PopStateEvent: window.PopStateEvent,
  IS_REACT_ACT_ENVIRONMENT: true,
};
for (const [key, value] of Object.entries(shared)) {
  Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
}
window.scrollTo = () => {};

const { act } = await import('react');
const { mountAdmin } = await import(pathToFileURL(mainJs).href);

// ---------------------------------------------------------------- 差し替えた API

const settingsView = () => ({
  bbs: 'sandbox',
  fields: {
    BBS_TITLE: { value: 'サンドボックス', explicit: true },
    BBS_SAMBA24: { value: '10', explicit: false },
    BBS_FR_LEVEL: { value: '1', explicit: false },
    BBS_HEISA: { value: '', explicit: false },
  },
  extra: ['UNKNOWN=1'],
  head: '<b>rules</b>',
  settingsEtag: 's1',
  headEtag: null,
  rules: { access: ['^sandbox$<><><>deny'], ngwords: [] },
});
const detail = () => ({
  bbs: 'sandbox', key: '1800000000', hidden: false, stopped: false, movedTo: null, movingTo: null,
  res: [
    { number: 1, name: '名無し </b>(JP.ab)<b>', mail: '', date: '2026/11/07(土) 10:00:00', message: ' 一行目 <br> &lt;b&gt; ',
      edit: { name: '名無し </b>(JP.ab)<b>', mail: '', message: '一行目\n<b>' } },
    { number: 2, name: 'あぼーん', mail: 'あぼーん', date: 'あぼーん', message: 'あぼーん', edit: { name: 'あぼーん', mail: 'あぼーん', message: 'あぼーん' } },
  ],
  dat: '名無し<><>2026/11/07<> 一行目 <>スレタイ &amp; テスト\n', log: '1<>IP:192.0.2.1\n', legacyLog: null, settings: '',
});

let requests = [];
let replies = {};
function reply(method, pathname, status, body) { replies[`${method} ${pathname}`] = { status, body }; }
function defaults() {
  replies = {};
  reply('GET', '/admin/api/boards/', 200, { boards: ['news', 'sandbox'] });
  reply('GET', '/admin/api/boards/sandbox/settings/', 200, settingsView());
  reply('PUT', '/admin/api/boards/sandbox/settings', 200, { settingsEtag: 's2', headEtag: 'h2', partial: false });
  reply('GET', '/admin/api/boards/sandbox/threads/', 200, { bbs: 'sandbox', subject: [{ key: '1800000000', line: '1800000000.dat<>スレタイ &amp; テスト (2)' }], removed: ['1700000000'] });
  reply('GET', '/admin/api/boards/sandbox/threads/1800000000/', 200, detail());
  reply('POST', '/admin/api/boards/sandbox/threads/1800000000/actions', 200, { ok: true });
  reply('GET', '/admin/api/rules/access/', 200, { text: '<><><>deny', etag: 'd1' });
  reply('PUT', '/admin/api/rules/access/', 200, { etag: 'd2' });
  reply('GET', '/admin/api/menu/', 200, { text: '<a href="/news/">news</a>', etag: null });
}
globalThis.fetch = async (url, init = {}) => {
  const target = new URL(url, window.location.href);
  const headers = new Headers(init.headers);
  const method = init.method ?? 'GET';
  requests.push({ method, path: target.pathname, headers, body: init.body === undefined ? undefined : JSON.parse(init.body) });
  const found = replies[`${method} ${target.pathname}`] ?? { status: 404, body: { code: 'admin:not-found', message: 'APIがありません。' } };
  return new Response(JSON.stringify(found.body), { status: found.status, headers: { 'content-type': 'application/json' } });
};

let confirms = [];
let confirmAnswer = true;
window.confirm = message => { confirms.push(message); return confirmAnswer; };

// ---------------------------------------------------------------- 補助

let unmount = null;
beforeEach(async () => {
  if (unmount) await act(async () => { unmount(); });
  document.body.innerHTML = '';
  requests = [];
  confirms = [];
  confirmAnswer = true;
  defaults();
});

const flush = () => act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
const query = (selector, scope = document) => scope.querySelector(selector);
const all = (selector, scope = document) => [...scope.querySelectorAll(selector)];
const byText = (selector, text) => all(selector).find(element => element.textContent.trim() === text);

async function open(pathname) {
  window.history.replaceState(null, '', pathname);
  const container = document.body.appendChild(document.createElement('div'));
  await act(async () => { unmount = mountAdmin(container); });
  await flush();
  return container;
}

function setValue(element, value) {
  const proto = element instanceof window.HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype
    : element instanceof window.HTMLSelectElement ? window.HTMLSelectElement.prototype : window.HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(element, value);
}
async function type(element, value) {
  await act(async () => {
    setValue(element, value);
    element.dispatchEvent(new window.Event(element instanceof window.HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}
async function click(element) {
  assert.ok(element, 'クリックする要素がありません');
  await act(async () => { element.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })); });
  await flush();
}
const changes = () => requests.filter(request => request.method !== 'GET');

// ---------------------------------------------------------------- テスト

test('トップ: 板の一覧を出し、リンクで板設定へ移ると URL とタイトルが変わる', async () => {
  await open('/admin/');
  assert.equal(document.title, '管理画面 - 3ch 管理画面');
  assert.deepEqual(all('tbody tr td:first-child').map(cell => cell.textContent), ['news', 'sandbox']);
  const link = all('a').find(a => a.getAttribute('href') === '/admin/boards/sandbox/' && a.closest('tbody'));
  await click(link);
  assert.equal(window.location.pathname, '/admin/boards/sandbox/');
  assert.equal(document.title, '板設定: sandbox - 3ch 管理画面');
  assert.equal(query('[aria-label="管理する板"]').value, 'sandbox');
  assert.deepEqual(requests.map(request => `${request.method} ${request.path}`), [
    'GET /admin/api/boards/', 'GET /admin/api/boards/sandbox/settings/']);
  assert.equal(all('[style]').length, 0, 'style 属性を使わない（CSP の style-src）');
});

test('板設定: 明示値・未設定・その他の設定・適用される規制ルールを出す', async () => {
  await open('/admin/boards/sandbox/');
  assert.equal(query('[aria-label="掲示板タイトル"]').value, 'サンドボックス');
  const samba = query('[aria-label="短時間投稿（Samba24）"]');
  assert.equal(samba.value, '');
  assert.equal(samba.placeholder, '未設定（既定 10）');
  assert.equal(query('#board-extra').value, 'UNKNOWN=1');
  assert.equal(query('#board-head').value, '<b>rules</b>');
  assert.match(document.body.textContent, /\^sandbox\$<><><>deny/);
  assert.equal(all('input[name="setting-BBS_FR_LEVEL"]').find(input => input.checked).value, '1');
  assert.equal(byText('button', '適用').disabled, true, '変更が無ければ適用できない');
});

test('板設定: 変えた項目だけを明示値にして ETag と JSON で保存し、読み直す', async () => {
  await open('/admin/boards/sandbox/');
  await type(query('[aria-label="短時間投稿（Samba24）"]'), '20');
  await click(query('input[type="checkbox"]', byText('label', '停止する')) ?? all('label').find(l => l.textContent.includes('停止する')).querySelector('input'));
  await type(query('#board-extra'), 'UNKNOWN=2\n\n');
  assert.match(document.body.textContent, /未保存の変更が3件あります。/);
  await click(all('button').find(button => button.textContent === '適用'));
  const [put] = changes();
  assert.equal(put.method, 'PUT');
  assert.equal(put.path, '/admin/api/boards/sandbox/settings');
  assert.equal(put.headers.get('content-type'), 'application/json');
  assert.equal(put.headers.get('x-ch3s-admin'), '1');
  assert.deepEqual(put.body.fields.BBS_SAMBA24, { value: '20', explicit: true });
  assert.deepEqual(put.body.fields.BBS_HEISA, { value: 'checked', explicit: true });
  assert.deepEqual(put.body.fields.BBS_FR_LEVEL, { value: '1', explicit: false });
  assert.deepEqual(put.body.extra, ['UNKNOWN=2']);
  assert.equal(put.body.settingsEtag, 's1');
  assert.equal(put.body.headEtag, null);
  assert.match(query('[role="status"]').textContent, /保存しました。/);
  assert.equal(requests.filter(request => request.path === '/admin/api/boards/sandbox/settings/').length, 2, '保存後に読み直す');
});

test('板設定: 項目の誤りは入力欄の下に、競合は開き直しの案内を出す', async () => {
  reply('PUT', '/admin/api/boards/sandbox/settings', 400, {
    code: 'admin:invalid', message: '入力内容を確認してください。', fields: { 'fields.BBS_SAMBA24': '0以上の整数で指定してください。' } });
  await open('/admin/boards/sandbox/');
  await type(query('[aria-label="短時間投稿（Samba24）"]'), '-1');
  await click(all('button').find(button => button.textContent === '適用'));
  assert.match(document.body.textContent, /0以上の整数で指定してください。/);
  assert.match(document.body.textContent, /1件の入力に誤りがあります。/);
  reply('PUT', '/admin/api/boards/sandbox/settings', 409, { code: 'admin:conflict', message: '他の操作で板設定が更新されました。画面を開き直してから保存してください。' });
  await click(all('button').find(button => button.textContent === '適用'));
  assert.match(query('[role="alert"]').textContent, /他の操作で板設定が更新されました。/);
  confirmAnswer = false;
  await click(byText('button', '開き直す'));
  assert.equal(confirms.length, 1, '編集中なら捨ててよいか確かめる');
  assert.equal(query('[aria-label="短時間投稿（Samba24）"]').value, '-1');
  confirmAnswer = true;
  await click(byText('button', '開き直す'));
  assert.equal(query('[aria-label="短時間投稿（Samba24）"]').value, '');
});

test('板設定: head.txt だけが競合したら SETTING.TXT の保存を知らせ、head.txt の編集を残す', async () => {
  reply('PUT', '/admin/api/boards/sandbox/settings', 200, { settingsEtag: 's2', headEtag: null, partial: true });
  await open('/admin/boards/sandbox/');
  await type(query('[aria-label="掲示板タイトル"]'), '新しい名前');
  await type(query('#board-head'), '新しい案内');
  await click(all('button').find(button => button.textContent === '適用'));
  assert.match(query('[role="alert"]').textContent, /head.txtは他の操作で更新されていたため保存していません。/);
  assert.match(document.body.textContent, /未保存の変更が1件あります。/);
  assert.equal(query('#board-head').value, '新しい案内');
});

test('未保存の変更があれば、画面内の移動・戻る・ページを閉じる前に確かめる', async () => {
  await open('/admin/boards/sandbox/');
  await type(query('[aria-label="掲示板タイトル"]'), '変更');
  confirmAnswer = false;
  await click(all('aside a').find(a => a.textContent === 'スレ一覧・スレ管理'));
  assert.equal(confirms.length, 1);
  assert.equal(window.location.pathname, '/admin/boards/sandbox/');
  const unload = new window.Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  assert.equal(unload.defaultPrevented, true);
  window.history.pushState(null, '', '/admin/menu/');
  await act(async () => { window.dispatchEvent(new window.PopStateEvent('popstate')); });
  assert.equal(confirms.length, 2);
  assert.equal(window.location.pathname, '/admin/boards/sandbox/', '戻る操作を取り消す');
  confirmAnswer = true;
  await click(all('aside a').find(a => a.textContent === 'スレ一覧・スレ管理'));
  assert.equal(window.location.pathname, '/admin/boards/sandbox/threads/');
  const after = new window.Event('beforeunload', { cancelable: true });
  window.dispatchEvent(after);
  assert.equal(after.defaultPrevented, false, '移った先では確認しない');
});

test('スレ一覧: 公開中と削除・移動済みを出し、key を指定して開ける', async () => {
  await open('/admin/boards/sandbox/threads/');
  assert.equal(all('tbody a')[0].textContent, 'スレタイ & テスト (2)');
  assert.equal(all('tbody a')[0].getAttribute('href'), '/admin/boards/sandbox/threads/1800000000/');
  assert.ok(all('a').some(a => a.getAttribute('href') === '/admin/boards/sandbox/threads/1700000000/'));
  await type(query('#thread-key'), '1800000000');
  await click(byText('button', '開く'));
  assert.equal(window.location.pathname, '/admin/boards/sandbox/threads/1800000000/');
});

test('スレ管理: 停止・削除・移動・レスの編集とあぼーんを API に送る', async () => {
  await open('/admin/boards/sandbox/threads/1800000000/');
  assert.match(query('h1').textContent, /スレタイ & テスト/);
  assert.match(query('h1').textContent, /公開中/);
  assert.match(query('#res-1').textContent, /名無し \(JP\.ab\)/);
  await click(byText('button', '書き込みを停止'));
  confirmAnswer = false;
  await click(byText('button', 'スレを削除'));
  confirmAnswer = true;
  await click(byText('button', 'スレを削除'));
  await type(query('#move-board'), 'destination');
  await type(query('#move-key'), '1800000001');
  await click(byText('button', '移動する'));
  await click(query('#res-1 button'));
  assert.equal(query('#res-message-1').value, '一行目\n<b>');
  await type(query('#res-message-1'), '書き換えた本文');
  await click(byText('button', '保存'));
  await click(all('#res-1 button').find(button => button.textContent === 'あぼーん'));
  assert.equal(all('#res-2 button').some(button => button.textContent === 'あぼーん'), false, 'あぼーん済みのレスには出さない');
  assert.deepEqual(changes().map(request => request.body), [
    { action: 'stop' },
    { action: 'delete' },
    { action: 'move', targetBoard: 'destination', targetKey: '1800000001' },
    { action: 'editRes', number: 1, name: '名無し </b>(JP.ab)<b>', mail: '', message: '書き換えた本文' },
    { action: 'deleteRes', number: 1 },
  ]);
  assert.equal(confirms.length, 4, '削除 2 回・移動・あぼーんで確かめる');
  assert.ok(changes().every(request => request.headers.get('x-ch3s-admin') === '1'));
  reply('POST', '/admin/api/boards/sandbox/threads/1800000000/actions', 409, { code: 'admin:thread-state', message: 'スレッドは停止中です。' });
  await click(byText('button', '書き込みを停止'));
  assert.match(query('[role="alert"]').textContent, /スレッドは停止中です。/);
});

test('規制ルール・板一覧: 読込時の ETag で保存し、誤りは行番号付きで出す', async () => {
  await open('/admin/rules/access/');
  assert.equal(query('#file-text').value, '<><><>deny');
  reply('PUT', '/admin/api/rules/access/', 400, { code: 'admin:invalid', message: '1行目: 動作が不正です。', fields: { text: '1行目: 動作が不正です。' } });
  await type(query('#file-text'), '<><><>unknown');
  await click(byText('button', '保存'));
  assert.match(document.body.textContent, /1行目: 動作が不正です。/);
  reply('PUT', '/admin/api/rules/access/', 200, { etag: 'd2' });
  await type(query('#file-text'), '<><><>mark');
  await click(byText('button', '保存'));
  assert.deepEqual(changes().map(request => request.body), [{ text: '<><><>unknown', etag: 'd1' }, { text: '<><><>mark', etag: 'd1' }]);
  assert.match(query('[role="status"]').textContent, /保存しました。/);
  assert.equal(byText('button', '保存').disabled, true);
  await click(all('aside a').find(a => a.textContent === '板一覧（bbsmenu）'));
  assert.equal(query('#file-text').value, '<a href="/news/">news</a>');
});

test('知らない URL はページなしを出し、API の失敗は文言を出す', async () => {
  await open('/admin/unknown/');
  assert.match(query('h1').textContent, /ページがありません/);
  if (unmount) await act(async () => { unmount(); });
  reply('GET', '/admin/api/boards/sandbox/threads/', 401, { code: 'admin:unauthorized', message: '管理者認証が必要です。' });
  await open('/admin/boards/sandbox/threads/');
  assert.match(document.body.textContent, /管理者認証が必要です。/);
});
