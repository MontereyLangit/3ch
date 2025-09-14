/*
 * クラシック版のWeb版(model不要)
 */
export const view3chikoHtml = () => `<!DOCTYPE html>
<!-- 3chiko.html -->
<html lang="ja">
<head>
<meta charset="utf-8">
<meta http-equiv="X-UA-Compatible" content="IE=11">
<title></title>
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<meta name="Keyword" content="">
<meta name="Description" content="">
<meta name="format-detection" content="telephone=no">
</head>
<body>
<noscript>
アクティブスクリプトが無効になっています。<br>3chiko.htmlを利用するには、アクティブスクリプトを有効にする必要があります。<br>インターネット オプション＞セキュリティ＞カスタム レベル＞セキュリティ設定 - インターネット ゾーン＞アクティブ スクリプトの有効化より、スクリプトを有効にしてください。
</noscript>
<!-- JavaScript -->
<SCRIPT LANGUAGE="JavaScript">
<!-- 
// itest3chが有効な場合、itest3ch.jsを読み込む処理 2025.08～

function l(e){var N=getCookie("NAME"),M=getCookie("MAIL"),i;with(document)for(i=0;i<forms.length;i++)if(forms[i].FROM&&forms[i].mail)with(forms[i]){FROM.value=N;mail.value=M;}}onload=l;
function getCookie(key, tmp1, tmp2, xx1, xx2, xx3){tmp1=" "+document.cookie+";";xx1=xx2=0;len=tmp1.length;while(xx1<len){xx2=tmp1.indexOf(";",xx1);tmp2=tmp1.substring(xx1+1,xx2);xx3 = tmp2.indexOf("=");if(tmp2.substring(0,xx3)==key){return(unescape(tmp2.substring(xx3 + 1, xx2 - xx1 - 1)));}xx1 = xx2 + 1;}return("");}
//-->
</SCRIPT>
<!-- 3chiko.html JScript 2007/08/13~ -->
<script language="JScript">
<!-- 
var path = location.pathname.split("/");
/* トップページ */
if (path[1] == "") {
  document.title = "[3ch] ３ちゃんねる掲示板へようこそ";
  /* トップページの中身はここへ。。。 */
  document.body.innerHTML = '<div><h1>[3ch] ３ちゃんねる掲示板へようこそ</h1><ul><li><a href="https://www.3chbbs.com/">トップページ</a></li><li><a href="https://www.3chbbs.com/bbsmenu.html">掲示板一覧</a></li><li><a href="https://www.3chbbs.com/html/">３ちゃんねるガイド</a></li><li><a href="https://www.3chbbs.com/">トップページ</a></li><li><a href="https://www.3chbbs.com/html/tos">利用規約</a></li><li><a href="https://www.3chbbs.com/html/privacy">プライバシー・ポリシー</a></li><li><a href="https://www.3chbbs.com/html/adv">削除ガイドライン</a></li><li><a href="https://www.3chbbs.com/html/guide">使い方＆注意</a></li><li><a href="https://www.3chbbs.com/operate/">運用情報板</a></li><li><a href="https://www.3chbbs.com/saku/">削除依頼板</a></li><li><a href="https://www.3chbbs.com/arashi/">荒らし報告板</a></li><li><a href="mailto:y@3chbbs.com">管理人メール</a></li><p>３ちゃんねる掲示板のご利用は利用者各位のご判断にお任せしています。 <p><font size="1">３ちゃんねる掲示板のデータの利用に関して、原則的に自由ですが、３ちゃんねるの書き込みなどを利用して収益を得る行為はご遠慮下さい。<br><br>要するに、3ちゃんねるの書き込みを転載してお金儲けをしているような方は<br>面倒なことになるかもしれません、、、ってことです。</font></p><a href="matome.html">３ちゃんねるまとめブログ運営者の皆さまへ</a></p><p>どのような形の削除依頼であれ公開させていただきます</p></ul></div>';
}
/* read.cgi */
else if (path[1] == "test" && path[2] == "read.cgi") {
  var makeres = function makeres(match, p1, p2, p3, p4, p5, offset, string) {
    rescount++;
    var name = p2 ? "<a href=\"mailto:" + p2 + "\"><b>" + p1 + "</b></a>" : "<font color=green><b>" + p1 + "</b></font>";
    p4 = p4.replace(/&gt;&gt;([0-9]+)/g, "<a href=\"#$1\">&gt;&gt;$1</a>");
    p4 = p4.replace(/(https?:\/\/[\w!?/+\-_~;.,*&@#$%()'[\]]+)/g, "<a href=\"$1\">$1</a>");
    return "<a name=\"" + rescount + "\"></a><dt>" + rescount + " ：" + name + "：" + p3 + "<dd>" + p4 + "<br><br>";
  };
  var NOWTIME = Math.floor(new Date().getTime() / 1000);
  var THREAD = ajax("/" + path[3] + "/dat/" + path[4] + ".dat");
  var match = THREAD.match(/^.*?<>.*?<>(.*?)<>(.*?)<>(.*?)\n/);
  var rescount = 0;
  THREAD = THREAD.replace(/(.*?)<>(.*?)<>(.*?)<>(.*?)<>(.*?)\n/g, makeres);
  document.title = match[3];
  document.body.innerHTML = '<!--<a href="http://ofuda.cc/"><img width="400" height="15" alt="もう22時か、" border="0" src="http://e.ofuda.cc/disp/1001000000010000/00813400.gif" ALT="無料アクセスカウンターofuda.cc「全世界カウント計画」"></a></div>--><div>もう22時か、</div><div style="margin-top:1em;"><span style=\'float:left;\'><a href="https://www.3chbbs.com/">３ちゃんねる</a> <a href="/<BBS>/">■掲示板に戻る■</a> <a href="/test/read.cgi/<BBS>/<KEY>/">全部</a><!--<a href="/test/read.cgi/<BBS>/<KEY>/-100">1-</a><a href="/test/read.cgi/<BBS>/<KEY>/l50">最新50</a>--><a href="javascript:switchReadJsMode();" style="background-color:dimgray;border:1px outset dimgray;color:lightgreen;text-decoration:none;">read.cgi モード切替</a></span><span style=\'float:right;\'>[PR][PR]</span>&nbsp;</div><hr style="background-color:#888;color:#888;border-width:0;height:1px;position:relative;top:-.4em;"><h1 style="color:red;font-size:larger;font-weight:normal;margin:-.5em 0 0;"><SUBJECT></h1><dl class="thread"><THREAD></dl><font color=red face="Arial"><b></b></font><font size=-1><b>[ ３ちゃんねる ]</b></font><br><hr>取りに行ったけどなかった。次は一時間後に取りに行くです。<center><a href="/test/read.cgi/<BBS>/<KEY>/">新着レスの表示</a></center><hr><a href="/<BBS>/">掲示板に戻る</a> <a href="/test/read.cgi/<BBS>/<KEY>/">全部</a><!--<a href="/test/read.cgi/<BBS>/<KEY>/">前100</a><a href="/test/read.cgi/<BBS>/<KEY>/">次100</a><a href="/test/read.cgi/<BBS>/<KEY>/l50">最新50</a>--><form method=POST action="/test/bbs.cgi?guid=ON"><input type=submit value="書き込む" name=submit>名前： <input name=FROM size=19>E-mail<font size=1> (省略可) </font>: <input name=mail size=19><br><textarea rows=5 cols=70 wrap=off name=MESSAGE></textarea><input type=hidden name=bbs value=<BBS>><input type=hidden name=key value=<KEY>><input type=hidden name=time value=<TIME>></form><br><br>3chiko.html (JScript) 2007/08/13<br>'.replace(/<BBS>/g, path[3]).replace(/<KEY>/g, path[4]).replace(/<TIME>/g, "" + NOWTIME).replace(/<THREAD>/g, THREAD).replace(/<DATEID>/g, match[1]).replace(/<MESSAGE>/g, match[2]).replace(/<SUBJECT>/g, match[3]);
} else if (path[1] == "test") {
  document.body.innerHTML = "3chiko.html (JScript) 2007/08/13";
}
/* 板top(subback) */
else if (path[2] == "") {
  var makesubj = function makesubj(match, p1, p2, offset, string) {
    var key = p1.replace(".dat", "");
    subjcount++;
    return "<a href=\"/test/read.cgi/<BBS>/"+key+"/\">" + subjcount + ": " + p2 + "</a><br>";
  };
  var NOWTIME = Math.floor(new Date().getTime() / 1000);
  var SUBJECT = ajax("./subject.txt");
  var SETTING = ajax("./SETTING.TXT");
  var HEAD = ajax("./head.txt");
  var m1 = SETTING.match(/BBS_TITLE=(.*?)\n/);
  var subjcount = 0;
  SUBJECT = SUBJECT.replace(/(.*?)<>(.*?)\n/g, makesubj);
  document.title = m1 ? m1[1] : path[1]+"＠3ch掲示板";
  document.body.innerHTML = '<center><a name="top" /><br><a name=info></a><table border=1 cellspacing=7 cellpadding=3 width=95% bgcolor=#ffffcc><tr><td><table border=0 width=100%><tr><td><b><big><BBS_TITLE></big></b></td><td align=right><a href=#menu>■</a> <a href=#1>▼</a> <a href="javascript:switchReadJsMode();" style="background-color:dimgray;border:1px outset dimgray;color:lightgreen;text-decoration:none;">read.cgi モード切替</a></td></tr><tr><td colspan=2><a href="https://www.3chbbs.com/">３ちゃんねる</a>＞<a href="https://www.3chbbs.com/bbsmenu.html">BBS</a>＞<BBS_TITLE><br><HEAD></td></tr></table></td></tr><tr><td align=center><a href="../bbsmenu.html" Target=_blank><small>■<b>掲示板一覧</b>■</small></a></td></tr></table><br><table border=1 cellspacing=7 cellpadding=3 width=95% bgcolor=#FFFFFF align=center><td><table width="100%" border="0" cellspacing="0" cellpadding="0"><tr><td><font color="#FF6666">★３月３日は「３ちゃんねるの日」。今年も書き込みは通常の３倍！<font></td></tr><tr><td><div align="right"><font color="#0000FF"><strong><u>広告出稿のお問い合わせ</u></strong></font></div></td></tr></table></td></table><br><a name="menu" /></a><table border=1 cellspacing=7 cellpadding=3 width=95% bgcolor=#ffffcc><tr><td><small><SUBJECT><div align="right"><b><a href="https://www.3chbbs.com/<BBS>/kako/">過去ログ倉庫</a></b></div></small></td></tr></tbody></table><br><table border="1" cellspacing="7" cellpadding="3" width="95%" bgcolor="#CCFFCC" align="center"><tbody><tr><td></td><td nowrap=""><form method="POST" action="../test/bbs.cgi?guid=ON">タイトル：<input type="text" name="subject" size="40"><input type="submit" value="書き込む" name="submit"><br>名前：<input type="text" name="FROM" size="19"> E-mail：<input type="text" name="mail" size="19"><br>内容：<textarea rows="5" cols="60" wrap="OFF" name="MESSAGE"></textarea><input type="hidden" name="bbs" value="<BBS>"><input type="hidden" name="time" value="<TIME>"></form></td></tr></tbody></table><br></center><p>SETTING.TXT</p><pre><SETTING></pre><br><br><b>どのような形の削除依頼であれ公開させてもらいます</b><br><br><a href="https://www.3chbbs.com/">３ちゃんねる</a> 3chiko.html - 2007/08/13 (JScript)<br>ページのおしまいだよ。。と'.replace(/<BBS_TITLE>/g, m1 ? m1[1] : path[1]+"＠3ch掲示板").replace(/<SUBJECT>/g, SUBJECT).replace(/<SETTING>/g, SETTING).replace(/<HEAD>/g, HEAD).replace(/<BBS>/g, path[1]).replace(/<TIME>/g, "" + NOWTIME);
}
/* 倉庫一覧 */
else if (path[2] == "kako" && path[3] == "") {
  var NOWTIME = Math.floor(new Date().getTime() / 1000);
  var KAKOLIST = ajax("./subject.txt");
  KAKOLIST = KAKOLIST.replace(/(.*?)<>(.*?)\n/g, "<tr><td><a href=\"$1/\">#<BBS>/$1</a></td><td align=\"right\">-</td><td align=\"right\"> $2</td><td align=\"right\"><a href=\"$1/subject.txt\">subject.txt</a></td></tr>");
  document.title = "３ちゃんねる 過去ログ倉庫 " + path[1] + "＠3ch掲示板";
  document.body.innerHTML = '<CENTER><h1>３ちゃんねる 過去ログ倉庫</h1><P><h2><BBS>＠3ch掲示板</h2><P></CENTER><a href="https://www.3chbbs.com/">３ちゃんねる に戻る</a><P><a href="/bbsmenu.html">板一覧 に戻る</a><P><a href="/<BBS>/"><BBS>＠3ch掲示板 に戻る</a><P><TABLE BORDER=2><TR><TD>倉庫番号</TD><TD>格納数</TD><TD>初スレ</TD><TD align="center">subject.txt</TD></TR><KAKOLIST></TABLE><a href="subject.txt">倉庫一覧</a><BR><A HREF="index3.html">旧倉庫。No2</A><BR><A HREF="index9.html">999999999 以前</A><BR><hr>問題等がありましたら<a href="https://www.3chbbs.com/operate/"><font color="green">運用情報板</font></a>へお願いします。'.replace(/<KAKOLIST>/g, KAKOLIST).replace(/<BBS>/g, path[1]);
}
/* 過去ログ一覧 */
else if (path[2] == "kako") {
  var makekako = function makekako(match, p1, p2, p3, offset, string) {
    kakocount++;
    return p1 + " <A HREF=\"/test/read.cgi/<BBS>/" + p1 + "/\">" + p2 + "</A><BR>";
  };
  var NOWTIME = Math.floor(new Date().getTime() / 1000);
  var KAKO = ajax("./subject.txt");
  var kakocount = 0;
  KAKO = KAKO.replace(/(.*?)<>(.*?)<>(.*?)\n/g, makekako);
  document.title = "３ちゃんねる　過去ログ倉庫 (" + path[1] + ")";
  document.body.innerHTML = '<a href="/<BBS>/">■掲示板に戻る■</a><a href="/bbsmenu.html">■板一覧に戻る■</a><P><a href="/<BBS>/kako/">■過去ログめにゅーに戻る■</a><P>※新しいデータ形式(teriのタイプ)のスレッド<p><KAKO><hr>スレッド数 = <KAKOCOUNT><hr>問題等がありましたら<a href="https://www.3chbbs.com/operate/"><font color="green">運用情報板</font></a>へお願いします。'.replace(/<KAKO>/g, KAKO).replace(/<KAKOCOUNT>/g, "" + kakocount).replace(/<BBS>/g, path[1]);
} else {
  document.open();
  document.write("<p>404 Not Found</p>");
  document.write("<br><br><a href=\"https://www.3chbbs.com/\">３ちゃんねる</a> 3chiko.html(JScript) 2007/08/13");
  document.close();
}

function ajax(url) {
  var data = "読み込み中。。。";
  var xhr;
  if (window.XMLHttpRequest) {
    xhr = new XMLHttpRequest();
  } else {
    try {
      xhr = new ActiveXObject("Msxml2.XMLHTTP");
    } catch (e) {
      xhr = new ActiveXObject("Microsoft.XMLHTTP");
    }
  }
  xhr.onreadystatechange = function () {
    switch (xhr.readyState) {
      case 0:
        data = "エラー0<br>" + url;
        break;
      case 1:
        data = "読み込み中1。。。<br>" + url;
        break;
      case 2:
        data = "読み込み中2。。。<br>" + url;
        break;
      case 3:
        data = "読み込み中3。。。<br>" + xhr.responseText.length + " bytes.<br>" + url;
        break;
      case 4:
        if (xhr.status == 200 || xhr.status == 304) {
          data = xhr.responseText;
        } else {
          data = "エラー1 (" + xhr.statusText + ")<br>" + url;
        }
        break;
    }
  };
  // 2025.8.12 iOSで更新が反映されない問題の対応
  xhr.open("GET", url+"?"+(Math.floor(new Date().getTime() / 1000).toString().slice(0,9)), false);
  xhr.send();
  xhr.abort();
  return data;
}
//-->
</script>
</body>
</html>
`;
