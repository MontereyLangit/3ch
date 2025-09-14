////@ts-check
import { createHtmlResponse, createTextResponse, createErrorResponse } from "./services/service.js";
import { entryPost } from "./services/bbs_cgi_service.js";
import { bbsCgiViewModel } from "./model/bbs_cgi_view_model.js";

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        const pathname = url.pathname;

        try {
            // POST /test/bbs.cgi, /test/subbbs.cgi
            if (request.method === "POST" &&
                    (pathname === "/test/bbs.cgi" || pathname === "/test/subbbs.cgi")) {
                const model = await entryPost(request, env, ctx, bbsCgiViewModel);
                
                if (model.error) {
                    return createErrorResponse(model);
                }
                
                return await createHtmlResponse(bbsCgiViewModel);
            }

            // GET /{bbs}/{key}.dat
            const datMatch = pathname.match(/^\/([^/]+)\/([0-9]+)\.dat$/);
            if (request.method === "GET" && datMatch) {
                const [, bbsName, key] = datMatch;
                const thread = env.THREAD.getByName(bbsName + key);
                const datText = await thread.getDat();

                if (datText === null) {
                    return createErrorResponse({error: "Not Found", status: 404});
                }

                return await createTextResponse(datText, "Shift-JIS");
            }

            // GET /{bbs}/subject.txt
            const subjectMatch = pathname.match(/^\/([^/]+)\/subject\.txt$/);
            if (request.method === "GET" && subjectMatch) {
                const [, bbsName] = subjectMatch;
                const bbs = env.BBS.getByName(bbsName);
                const subjectText = await bbs.getSubjectText();

                return await createTextResponse(subjectText, "Shift-JIS");
            }

            // GET /{bbs}/head.txt
            const headMatch = pathname.match(/^\/([^/]+)\/head\.txt$/);
            if (request.method === "GET" && headMatch) {
                const [, bbsName] = headMatch;
                const bbs = env.BBS.getByName(bbsName);
                const headText = await bbs.getHeadText();

                return await createTextResponse(headText, "Shift-JIS");
            }

            // GET /{bbs}/SETTING.TXT
            const settingMatch = pathname.match(/^\/([^/]+)\/SETTING\.TXT$/i);
            if (request.method === "GET" && settingMatch) {
                const [, bbsName] = settingMatch;
                const bbs = env.BBS.getByName(bbsName);
                const settingText = await bbs.getSettingText();

                return await createTextResponse(settingText, "Shift-JIS");
            }

            // Not Found
            return createErrorResponse({error: `Not Found: ${pathname}`, status: 404});

        } catch (err) {
            return createErrorResponse({error: `Internal Server Error: ${err.message}`, status: 500});
        }
    },
};
