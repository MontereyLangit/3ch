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
  // ASSETS.fetch の応答にも変更可能なヘッダを持つコピーを返す。
  const rootResponse = await fetch(origin + '/');
  assert.equal(rootResponse.status, 200);
  assert.match(rootResponse.headers.get('accept-ch'), /Sec-CH-UA-Model/);
  assert.match(await rootResponse.text(), /<!doctype html>/i);
  const authorization = 'Basic ' + Buffer.from('admin:local-integration-password').toString('base64');
  const get = path => fetch(origin + path, {headers: {authorization}});
  // 管理画面（SPA）からの変更と同じヘッダ。headers で Origin などを上書きできる
  const api = (path, method = 'GET', body, headers = {}) => fetch(origin + '/admin/api' + path, {
    method,
    headers: {
      authorization,
      ...(body === undefined ? {} : {'content-type': 'application/json', origin, 'x-ch3s-admin': '1'}),
      ...headers
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = async (path) => (await api(path)).json();
  // 板設定の画面と同じく、読み込んだ値に fields を重ねて保存する
  const saveSettings = async (bbs, fields, head) => {
    const view = await json(`/boards/${bbs}/settings/`);
    const explicit = Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, {value, explicit: true}]));
    return api(`/boards/${bbs}/settings`, 'PUT', {
      fields: {...view.fields, ...explicit}, extra: view.extra, head: head ?? view.head, settingsEtag: view.settingsEtag, headEtag: view.headEtag
    });
  };
  const saveFile = async (path, text) => api(path, 'PUT', {text, etag: (await json(path + '/')).etag});
  const action = (bbs, key, body) => api(`/boards/${bbs}/threads/${key}/actions`, 'POST', body);
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
  // 管理画面の SPA: 起動ページと資産は Basic 認証の後にだけ返す（run_worker_first）
  for (const path of ['/admin/', '/admin/index.html', '/admin/boards/sandbox/', '/admin/api/boards/']) {
    assert.equal((await fetch(origin + path)).status, 401, path);
  }
  const shell = await get('/admin/');
  assert.equal(shell.status, 200);
  assert.equal(shell.headers.get('cache-control'), 'no-store');
  assert.equal(shell.headers.get('x-content-type-options'), 'nosniff');
  assert.equal(shell.headers.get('referrer-policy'), 'no-referrer');
  const csp = shell.headers.get('content-security-policy');
  assert.match(csp, /default-src 'none'; script-src 'self'; style-src 'self';/);
  assert.match(csp, /frame-ancestors 'none'/);
  const shellHtml = await shell.text();
  assert.match(shellHtml, /<div id="admin-root"><\/div>/);
  assert.doesNotMatch(shellHtml, /<script(?![^>]*\ssrc=)[^>]*>|<style|\sstyle=/, 'CSP に触れるインラインのスクリプト・スタイルを持たない');
  const scripts = [...shellHtml.matchAll(/<script[^>]+src="([^"]+)"/g)].map(m => m[1]);
  const styles = [...shellHtml.matchAll(/<link[^>]+href="([^"]+\.css)"/g)].map(m => m[1]);
  assert.equal(scripts.length, 1);
  assert.equal(styles.length, 1);
  for (const asset of [...scripts, ...styles]) {
    assert.match(asset, /^\/admin\/assets\//);
    assert.equal((await fetch(origin + asset)).status, 401, `${asset} は認証なしで取れない`);
    const response = await get(asset);
    assert.equal(response.status, 200, asset);
    assert.equal(response.headers.get('cache-control'), 'private, max-age=31536000, immutable');
    assert.match(response.headers.get('content-type'), asset.endsWith('.js') ? /javascript/ : /text\/css/);
  }
  assert.equal(await (await get('/admin/boards/sandbox/threads/1800000000/')).text(), shellHtml, '画面の URL はどれも同じ起動ページ');
  // dist/admin/ は /admin 以外の書き方では取れない
  for (const path of ['/%61dmin/index.html', '/ADMIN/index.html', '//admin/index.html', '/static/../admin/index.html', '/admin%2Findex.html',
    '/%2Fadmin/index.html', '/admin/./index.html', '/.%2Fadmin/index.html', scripts[0].replace('/admin/', '/%61dmin/')]) {
    const response = await fetch(origin + path, {redirect: 'manual'});
    const body = await response.text();
    assert.ok(!body.includes('admin-root') && !(response.status === 200 && path.endsWith('.js')), `${path} -> ${response.status}`);
  }
  // 他オリジン・独自ヘッダの無い変更は受け付けない
  const crossOrigin = await api('/menu', 'PUT', {text: 'cross-origin menu', etag: null}, {origin: 'https://other.example'});
  assert.equal(crossOrigin.status, 403);
  assert.equal((await crossOrigin.json()).code, 'admin:origin');
  assert.equal((await api('/menu', 'PUT', {text: 'no marker', etag: null}, {'x-ch3s-admin': ''})).status, 403);
  assert.equal((await fetch(origin + '/bbsmenu.html')).status, 404, '拒否した変更は保存しない');
  assert.equal((await api('/boards/..%2Fprivate/settings/')).status, 400);
  for (const bbs of ['sandbox', 'destination']) {
    // 同じ IP から続けて投稿するので連投規制を外す
    const saved = await saveSettings(bbs, {BBS_TITLE: bbs, BBS_NONAME_NAME: '名無し', BBS_FR_LEVEL: 'off'}, '<b>rules</b>');
    assert.equal(saved.status, 200, bbs);
  }
  assert.match(await publicText('/sandbox/head.txt'), /<b>rules<\/b>/);
  assert.match(await publicText('/sandbox/SETTING.TXT'), /BBS_TITLE=sandbox/);
  const legacyMenu = '<HTML><BODY><!-- menu -->板一覧 &amp; custom menu</BODY></HTML>';
  assert.equal((await saveFile('/menu', legacyMenu)).status, 200);
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
  assert.equal((await saveFile('/rules/access', '[<><><>deny')).status, 400);
  assert.equal((await saveFile('/rules/access', '^sandbox$<><><>deny')).status, 200);
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
  assert.equal((await saveFile('/rules/access', '')).status, 200);
  assert.equal((await saveFile('/rules/ngwords', '<><>forbidden<>deny')).status, 200);
  assert.match(await post('sandbox', '', 'hello', 'forbidden title'), /NGワード/);
  assert.equal((await saveFile('/rules/ngwords', '')).status, 200);
  assert.match(await post('sandbox', '', 'original message', 'thread title'), /書きこみました/);
  const subject = await publicText('/sandbox/subject.txt');
  const key = subject.match(/^(\d{10})\.dat/)[1];
  const listResponse = await fetch(origin + '/sandbox/?type=subback');
  assert.equal(listResponse.status, 200, 'KB-37: スレがある板も描画できる');
  const listHtml = await listResponse.text();
  assert.match(listHtml, /thread title/);
  assert.ok(listHtml.includes(`/test/read.cgi/sandbox/${key}/`));
  assert.match(listHtml, /https:\/\/github.com\/MontereyLangit\/3ch/);
  assert.match(await (await fetch(origin + '/sandbox/')).text(), /thread title/, '既定はスレ一覧');
  const feedResponse = await fetch(origin + '/sandbox/?type=feed');
  assert.equal(feedResponse.status, 200);
  assert.match(await feedResponse.text(), /Coming Soon…/);
  const creationSettings = {
    BBS_THREAD_TATESUGI: '3', BBS_THREAD_TATESUGI_RESET: '180000',
    BBS_THREAD_JUNBAN: '1', BBS_THREAD_JUNBAN_RESET: '180000', BBS_THREAD_JUNBAN_AS_ONLY: 'AS2516, 4713',
  };
  assert.equal((await saveSettings('sandbox', creationSettings)).status, 200);
  const invalidCreation = await saveSettings('sandbox', { ...creationSettings, BBS_THREAD_JUNBAN_AS_ONLY: 'invalid' });
  assert.equal(invalidCreation.status, 400);
  assert.ok((await invalidCreation.json()).fields['fields.BBS_THREAD_JUNBAN_AS_ONLY']);
  const savedCreationSettings = await publicText('/sandbox/SETTING.TXT');
  assert.match(savedCreationSettings, /BBS_THREAD_JUNBAN_AS_ONLY=AS2516, 4713/);
  assert.equal((await saveSettings('sandbox', { ...creationSettings, BBS_THREAD_JUNBAN_AS_ONLY: '2516\nBBS_TITLE=injected' })).status, 400);
  assert.equal(await publicText('/sandbox/SETTING.TXT'), savedCreationSettings);
  assert.match(await publicText('/sandbox/SETTING.TXT'), /BBS_TITLE=sandbox/);
  await new Promise(resolve => setTimeout(resolve, 1100));
  const rateLimited = await postResponse('sandbox', '', 'another message', 'another title');
  assert.equal(rateLimited.status, 200);
  assert.equal(rateLimited.headers.get('x-threadkey'), null);
  const rateLimitedHtml = await rateLimited.text();
  assert.match(rateLimitedHtml, /スレッドを立てすぎ/);
  assert.match(rateLimitedHtml, /<!-- 2ch_X:error -->/);
  assert.doesNotMatch(rateLimitedHtml, /運用情報板|ログファイルに書けませんでした/);
  assert.equal(await publicText('/sandbox/subject.txt'), subject);
  assert.match(await post('sandbox', key, 'second message'), /書きこみました/);
  assert.equal((await action('sandbox', key, {action: 'stop'})).status, 200);
  assert.match(await post('sandbox', key), /停止/);
  assert.match(await publicText('/sandbox/subject.txt'), /\(2\)/);
  assert.equal((await action('sandbox', key, {action: 'resume'})).status, 200);
  assert.match(await post('sandbox', key, 'third message'), /書きこみました/);
  assert.equal((await action('sandbox', key, {
    action: 'editRes',
    number: 2,
    name: '<script>',
    mail: '',
    message: 'edited message'
  })).status, 200);
  assert.match(await publicText(`/sandbox/dat/${key}.dat`), /&lt;script&gt;.*edited message/);
  const editedResponse = await fetch(origin + `/sandbox/dat/${key}.dat`, {
    headers: {
      range: 'bytes=10-',
      'if-modified-since': new Date(Date.now() + 86400000).toUTCString()
    }
  });
  assert.equal(editedResponse.status, 200, '編集後は古い差分キャッシュを使わず全文を返す');
  assert.equal((await action('sandbox', key, {action: 'deleteRes', number: 1})).status, 200);
  assert.match(await publicText(`/sandbox/dat/${key}.dat`), /^あぼーん<>あぼーん<>あぼーん<>あぼーん<>thread title/);
  assert.equal((await action('sandbox', key, {action: 'deleteRes', number: 999})).status, 409);
  assert.equal((await action('sandbox', key, {action: 'move', targetBoard: 'destination', targetKey: '1800000001'})).status, 200);
  assert.equal((await fetch(origin + `/sandbox/dat/${key}.dat`)).status, 404);
  assert.equal(await publicText('/sandbox/subject.txt'), '');
  assert.match(await publicText('/destination/subject.txt'), /1800000001.dat<>thread title.* \(3\)/);
  const archive = await (await fetch(origin + `/sandbox/kako/${key.slice(0, 4)}/subject.txt`, {headers: {'if-modified-since': new Date(Date.now() + 86400000).toUTCString()}})).text();
  assert.doesNotMatch(archive, new RegExp(key));
  assert.match((await json(`/boards/sandbox/threads/${key}/`)).log, /IP:/);
  const refusedMove = await action('destination', '1800000001', {action: 'move', targetBoard: 'sandbox', targetKey: key});
  assert.equal(refusedMove.status, 409);
  assert.equal((await refusedMove.json()).code, 'admin:thread-state');
  assert.match(await post('destination', '1800000001', 'after move'), /書きこみました/);
  const concurrent = await Promise.all([postResponse('destination', '1800000001', 'concurrent A'), postResponse('destination', '1800000001', 'concurrent B')]);
  assert.deepEqual(concurrent.map(response => Number(response.headers.get('x-resnum'))).sort(), [5, 6]);
  assert.equal((await action('destination', '1800000001', {action: 'delete'})).status, 200);
  assert.equal((await fetch(origin + '/destination/dat/1800000001.dat')).status, 404);
  assert.equal(await publicText('/destination/subject.txt'), '');
  const movedDetail = await json('/boards/destination/threads/1800000001/');
  assert.equal(movedDetail.hidden, true);
  assert.match(movedDetail.dat, /edited message/);
  assert.match(movedDetail.log, /IP:/);
  assert.ok((await json('/boards/destination/threads/')).removed.includes('1800000001'));
  assert.doesNotMatch(await post('destination', '1800000001'), /<title>書きこみました/);
  assert.equal((await saveSettings('closedboard', {BBS_HEISA: 'checked'}, '')).status, 200);
  assert.match(await post('closedboard', '', 'message', 'closed board'), /停止/);

  // 管理 API（Basic 認証のまま）
  const unauthorized = await fetch(origin + '/admin/api/boards');
  assert.equal(unauthorized.status, 401);
  assert.equal((await unauthorized.json()).code, 'admin:unauthorized');
  const boards = await (await api('/boards')).json();
  for (const bbs of ['sandbox', 'destination', 'closedboard']) assert.ok(boards.boards.includes(bbs), bbs);
  const apiBoard = await (await api('/boards/apiboard/settings')).json();
  assert.equal(apiBoard.settingsEtag, null);
  assert.equal(apiBoard.fields.BBS_TITLE.value, 'apiboard＠3ch掲示板');
  const settingsBody = (view, fields, head) => ({
    fields: {...view.fields, ...fields}, extra: view.extra, head, settingsEtag: view.settingsEtag, headEtag: view.headEtag
  });
  const created = await api('/boards/apiboard/settings', 'PUT', settingsBody(apiBoard, {
    BBS_TITLE: {value: 'API板', explicit: true}, BBS_FR_LEVEL: {value: 'off', explicit: true}
  }, '<i>api</i>'));
  assert.equal(created.status, 200);
  assert.equal((await created.json()).partial, false);
  assert.match(await publicText('/apiboard/SETTING.TXT'), /BBS_TITLE=API板/);
  assert.match(await publicText('/apiboard/head.txt'), /<i>api<\/i>/);
  const stale = await api('/boards/apiboard/settings', 'PUT', settingsBody(apiBoard, {}, ''));
  assert.equal(stale.status, 409, '読込時に無かった SETTING.TXT が作られていれば競合');
  assert.equal((await stale.json()).code, 'admin:conflict');
  const invalid = await api('/boards/apiboard/settings', 'PUT', settingsBody(await (await api('/boards/apiboard/settings')).json(), {
    BBS_TITLE: {value: 'x\nBBS_HEISA=checked', explicit: true}
  }, ''));
  assert.equal(invalid.status, 400);
  assert.ok((await invalid.json()).fields['fields.BBS_TITLE']);
  assert.doesNotMatch(await publicText('/apiboard/SETTING.TXT'), /BBS_HEISA=checked/);
  const rules = await (await api('/rules/ngwords')).json();
  assert.equal((await api('/rules/ngwords', 'PUT', {text: '[<><><>deny', etag: rules.etag})).status, 400);
  const savedRules = await api('/rules/ngwords', 'PUT', {text: '<><>api-forbidden<>deny', etag: rules.etag});
  assert.equal(savedRules.status, 200);
  assert.match(await post('apiboard', '', 'hello', 'api-forbidden'), /NGワード/);
  assert.equal((await api('/rules/ngwords', 'PUT', {text: '', etag: rules.etag})).status, 409);
  assert.equal((await api('/rules/ngwords', 'PUT', {text: '', etag: (await savedRules.json()).etag})).status, 200);
  const menu = await (await api('/menu')).json();
  assert.equal(menu.text, legacyMenu);
  assert.equal((await api('/menu', 'PUT', {text: 'api menu', etag: menu.etag})).status, 200);
  assert.equal(await publicText('/bbsmenu.html'), 'api menu');
  const apiPost = await postResponse('apiboard', '', 'api first', 'api thread');
  assert.match(await apiPost.text(), /書きこみました/);
  const apiThreads = await (await api('/boards/apiboard/threads')).json();
  assert.equal(apiThreads.subject.length, 1);
  const apiKey = apiThreads.subject[0].key;
  const actions = `/boards/apiboard/threads/${apiKey}/actions`;
  assert.equal((await api(actions, 'POST', {action: 'stop'})).status, 200);
  assert.match(await post('apiboard', apiKey, 'while stopped'), /停止/);
  assert.equal((await api(actions, 'POST', {action: 'resume'})).status, 200);
  assert.equal((await api(actions, 'POST', {action: 'editRes', number: 1, name: '<b>', mail: '', message: 'api edited'})).status, 200);
  const detail = await (await api(`/boards/apiboard/threads/${apiKey}`)).json();
  assert.equal(detail.res[0].name, '&lt;b&gt;');
  assert.equal(detail.res[0].edit.name, '<b>');
  assert.match(detail.log, /IP:/);
  const refused = await api(actions, 'POST', {action: 'deleteRes', number: 999});
  assert.equal(refused.status, 409);
  assert.equal((await refused.json()).code, 'admin:thread-state');
  assert.equal((await api(actions, 'POST', {action: 'delete'})).status, 200);
  assert.equal((await fetch(origin + `/apiboard/dat/${apiKey}.dat`)).status, 404);
  const formPost = await fetch(origin + '/admin/api' + actions, {
    method: 'POST', headers: {authorization, 'content-type': 'application/x-www-form-urlencoded'}, body: 'action=stop'
  });
  assert.equal(formPost.status, 403, 'フォーム送信は Origin と独自ヘッダを満たせない');
  const sameOriginForm = await fetch(origin + '/admin/api' + actions, {
    method: 'POST', headers: {authorization, origin, 'x-ch3s-admin': '1', 'content-type': 'application/x-www-form-urlencoded'}, body: 'action=stop'
  });
  assert.equal(sameOriginForm.status, 415);
});
