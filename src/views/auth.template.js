import { viewAuthJs } from "./auth.js";
// 認証ページを利用するのは専ブラユーザーのみ
// Web版ではこのページではなく、書き込み画面で動的にauth.jsを読み込み、認証を行う
export const viewAuthHtml = model => `<!DOCTYPE html>
<html>
  <head>
    <title>認証</title>
    <meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';">
    <meta name="robots" content="noindex,nofollow,noarchive" />
    <meta name="referrer" content="no-referrer" />
    <meta http-equiv="Content-type" content="text/html; charset=UTF-8" />
  </head>
  <body>
    <div id="auth"></div>
    <script src="/api/auth.js"></script>
  </body>
</html>`;
