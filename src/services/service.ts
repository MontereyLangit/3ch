import { encodeSJIS } from "@/utils/commonUtils";
import { ViewModel } from "@/model/view/ViewModel";

/**
 * ViewModelからHTMLレスポンスを生成します。
 * @param {function} view View関数
 * @param {object} model ViewModel
 * @returns {Response}
 */
export function createHtmlResponse<T extends ViewModel>(view: (model: T) => string, model: T): Response {
    const headers = model.headers || {};
    let body: string | Uint8Array;

    if (model.isSJIS) {
        headers["Content-Type"] = `text/html; charset=Shift_JIS`;
        body = encodeSJIS(view(model));
    } else {
        headers["Content-Type"] = `text/html; charset=UTF-8`;
        body = view(model);
    }

    return new Response(body, {
        status: model.status || 200,
        headers: headers,
    });
}

/**
 * テキストデータのレスポンスを生成します。
 * @param {string} text 
 * @param {string} encoding 
 * @param {string} rangeHeader 
 * @param {Date} lastModified 
 * @param {string} ifModifiedSinceHeader 
 * @param {object} addHeader 
 * @returns {Response}
 */
export function createTextResponse(
    text: string,
    encoding: "UTF-8" | "Shift_JIS" = "UTF-8",
    rangeHeader: string | null = "",
    lastModified: Date = new Date(),
    ifModifiedSinceHeader: string | null = "",
    addHeader: Record<string, string> = {}
): Response {
    let response: Response;

    if (ifModifiedSinceHeader && lastModified <= new Date(ifModifiedSinceHeader)) {
        return new Response(null, {
            status: 304,
            headers: {
                "Last-Modified": lastModified.toUTCString()
            }
        });
    }

    let bytes: Uint8Array;
    let contentType: string;

    if (encoding === "Shift_JIS") {
        bytes = encodeSJIS(text);
        contentType = `text/plain;charset=Shift_JIS`;
    } else {
        const encoder = new TextEncoder();
        bytes = encoder.encode(text);
        contentType = `text/plain;charset=UTF-8`;
    }

    const totalLength = bytes.length;

    if (rangeHeader) {
        const match = rangeHeader.match(/^bytes=(\d*)-(\d*)$/);
        if (!match) {
            return new Response("Bad Range Header", { status: 400 });
        }

        const start = parseInt(match[1] || "0", 10);
        let end = match[2] ? parseInt(match[2], 10) : totalLength - 1;

        if (start >= totalLength || start > end) {
            return new Response("Requested Range Not Satisfiable", {
                status: 416,
                headers: { "Content-Range": `bytes */${totalLength}` }
            });
        }

        end = Math.min(end, totalLength - 1);
        const chunk = bytes.slice(start, end + 1);

        response = new Response(chunk, {
            status: 206,
            headers: {
                "Content-Type": contentType,
                "Last-Modified": lastModified.toUTCString(),
                "Content-Length": chunk.length.toString(),
                "Content-Range": `bytes ${start}-${end}/${totalLength}`,
                "Accept-Ranges": "bytes"
            }
        });
    } else {
        response = new Response(bytes, {
            headers: {
                "Content-Type": contentType,
                "Content-Length": totalLength.toString(),
                "Last-Modified": lastModified.toUTCString(),
                "Accept-Ranges": "bytes"
            }
        });
    }

    Object.entries(addHeader).forEach(([name, value]) => {
        response.headers.set(name, value);
    });

    return response;
}
