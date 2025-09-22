import { Hono } from 'hono';
/// <reference types="@cloudflare/workers-types" />
import { createHtmlResponse, createTextResponse } from './services/service';
import { post as postBbsCgi } from './services/bbs_cgi_service';
import { BbsCgiViewModel } from './model/bbs_cgi_view_model';
import { ViewBbsCgi } from './views/bbs_cgi';
import { prepareAuthJs } from './services/auth_service';
import { AuthViewModel } from './model/auth_view_model';
import { viewAuthJs } from './views/auth_js';
import { View404 } from './views/404';
import { ViewError } from './views/error';
import { ViewBbs } from './views/bbs.template';
import { ViewBoard } from './views/board';
import { View3chiko } from './views/3chiko';
import { getCookie } from 'hono/cookie'
import { Bbs } from './durable_objects/bbs';
import { Thread } from './durable_objects/thread';
import { BbsViewModel } from './model/bbs_modes';
import { ViewAuth } from './views/auth';

const app = new Hono<{ Bindings: CloudflareBindings }>();

app.onError((err, c) => {
    console.error(err);
    return c.html(<ViewError model={{ status: 500, statusText: "Internal Server Error", errMsg: err.message }} />, 500);
});

app.notFound((c) => {
    const url = new URL(c.req.url);
    return c.html(<View404 model={{ pathname: url.pathname }} />, 404);
});

// POST /test/subbbs.cgi, /test/subbbs.cgi 書き込みAPI
app.on('POST', ['/test/bbs.cgi', '/test/subbbs.cgi'], async (c) => {
    const model = new BbsCgiViewModel();
    await postBbsCgi(c, model);
    return c.html(<ViewBbsCgi model={model} />);
});

// GET /api/auth.js 認証用クライアントjs
app.get('/api/auth.js', async (c) => {
    const model = new AuthViewModel();
    await prepareAuthJs(c, model);
    const script = viewAuthJs(model);
    return new Response(script, {
        headers: { "Content-Type": "application/javascript; charset=utf-8" }
    });
});

// GET /auth 認証ページ
app.get('/auth', (c) => {
    const model = new AuthViewModel();
    return c.html(<ViewAuth model={model} />);
});


// GET /test/read.cgi/{bbs}/{key}/ スレッド
app.get('/test/read.cgi/:bbsName/:keyName/', async (c) => {
    const { bbsName, keyName } = c.req.param();
    const webclient = getCookie(c, 'webclient');
    const url = new URL(c.req.url);
    const webclientQuery = url.searchParams.get('webclient');

    if (webclient === '3chiko' || webclientQuery === '3chiko') {
        return c.html(<View3chiko />);
    }

    // TODO: Web版の実装
    return new Response("404 Not Found", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
});

// GET /<bbs>/ 専ブラ用板トップ
app.get('/:bbsName/', async (c) => {
    const { bbsName } = c.req.param();
    const webclient = getCookie(c, 'webclient');
    const url = new URL(c.req.url);
    const webclientQuery = url.searchParams.get('webclient');

    if (webclient === '3chiko' || webclientQuery === '3chiko') {
        return c.html(<View3chiko />);
    }

    const bbs = c.env.BBS.getByName(bbsName) as unknown as Bbs;
    const settingText = await bbs.getSettingText();
    const bbsTitle = settingText.match(/BBS_TITLE=(.*)/)?.[1] || bbsName;

    // モデルを作成
    const model = new BbsViewModel(bbsName);
    model.bbsTitle = bbsTitle;
    model.isSJIS = true; // 専ブラ用はShift_JISで表示

    // createHtmlResponse を使用してShift_JISでレスポンスを返す
    return createHtmlResponse(ViewBbs, model);
});

// GET /boards/<bbs> Web版板トップ
app.get('/boards/:bbsName/', async (c) => {
    const { bbsName } = c.req.param();
    const bbs = c.env.BBS.getByName(bbsName) as unknown as Bbs;
    const settingText = await bbs.getSettingText();
    const bbsTitle = settingText.match(/BBS_TITLE=(.*)/)?.[1] || bbsName;
    const bbsDescription = settingText.match(/BBS_EXPLAIN=(.*)/)?.[1] || '';

    // モデルを作成
    const model = new BbsViewModel(bbsName);
    model.bbsTitle = bbsTitle;
    model.bbsDescription = bbsDescription;
    model.isSJIS = false; // Web版はUTF-8で表示
    // ToDO: スレッド一覧の取得

    return c.html(<ViewBoard model={model} />);
});

// GET /{bbs}/{key}.dat
app.get('/:bbsName/:key{[0-9]+.dat}', async (c) => {
    const { bbsName, key } = c.req.param();
    const thread = c.env.THREAD.getByName(bbsName + key.replace('.dat', '')) as unknown as Thread;
    const datText = await thread.getDat();

    if (datText === null) {
        return new Response("404 Not Found", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
    }

    return createTextResponse(datText, "Shift_JIS");
});

// GET /{bbs}/subject.txt
app.get('/:bbsName/subject.txt', async (c) => {
    const { bbsName } = c.req.param();
    const bbs = c.env.BBS.getByName(bbsName) as unknown as Bbs;
    const subjectText = await bbs.getSubjectText();
    return createTextResponse(subjectText, "Shift_JIS");
});

// GET /{bbs}/head.txt
app.get('/:bbsName/head.txt', async (c) => {
    const { bbsName } = c.req.param();
    const bbs = c.env.BBS.getByName(bbsName) as unknown as Bbs;
    const headText = await bbs.getHeadText();
    return createTextResponse(headText, "Shift_JIS");
});

// GET /{bbs}/SETTING.TXT
app.get('/:bbsName/SETTING.TXT', async (c) => {
    const { bbsName } = c.req.param();
    const bbs = c.env.BBS.getByName(bbsName) as unknown as Bbs;
    const settingText = await bbs.getSettingText();
    // <DIR_NAME> を置換
    return createTextResponse(settingText.replace(/<DIR_NAME>/g, bbsName), "Shift_JIS");
});

export default app;
