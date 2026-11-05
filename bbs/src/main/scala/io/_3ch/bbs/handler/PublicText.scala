package io._3ch.bbs.handler

import io._3ch.bbs.BuildInfo

object PublicText {
  def respond404(pathname: String): String = "<!DOCTYPE html>\n<html>\n<head>\n<title>Error 404</title>\n<meta http-equiv=\"Content-type\" content=\"text/html; charset=UTF-8\" />\n</head>\n<body>\n<h1>Error 404</h1>\n<div id=\"content\">\nThe page you requested &quot;" + pathname + "&quot; is not available.\n</div>\n<hr>\n<div id=\"footer\"><a href=\"https://github.com/MontereyLangit/3ch\">3ch</a> ver " + BuildInfo.version + " " + BuildInfo.updatedDate + " <a href=\"https://github.com/MontereyLangit/\" style=\"text-decoration:none;color:rgb(254, 100, 46);font-weight:700\">†Monterey Langit ★</a></div>\n</body>\n</html>"
  def respond500(errorMsg: String): String = "<!DOCTYPE html>\n<html>\n<head>\n<title>500 Internal Server Error</title>\n<meta http-equiv=\"Content-type\" content=\"text/html; charset=UTF-8\" />\n</head>\n<body>\n<h1>Internal Server Error</h1>\n<div id=\"content\">\nThe server was unable to complete your request. Please try again later.<br>\nIf this problem persists, please post the notice on <a href=\"https://delightly.3chbbs.com/operate3/\">the operate3 board</a>.<br>\n不明なエラーが発生しました。運用情報板で報告していただけるとありがたいです。<br>\nError Message: " + errorMsg + "\n</div>\n<hr>\n<div id=\"footer\"><a href=\"https://github.com/MontereyLangit/3ch\">3ch</a> ver " + BuildInfo.version + " " + BuildInfo.updatedDate + " <a href=\"https://github.com/MontereyLangit/\" style=\"text-decoration:none;color:rgb(254, 100, 46);font-weight:700\">†Monterey Langit ★</a></div>\n</body>\n</html>"
}
