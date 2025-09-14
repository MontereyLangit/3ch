export const view404Html = (model) => `<!DOCTYPE html>
<html>
  <head>
    <title>404 Not Found</title>
  </head>
  <body>
    <h1>404 Not Found</h1>
    <p>お探しのページ(${model.pathname})は見つかりませんでした。</p>
  </body>
</html>`;
