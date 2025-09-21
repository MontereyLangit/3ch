import { FC } from 'hono/jsx';

interface NotFoundViewModel {
    pathname: string;
}

export const View404: FC<{ model: NotFoundViewModel }> = ({ model }) => (
    <html>
        <head>
            <title>404 Not Found</title>
        </head>
        <body>
            <h1>404 Not Found</h1>
            <p>お探しのページ({model.pathname})は見つかりませんでした。</p>
        </body>
    </html>
);
