import { Hono } from 'hono';
/// <reference types="@cloudflare/workers-types" />
import { createHtmlResponse, createTextResponse } from './services/service.js';
import { post as postBbsCgi } from './services/bbs_cgi_service.js';
import { bbsCgiViewModel } from './model/bbs_cgi_view_model.js';
import { viewBbsCgiHtml } from './views/bbs_cgi.template.js';
import { prepareAuthJs, authenticateUser } from './services/auth_service.js';
import { viewAuthHtml } from './views/auth.template.js';
import { authViewModel } from './model/auth_view_model.js';
import { viewAuthJs } from './views/auth.js';
import { view404Html } from './views/404.template.js';
import { viewErrorHtml } from './views/error.template.js';

// Cloudflare Bindings の型定義
type Bindings = {
    BBS: DurableObjectNamespace;
    THREAD: DurableObjectNamespace;
    USER: DurableObjectNamespace;
    TEST: KVNamespace;
    CAP: KVNamespace;
    salt: string;
    ENVIRONMENT: string;
}

const app = new Hono<{ Bindings: Bindings }>();

app.onError((err, c) => {
    console.error(err);
    return createHtmlResponse(viewErrorHtml, { status: 500, statusText: "Internal Server Error", errMsg: err.message });
});

app.notFound((c) => {
    const url = new URL(c.req.url);
    return createHtmlResponse(view404Html, { status: 404, pathname: url.pathname });
});

// POST /test/bbs.cgi, /test/subbbs.cgi
app.post('/test/bbs.cgi', async (c) => {
    await postBbsCgi(c.req.raw, c.env, c.executionCtx, bbsCgiViewModel);
    return createHtmlResponse(viewBbsCgiHtml, bbsCgiViewModel);
});
app.post('/test/subbbs.cgi', async (c) => {
    await postBbsCgi(c.req.raw, c.env, c.executionCtx, bbsCgiViewModel);
    return createHtmlResponse(viewBbsCgiHtml, bbsCgiViewModel);
});

// GET /api/auth.js
app.get('/api/auth.js', async (c) => {
    await prepareAuthJs(c.req.raw, c.env, c.executionCtx, authViewModel);
    const script = viewAuthJs(authViewModel);
    return new Response(script, {
        headers: { "Content-Type": "application/javascript; charset=utf-8" }
    });
});

// GET /auth
app.get('/auth', (c) => {
    return createHtmlResponse(viewAuthHtml, authViewModel);
});

// POST /api/auth
app.post('/api/auth', async (c) => {
    await authenticateUser(c.req.raw, c.env, authViewModel);

    if (authViewModel.error) {
        return new Response("認証失敗: " + authViewModel.error, {
            status: 400,
            headers: { "Content-Type": "text/plain; charset=utf-8" }
        });
    }

    return new Response("認証成功", {
        status: 200,
        headers: { "Content-Type": "text/plain; charset=utf-8" }
    });
});

// GET /{bbs}/{key}.dat
app.get('/:bbsName/:key{[0-9]+.dat}', async (c) => {
    const { bbsName, key } = c.req.param();
    const thread = c.env.THREAD.getByName(bbsName + key.replace('.dat', ''));
    const datText = await thread.getDat();

    if (datText === null) {
        return new Response("404 Not Found", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
    }

    return createTextResponse(datText, "Shift-JIS");
});

// GET /{bbs}/subject.txt
app.get('/:bbsName/subject.txt', async (c) => {
    const { bbsName } = c.req.param();
    const bbs = c.env.BBS.getByName(bbsName);
    const subjectText = await bbs.getSubjectText();
    return createTextResponse(subjectText, "Shift-JIS");
});

// 他の .txt ファイルのルートも同様に追加...
// GET /{bbs}/head.txt, GET /{bbs}/SETTING.TXT

export default app;
