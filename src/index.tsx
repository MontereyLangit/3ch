import { Hono } from 'hono';
import { getCookie } from 'hono/cookie';
import { createHtmlResponse, createTextResponse } from '@/services/service';
import { PostService } from '@/services/PostService';
import { ViewPost } from '@/views/post';
import { authenticateUser, prepareAuthJs } from '@/services/AuthService';
import { AuthViewModel } from '@/model/view/AuthViewModel';
import { viewAuthJs } from '@/views/auth_js';
import { View404 } from '@/views/404';
import { ViewError } from '@/views/error';
import { ViewBbs } from '@/views/bbs.template';
import { ViewBoard } from '@/views/board';
import { View3chiko } from '@/views/3chiko.template';
import { BbsViewModel } from '@/model/view/BbsViewModel';
import { ViewAuth } from '@/views/auth';
import { PostProcessModel } from '@/model/PostProcessModel';
import { Poster } from './model/user/Poster';
import { PostViewModel } from './model/view/PostViewModel';
import { Bbs } from '@/durable_objects/Bbs';
import { Thread } from '@/durable_objects/Thread';
import { User } from '@/durable_objects/User';

const app = new Hono<{ Bindings: CloudflareBindings }>();

app.onError((err, c) => {
    console.error(err);
    return c.html(<ViewError model={{ status: 500, statusText: "Internal Server Error", errMsg: err.message }} />, 500);
});

app.notFound((c) => {
    const url = new URL(c.req.url);
    return c.html(<View404 model={{ pathname: url.pathname }} />, 404);
});

// POST /test/subbbs.cgi, /test/subbbs.cgi 書き込み
app.on('POST', ['/test/bbs.cgi', '/test/subbbs.cgi'], async (c) => {
    const processModel = new PostProcessModel();
    const poster = new Poster();
    const viewModel = new PostViewModel();
    const postService = new PostService(c, processModel, poster);

    // モデル準備
    postService.preparePostProcessModel();
    postService.preparePosterInfo();
    await postService.preparePostForm();
    
    // 書き込み実行（エラー時は中断）
    await postService.post();

    // エラーがある又は書き込み確認画面の場合
    if (processModel.hasErrors()) {
        postService.prepareViewModel(viewModel);
        return c.html(<ViewPost model={viewModel} />);
    }
    
    postService.prepareViewModel(viewModel);
    return c.html(<ViewPost model={viewModel} />);
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


// POST /api/auth 認証API
app.post('/api/auth', async (c) => {
    const model = new AuthViewModel();
    await authenticateUser(c, model);
    return c.text(model.body, model.status, model.headers);
});

// GET /auth/ 専ブラユーザー用認証ページ


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
    // bbsDescriptionはheadTextから取得
    const headText = await bbs.getHeadText();

    // モデルを作成
    const model = new BbsViewModel(bbsName);
    model.bbsTitle = bbsTitle;
    model.bbsDescription = headText;
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

// =========================================================
// !!! CRITICAL STEP: EXPORT THE DURABLE OBJECT CLASSES !!!
// =========================================================
export { Bbs };
export { Thread };
export { User };
