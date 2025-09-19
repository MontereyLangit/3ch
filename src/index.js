////@ts-check
import { createHtmlResponse, createTextResponse } from "./services/service.js";
import { post } from "./services/bbs_cgi_service.js";
import { bbsCgiViewModel } from "./model/bbs_cgi_view_model.js";
import { viewBbsCgiHtml } from "./views/bbs_cgi.template.js";
import { prepareAuthJs, authenticateUser } from "./services/auth_service.js";
import { viewAuthHtml } from "./views/auth.template.js";
import { authViewModel } from "./model/auth_view_model.js";
import { viewAuthJs } from "./views/auth.js";
import { view404Html } from "./views/404.template.js";
import { viewErrorHtml } from "./views/error.template.js";

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        const pathname = url.pathname;

        try {
            // POST /test/bbs.cgi, /test/subbbs.cgi
            if (request.method === "POST" &&
                    (pathname === "/test/bbs.cgi" || pathname === "/test/subbbs.cgi")) {
                await post(request, env, ctx, bbsCgiViewModel);
                
                return createHtmlResponse(viewBbsCgiHtml, bbsCgiViewModel);
            }

            // GET /api/auth.js
            if (request.method === "GET" && pathname === "/api/auth.js") {
                await prepareAuthJs(request, env, ctx, authViewModel);
                const script = viewAuthJs(authViewModel);
                return new Response(script, {
                    headers: { "Content-Type": "application/javascript; charset=utf-8" }
                });
            }

            // GET /auth
            if (request.method === "GET" && pathname === "/auth") {
                return createHtmlResponse(viewAuthHtml, authViewModel);
            }

            // POST /api/auth
            if (request.method === "POST" && pathname === "/api/auth") {
                await authenticateUser(request, env, authViewModel);

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
            }

            // GET /{bbs}/{key}.dat
            const datMatch = pathname.match(/^\/([^/]+)\/([0-9]+)\.dat$/);
            if (request.method === "GET" && datMatch) {
                const [, bbsName, key] = datMatch;
                const thread = env.THREAD.getByName(bbsName + key);
                const datText = await thread.getDat();

                if (datText === null) {
                    return new Response("404 Not Found", {
                        status: 404,
                        headers: { "Content-Type": "text/plain; charset=utf-8" }
                    });
                }

                return createTextResponse(datText, "Shift-JIS");
            }

            // GET /{bbs}/subject.txt
            const subjectMatch = pathname.match(/^\/([^/]+)\/subject\.txt$/);
            if (request.method === "GET" && subjectMatch) {
                const [, bbsName] = subjectMatch;
                const bbs = env.BBS.getByName(bbsName);
                const subjectText = await bbs.getSubjectText();

                return createTextResponse(subjectText, "Shift-JIS");
            }

            // GET /{bbs}/head.txt
            const headMatch = pathname.match(/^\/([^/]+)\/head\.txt$/);
            if (request.method === "GET" && headMatch) {
                const [, bbsName] = headMatch;
                const bbs = env.BBS.getByName(bbsName);
                const headText = await bbs.getHeadText();

                return createTextResponse(headText, "Shift-JIS");
            }

            // GET /{bbs}/SETTING.TXT
            const settingMatch = pathname.match(/^\/([^/]+)\/SETTING\.TXT$/i);
            if (request.method === "GET" && settingMatch) {
                const [, bbsName] = settingMatch;
                const bbs = env.BBS.getByName(bbsName);
                const settingText = await bbs.getSettingText();

                return createTextResponse(settingText, "Shift-JIS");
            }

            // Not Found
            return createHtmlResponse(view404Html, { status:404, pathname: pathname });
        } catch (err) {
            // Internal Server Error
            console.error(err);
            return createHtmlResponse(viewErrorHtml, { status: 500, statusText: "Internal Server Error", errMsg: err.message });
        }
    },
};
