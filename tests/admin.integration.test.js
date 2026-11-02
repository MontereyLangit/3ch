import {test} from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {unstable_dev} from 'wrangler';

// 前提: `npm run v6:build` で dist/index.js が生成済みであること（無ければスキップ）。
const built = existsSync(new URL('../dist/index.js', import.meta.url));

test('Wrangler: 管理画面と公開投稿の統合検証', {
  timeout: 120000,
  skip: built ? false : 'dist/index.js がありません（npm run v6:build を先に実行）'
}, async t => {
  const worker = await unstable_dev('dist/index.js', {
    config: 'wrangler.jsonc',
    local: true,
    persist: false,
    port: 0,
    vars: {ADMIN_PASSWORD: 'local-integration-password'},
    logLevel: 'error',
    experimental: {disableExperimentalWarning: true, disableDevRegistry: true, watch: false}
  });
  t.after(() => worker.stop());
  const origin = `http://${worker.address}:${worker.port}`;
  const authorization = 'Basic ' + Buffer.from('admin:local-integration-password').toString('base64');
  const get = path => fetch(origin + path, {headers: {authorization}});
  const save = (path, data, extra = {}) => fetch(origin + path, {
    method: 'POST',
    headers: {authorization, origin, ...extra},
    body: new URLSearchParams(data),
    redirect: 'manual'
  });
  const postResponse = (bbs, key = '', message = 'hello', subject = '') => fetch(origin + '/test/bbs.cgi', {
    method: 'POST',
    headers: {
      'user-agent': 'IntegrationBrowser/1.0',
      referer: 'http://127.0.0.1:8787/',
      'CF-Connecting-IP': '192.0.2.1'
    },
    body: new URLSearchParams({
      bbs,
      key,
      MESSAGE: message,
      FROM: 'tester',
      mail: '',
      subject,
      time: '1',
      submit: '書き込む'
    })
  });
  const post = (...args) => postResponse(...args).then(r => r.text());
  const publicText = async path => new TextDecoder('shift_jis').decode(await (await fetch(origin + path)).arrayBuffer());
  for (const method of ['GET', 'HEAD']) {
    const redirect = await fetch(origin + '/sandbox?view=all', {method, redirect: 'manual'});
    assert.equal(redirect.status, 301);
    assert.equal(redirect.headers.get('location'), origin + '/sandbox/?view=all');
  }
  const adminRedirect = await fetch(origin + '/admin', {redirect: 'manual'});
  assert.equal(adminRedirect.status, 301);
  assert.equal(adminRedirect.headers.get('location'), origin + '/admin/');
  for (const path of ['/auth.js', '/missing.dat', '/missing.txt', '/test/bbs.cgi']) {
    assert.notEqual((await fetch(origin + path, {redirect: 'manual'})).status, 301);
  }
  assert.equal((await fetch(origin + '/admin/')).status, 401);
  assert.equal((await get('/admin/')).status, 200);
  assert.equal((await save('/admin/menu', {html: 'cross-origin menu'}, {origin: 'https://other.example'})).status, 303);
  assert.equal(await publicText('/bbsmenu.html'), 'cross-origin menu');
  assert.equal((await get('/admin/board?bbs=../private')).status, 400);
  for (const bbs of ['sandbox', 'destination']) assert.equal((await save(`/admin/board?bbs=${bbs}`, {
    action: 'save',
    head: '<b>rules</b>',
    settings: `BBS_TITLE=${bbs}\nBBS_NONAME_NAME=名無し`
  })).status, 303);
  assert.match(await publicText('/sandbox/head.txt'), /<b>rules<\/b>/);
  assert.match(await publicText('/sandbox/SETTING.TXT'), /BBS_TITLE=sandbox/);
  const legacyMenu = '<HTML><BODY><!-- menu -->板一覧 &amp; custom menu</BODY></HTML>';
  assert.equal((await save('/admin/menu/', {html: legacyMenu})).status, 303);
  const menuResponse = await fetch(origin + '/bbsmenu.html');
  assert.equal(menuResponse.headers.get('content-type'), 'text/html;charset=Shift_JIS');
  assert.equal(new TextDecoder('shift_jis').decode(await menuResponse.arrayBuffer()), legacyMenu);
  const boardResponse = await fetch(origin + '/sandbox/');
  assert.equal(boardResponse.status, 200);
  assert.match(boardResponse.headers.get('content-type'), /text\/html;?\s*charset=UTF-8/i);
  const boardHtml = await boardResponse.text();
  assert.match(boardHtml, /^<!DOCTYPE html>/);
  assert.match(boardHtml, /<title>sandbox<\/title>/);
  assert.match(boardHtml, /<h1[^>]*>sandbox<\/h1>/);
  assert.ok(boardHtml.includes('<b>rules</b>'), 'head.txt は無エスケープで埋め込まれる');
  // island の data-serialized-props（JSON）には属性としてエスケープされた文字列が入る。描画部分だけを見る。
  assert.ok(!boardHtml.replace(/data-serialized-props="[^"]*"/g, '').includes('&lt;b&gt;rules'));
  assert.match(boardHtml, /<script type="module" src="\/static\/client\.js"><\/script>/);
  const clientScriptResponse = await fetch(origin + '/static/client.js');
  assert.equal(clientScriptResponse.status, 200);
  assert.match(clientScriptResponse.headers.get('content-type'), /javascript/);
  const stylesheet = boardHtml.match(/<link[^>]+href="([^"]+\.css)"/);
  assert.ok(stylesheet, '板トップが本番ビルドの CSS を読み込む');
  const stylesheetResponse = await fetch(origin + stylesheet[1]);
  assert.equal(stylesheetResponse.status, 200);
  assert.match(stylesheetResponse.headers.get('content-type'), /text\/css/);
  assert.equal((await save('/admin/access', {rules: '[<><><>deny'})).status, 400);
  assert.equal((await save('/admin/access', {rules: '^sandbox$<><><>deny'})).status, 303);
  const rejectedPost = await postResponse('sandbox', '', 'hello', 'blocked');
  assert.equal(rejectedPost.status, 200);
  assert.equal(rejectedPost.headers.get('content-type'), 'text/html;charset=UTF-8');
  const rejectedHtml = await rejectedPost.text();
  assert.match(rejectedHtml, /アクセス規制/);
  assert.match(rejectedHtml, /<!-- 2ch_X:error -->/);
  assert.match(rejectedHtml, /<script type="module" src="\/static\/client\.js"><\/script>/);
  const monazillaResponse = await fetch(origin + '/test/bbs.cgi', {
    method: 'POST',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      'user-agent': 'Monazilla/1.00 (IntegrationBrowser)',
      referer: 'http://127.0.0.1:8787/',
      'CF-Connecting-IP': '192.0.2.2',
    },
    body: 'bbs=sandbox&key=&MESSAGE=hello&FROM=&mail=&subject=blocked&time=1&submit=%8F%91%82%AB%8D%9E%82%DE',
  });
  assert.equal(monazillaResponse.status, 200);
  assert.equal(monazillaResponse.headers.get('content-type'), 'text/html;charset=Shift_JIS');
  const monazillaHtml = new TextDecoder('shift_jis').decode(await monazillaResponse.arrayBuffer());
  assert.match(monazillaHtml, /<!-- 2ch_X:error -->/);
  assert.match(monazillaHtml, /アクセス規制/);
  assert.equal(await publicText('/sandbox/subject.txt'), '');
  await save('/admin/access', {rules: ''});
  await save('/admin/ngwords', {rules: '<><>forbidden<>deny'});
  assert.match(await post('sandbox', '', 'hello', 'forbidden title'), /NGワード/);
  await save('/admin/ngwords', {rules: ''});
  assert.match(await post('sandbox', '', 'original message', 'thread title'), /書きこみました/);
  const subject = await publicText('/sandbox/subject.txt');
  const key = subject.match(/^(\d{10})\.dat/)[1];
  const listResponse = await fetch(origin + '/sandbox/?type=subback');
  assert.equal(listResponse.status, 200, 'KB-37: スレがある板も描画できる');
  const listHtml = await listResponse.text();
  assert.match(listHtml, /thread title/);
  assert.ok(listHtml.includes(`/test/read.cgi/sandbox/${key}/`));
  assert.match(listHtml, /https:\/\/github.com\/MontereyLangit\/ch3s/);
  assert.match(await (await fetch(origin + '/sandbox/')).text(), /thread title/, '既定はスレ一覧');
  const feedResponse = await fetch(origin + '/sandbox/?type=feed');
  assert.equal(feedResponse.status, 200);
  assert.match(await feedResponse.text(), /Coming Soon…/);
  const path = `/admin/thread?bbs=sandbox&key=${key}`;
  assert.match(await post('sandbox', key, 'second message'), /書きこみました/);
  assert.equal((await save(path, {action: 'stop'})).status, 303);
  assert.match(await post('sandbox', key), /停止/);
  assert.match(await publicText('/sandbox/subject.txt'), /\(2\)/);
  await save(path, {action: 'resume'});
  assert.match(await post('sandbox', key, 'third message'), /書きこみました/);
  assert.equal((await save(path, {
    action: 'editRes',
    number: '2',
    name: '<script>',
    mail: '',
    message: 'edited message'
  })).status, 303);
  assert.match(await publicText(`/sandbox/dat/${key}.dat`), /&lt;script&gt;.*edited message/);
  const editedResponse = await fetch(origin + `/sandbox/dat/${key}.dat`, {
    headers: {
      range: 'bytes=10-',
      'if-modified-since': new Date(Date.now() + 86400000).toUTCString()
    }
  });
  assert.equal(editedResponse.status, 200, '編集後は古い差分キャッシュを使わず全文を返す');
  assert.equal((await save(path, {action: 'deleteRes', number: '1'})).status, 303);
  assert.match(await publicText(`/sandbox/dat/${key}.dat`), /^あぼーん<>あぼーん<>あぼーん<>あぼーん<>thread title/);
  assert.equal((await save(path, {action: 'deleteRes', number: '999'})).status, 400);
  assert.equal((await save(path, {action: 'move', targetBoard: 'destination', targetKey: '1800000001'})).status, 303);
  assert.equal((await fetch(origin + `/sandbox/dat/${key}.dat`)).status, 404);
  assert.equal(await publicText('/sandbox/subject.txt'), '');
  assert.match(await publicText('/destination/subject.txt'), /1800000001.dat<>thread title.* \(3\)/);
  const archive = await (await fetch(origin + `/sandbox/kako/${key.slice(0, 4)}/subject.txt`, {headers: {'if-modified-since': new Date(Date.now() + 86400000).toUTCString()}})).text();
  assert.doesNotMatch(archive, new RegExp(key));
  const moved = '/admin/thread?bbs=destination&key=1800000001';
  assert.match(await (await get(path)).text(), /IP:/);
  assert.equal((await save(moved, {action: 'move', targetBoard: 'sandbox', targetKey: key})).status, 400);
  assert.match(await post('destination', '1800000001', 'after move'), /書きこみました/);
  const concurrent = await Promise.all([postResponse('destination', '1800000001', 'concurrent A'), postResponse('destination', '1800000001', 'concurrent B')]);
  assert.deepEqual(concurrent.map(response => Number(response.headers.get('x-resnum'))).sort(), [5, 6]);
  assert.equal((await save(moved, {action: 'delete'})).status, 303);
  assert.equal((await fetch(origin + '/destination/dat/1800000001.dat')).status, 404);
  assert.equal(await publicText('/destination/subject.txt'), '');
  assert.match(await (await get(moved)).text(), /edited message/);
  assert.match(await (await get(moved)).text(), /IP:/);
  assert.match(await (await get('/admin/threads?bbs=destination')).text(), /1800000001/);
  assert.doesNotMatch(await post('destination', '1800000001'), /<title>書きこみました/);
  await save('/admin/board?bbs=closed-board', {action: 'save', settings: 'BBS_HEISA=checked', head: ''});
  assert.match(await post('closed-board', '', 'message', 'closed board'), /停止/);
});
