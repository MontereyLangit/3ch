import { FC } from 'hono/jsx';
import { AuthViewModel } from '@/model/view/AuthViewModel';

export const ViewAuth: FC<{ model: AuthViewModel }> = ({ model }) => (
    <html>
        <head>
            <title>認証</title>
            <meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' https://challenges.cloudflare.com; style-src 'self'; img-src data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';" />
            <meta name="robots" content="noindex,nofollow,noarchive" />
            <meta name="referrer" content="no-referrer" />
            <meta http-equiv="Content-type" content="text/html; charset=UTF-8" />
        </head>
        <body>
            <div id="auth">読み込み中...</div>
            <script src="/api/auth.js"></script>
        </body>
    </html>
);
