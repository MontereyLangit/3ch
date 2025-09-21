import { FC } from 'hono/jsx';

interface BoardViewModel {
    bbsTitle: string;
    bbsDescription: string;
}

export const ViewBoard: FC<{ model: BoardViewModel }> = ({ model }) => (
    <html>
        <head>
            <title>{model.bbsTitle}</title>
            <meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';" />
            <link rel="icon" type="image/x-icon" href="https://www2.3chbbs.com/favicon.ico" />
            <link rel="stylesheet" href="https://www2.3chbbs.com/style.css" />
            <script src="https://www2.3chbbs.com/bbs.js"></script>
        </head>
        <body>
            <div id="header">
                <a href="https://www.3chbbs.com/"><img src="https://www2.3chbbs.com/logo.png" alt="３ちゃんねる" title="３ちゃんねる" border="0" /></a>
            </div>
            <div id="main">
                <div id="boardHeader"><h1>{model.bbsTitle}</h1><div id="boardInfo"><span id="boardDescription">{model.bbsDescription}</span></div></div>
            </div>
        </body>
    </html>
);
