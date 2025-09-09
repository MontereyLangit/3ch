////@ts-check
import { handleBbsPost } from "./services/bbs_cgi_service.js";
import { handleDat } from "./services/dat.js";
import { handleSubject } from "./services/subject.js";
import { handleHead } from "./services/head.js";
import { handleSetting } from "./services/setting.js";

export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        const pathname = url.pathname;

        try {
            // POST /test/bbs.cgi または /test/subbbs.cgi
            if (request.method === "POST" &&
                    (pathname === "/test/bbs.cgi" || pathname === "/test/subbbs.cgi")) {
                return await handleBbsPost(request, env, ctx);
            }

            // GET /{bbs}/{key}.dat
            const datMatch = pathname.match(/^\/([^/]+)\/([0-9]+)\.dat$/);
            if (request.method === "GET" && datMatch) {
                const [, bbs, key] = datMatch;
                return await handleDat(env, bbs, key);
            }

            // GET /{bbs}/subject.txt
            const subjectMatch = pathname.match(/^\/([^/]+)\/subject\.txt$/);
            if (request.method === "GET" && subjectMatch) {
                const [, bbs] = subjectMatch;
                return await handleSubject(env, bbs);
            }

            // GET /{bbs}/head.txt
            const headMatch = pathname.match(/^\/([^/]+)\/head\.txt$/);
            if (request.method === "GET" && headMatch) {
                const [, bbs] = headMatch;
                return await handleHead(env, bbs);
            }

            // GET /{bbs}/SETTING.TXT
            const settingMatch = pathname.match(/^\/([^/]+)\/SETTING\.TXT$/i);
            if (request.method === "GET" && settingMatch) {
                const [, bbs] = settingMatch;
                return await handleSetting(env, bbs);
            }

            // Not Found
            return new Response("Not Found", { status: 404 });

        } catch (err) {
            console.error(err);
            return new Response("Internal Server Error", { status: 500 });
        }
    },
};
