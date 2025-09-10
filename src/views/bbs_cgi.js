import { bbsCgiConfirmationTemplete } from "./_bbs_cgi_confirmation.js";
import { bbsCgiErrorTemplete } from "./_bbs_cgi_error.js";
import { bbsCgiSuccessTemplete } from "./_bbs_cgi_success.js";
export const viewBbsCgiHtml = model => `<html>${
  model.hasErrors()
    && (model.status === "confirmation"
      ? "<!-- 2ch_X:cookie -->"
      : "<!-- 2ch_X:error -->"
    )
}
<head>
<title>${
  model.hasErrors()
    ? (model.status === "confirmation"
      ? "■ 書き込み確認 ■"
      : "ＥＲＲＯＲ！"
    ) : "書きこみました。"
}</title>
<meta http-equiv="Content-Type" content="text/html; charset=${model.charset}">
${!model.hasErrors()
  && "<meta content=5;URL=/test/read.cgi/" + model.bbs + "/" + model.key + "/l50 http-equiv=refresh>"
}
<meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
</head>
<body>
${
  model.hasErrors()
    ? (model.status === "confirmation"
      ? bbsCgiConfirmationTemplete(model)
      : bbsCgiErrorTemplete(model)
    ) : bbsCgiSuccessTemplete(model)
}
</body>
</html>
`;
