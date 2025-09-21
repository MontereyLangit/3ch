import { FC } from 'hono/jsx';

interface ErrorViewModel {
    status: number;
    statusText: string;
    errMsg: string;
}

export const ViewError: FC<{ model: ErrorViewModel }> = ({ model }) => (
    <html>
        <head>
            <title>{model.status} {model.statusText}</title>
            <meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';" />
            <meta name="robots" content="noindex,nofollow,noarchive" />
            <meta name="referrer" content="no-referrer" />
            <meta http-equiv="Content-type" content="text/html; charset=UTF-8" />
        </head>
        <body>
            <div id="header">
                <a href="https://www.3chbbs.com/"><img src="https://www2.3chbbs.com/logo.png" alt="３ちゃんねる" title="３ちゃんねる" border="0" /></a>
            </div>
            <div id="main">
                <div id="boardHeader">
                    <h1>{model.status} {model.statusText}</h1>
                </div>
                <div id="content">
                    <div>サービスが一時的に利用できません。<br />お手数ですが、しばらく時間をおいてから再度アクセスしてください。<br /></div>
                    <div class="errorMessage">Error Message: {model.errMsg}</div>
                </div>
            </div>
        </body>
    </html>
);
