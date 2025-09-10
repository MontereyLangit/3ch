/*
 * @param {Object} model bbs.cgiのViewModel
 * @return {string} HTML
 */
export const viewBbsCgiSuccessHtml = model => `
<html lang="ja">
<head>
<title>書きこみました。</title>
<meta http-equiv="Content-Type" content="text/html; charset=${model.charset}">
<meta content=5;URL=/test/read.cgi/${model.bbs}/${model.key}/l50  http-equiv=refresh>
</head>
<body>書きこみが終わりました。<br><br>
画面を切り替えるまでしばらくお待ち下さい。<br><br>
<br><br><br><br><br>
<center>
</center>
</body>
</html>
`;
