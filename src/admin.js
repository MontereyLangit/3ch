import { settingTxtDefault } from './bbs.cgi.js';
import { parseRules } from './moderation.js';

const escape = value => String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const boardName = value => { if (!/^[a-zA-Z0-9_-]{1,80}$/.test(value || '')) throw new Error('板名が不正です。'); return value; };
const threadKey = value => { if (!/^\d{10}$/.test(value || '')) throw new Error('keyは10桁の数字で指定してください。'); return value; };
const input = (name, value = '', label = name) => `<label>${label} <input name="${name}" value="${escape(value)}" required></label>`;
const area = (name, value, label = name) => `<label>${label}<textarea name="${name}" rows="16">${escape(value)}</textarea></label>`;
const form = (body, action, label = '保存') => `<form method="post">${body}<button name="action" value="${action}">${label}</button></form>`;
const textObject = async (env, key, fallback = '') => { const object = await env.ch3www.get(key); return object ? object.text() : fallback; };
const editableText = value => (value || '').replace(/ ?<br\s*\/?>(?: )?/gi, '\n').replace(/&(?:lt|gt|quot|apos|amp);/g, entity => ({ '&lt;': '<', '&gt;': '>', '&quot;': '"', '&apos;': "'", '&amp;': '&' })[entity]);

async function authorized(request, env) {
  if (!env.ADMIN_PASSWORD) return false;
  const credentials = new TextEncoder().encode(`${env.ADMIN_USER || 'admin'}:${env.ADMIN_PASSWORD}`);
  const expected = `Basic ${btoa(Array.from(credentials, byte => String.fromCharCode(byte)).join(''))}`;
  const digest = value => crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  const [a, b] = await Promise.all([digest(expected), digest(request.headers.get('authorization') || '')]);
  return new Uint8Array(a).every((v, i) => v === new Uint8Array(b)[i]);
}

export async function admin(request, env) {
  const headers = { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'referrer-policy': 'no-referrer', 'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'" };
  if (!env.ADMIN_PASSWORD) return new Response('管理者パスワードが未設定です。', { status: 503, headers });
  if (!await authorized(request, env)) return new Response('管理者認証が必要です。', { status: 401, headers: { ...headers, 'www-authenticate': 'Basic realm="3ch admin", charset="UTF-8"' } });
  const url = new URL(request.url);
  if (!['GET', 'POST'].includes(request.method)) return new Response('Method not allowed', { status: 405, headers });
  let content = '', status = 200;
  try {
    if (request.method === 'POST') {
      const origin = request.headers.get('origin');
      if (origin !== url.origin && !(origin === null && request.headers.get('sec-fetch-site') === 'same-origin')) return new Response('送信元が不正です。', { status: 403, headers });
      if (Number(request.headers.get('content-length')) > 262144) throw new Error('入力が大きすぎます。');
      const body = await request.text();
      if (new TextEncoder().encode(body).length > 262144) throw new Error('入力が大きすぎます。');
      const data = Object.fromEntries(new URLSearchParams(body));
      if (url.pathname === '/admin/board') {
        const bbs = boardName(url.searchParams.get('bbs'));
        if (data.action !== 'save') throw new Error('操作が不正です。');
        await env.ch3www.put(`${bbs}/head.txt`, data.head || '');
        await env.ch3www.put(`${bbs}/SETTING.TXT`, data.settings || '');
      } else if (url.pathname === '/admin/access' || url.pathname === '/admin/ngwords') {
        parseRules(data.rules || '');
        await env.ch3www.put(url.pathname.endsWith('access') ? 'deny.cgi' : 'ngwords.cgi', data.rules || '');
      } else if (url.pathname === '/admin/menu') {
        await env.ch3www.put('bbsmenu.html', data.html || '');
      } else if (url.pathname === '/admin/thread') {
        const bbs = boardName(url.searchParams.get('bbs')), key = threadKey(url.searchParams.get('key'));
        if (data.action === 'move') { boardName(data.targetBoard); threadKey(data.targetKey); }
        const stub = env.THREAD_DO.get(env.THREAD_DO.idFromName(`${bbs}/${key}`));
        await stub.inspect(bbs, key);
        await stub.moderate(data.action, { ...data, bbs, key });
      } else throw new Error('操作先が不正です。');
      return new Response(null, { status: 303, headers: { ...headers, location: url.pathname + url.search } });
    }
    if (url.pathname === '/admin/' || url.pathname === '/admin') {
      content = '<h1>管理画面top</h1><p>板名を指定して管理対象を開いてください。</p><form action="/admin/board">' + input('bbs', '', '板名') + '<button>板管理</button></form><form action="/admin/threads">' + input('bbs', '', '板名') + '<button>スレ管理</button></form>';
    } else if (url.pathname === '/admin/board') {
      const bbs = boardName(url.searchParams.get('bbs'));
      content = `<h1>板管理: ${escape(bbs)}</h1>` + form(area('head', await textObject(env, `${bbs}/head.txt`), 'head.txt') + area('settings', await textObject(env, `${bbs}/SETTING.TXT`, settingTxtDefault.replaceAll('<DIR_NAME>', bbs)), 'SETTING.TXT'), 'save');
    } else if (url.pathname === '/admin/threads') {
      const bbs = boardName(url.searchParams.get('bbs'));
      const stub = env.BOARD_DO.get(env.BOARD_DO.idFromName(bbs));
      content = `<h1>スレ管理: ${escape(bbs)}</h1><form action="/admin/thread">${input('bbs', bbs, '板名')}${input('key', '', 'key')}<button>開く</button></form><h2>公開中</h2><ul>`;
      for (const line of (await stub.getSubject()).split('\n').filter(Boolean)) {
        const key = line.split('.')[0];
        content += `<li><a href="/admin/thread?bbs=${bbs}&amp;key=${escape(key)}">${escape(line)}</a></li>`;
      }
      content += '</ul><h2>削除・移動済み（ログ保持）</h2><ul>';
      for (const key of await stub.getRemoved()) content += `<li><a href="/admin/thread?bbs=${bbs}&amp;key=${key}">${key}</a></li>`;
      content += '</ul>';
    } else if (url.pathname === '/admin/thread') {
      const bbs = boardName(url.searchParams.get('bbs')), key = threadKey(url.searchParams.get('key'));
      const data = await env.THREAD_DO.get(env.THREAD_DO.idFromName(`${bbs}/${key}`)).inspect(bbs, key);
      if (!data.dat) throw new Error('スレッドがありません。');
      content = `<h1>スレ管理: ${bbs}/${key}</h1><p><a href="/admin/threads?bbs=${bbs}">一覧へ</a></p><p>状態: ${data.hidden ? '削除・移動済み' : data.stopped ? '停止中' : '公開中'} ${escape(data.movedTo || '')}</p>`;
      content += form('', data.stopped ? 'resume' : 'stop', data.stopped ? 'スレ停止解除' : 'スレ停止');
      content += '<details><summary>スレッド削除（本文・投稿者ログは保持）</summary>' + form('', 'delete', '公開一覧とdatから削除') + '</details>';
      content += '<details><summary>スレッド移動（板移動 / key変更）</summary>' + form(input('targetBoard', bbs, '移動先の板') + input('targetKey', key, '移動先key'), 'move', '移動する') + '</details><h2>レス一覧・編集・削除</h2>';
      data.dat.trimEnd().split('\n').forEach((line, i) => {
        const [name, mail, date, message] = line.split('<>');
        content += `<details><summary>${i + 1}: ${escape(name)} ${escape(date)}</summary><pre>${escape(message)}</pre>`;
        content += form(`<input type="hidden" name="number" value="${i + 1}">` + input('name', editableText(name), '名前') + `<label>メール<input name="mail" value="${escape(editableText(mail))}"></label>` + area('message', editableText(message), '本文（テキスト入力）'), 'editRes', 'レスを編集');
        content += form(`<input type="hidden" name="number" value="${i + 1}">`, 'deleteRes', 'レスをあぼーん化') + '</details>';
      });
      content += `<h2>投稿者ログ（削除後も保持）</h2><pre>${escape(data.log || '')}</pre><h2>datログ</h2><pre>${escape(data.dat)}</pre>`;
      const oldLog = data.legacyLog;
      if (oldLog) content += `<h2>旧形式ログ</h2><pre>${escape(oldLog)}</pre>`;
    } else if (['/admin/access', '/admin/ngwords'].includes(url.pathname)) {
      const access = url.pathname.endsWith('access');
      content = `<h1>${access ? 'アクセス規制登録' : 'NGワード設定'}</h1><p>1行に「板の正規表現&lt;&gt;投稿者情報の正規表現&lt;&gt;NGワードの正規表現&lt;&gt;動作」。空欄は全対象。# で始まる行はコメントです。</p><p>動作: deny（拒否）、mark（注意表示）、TateDame（スレ立て禁止）、md5（拒否）。対象例: IP:192\\.0\\.2\\.1(?: |$)、AS1234-。NGは名前・メール・本文・タイトルに適用します。</p><p>例: ${access ? '^test$&lt;&gt;IP:192\\.0\\.2\\.1(?: |$)&lt;&gt;&lt;&gt;deny' : '^test$&lt;&gt;&lt;&gt;禁止語&lt;&gt;deny'}</p>` + form(area('rules', await textObject(env, access ? 'deny.cgi' : 'ngwords.cgi'), '規制ルール'), 'save');
    } else if (url.pathname === '/admin/menu') {
      content = '<h1>板一覧（bbsmenu.html）編集</h1><p>公開するHTMLを入力してください。保存後はこの内容を使用します。</p>' + form(area('html', await textObject(env, 'bbsmenu.html'), 'bbsmenu.html'), 'save');
    } else { status = 404; content = '<h1>ページがありません。</h1>'; }
  } catch (e) { status = 400; content = `<h1>処理できませんでした</h1><p>${escape(e.message)}</p><p>ブラウザーで戻り、入力内容を確認してください。</p>`; }
  return new Response(`<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>3ch 管理画面</title><style>body{font:16px sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172435;background:#f6f8fb}nav{display:flex;gap:1rem;flex-wrap:wrap}form,details{background:white;padding:1rem;margin:1rem 0;border:1px solid #ccd4de;border-radius:6px}label{display:block;margin:.5rem 0}textarea{display:block;width:100%;box-sizing:border-box;font:14px monospace}input{padding:.4rem}button{padding:.6rem 1rem;cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere}summary{cursor:pointer}</style><nav><a href="/admin/">管理画面top</a><a href="/admin/access">アクセス規制</a><a href="/admin/ngwords">NGワード</a><a href="/admin/menu">板一覧編集</a></nav>${content}</html>`, { status, headers });
}
