import { respondPlainText, respond404, respond500 } from "./common.js";
import { bbs_entry, settingTxtDefault } from "./bbs.cgi.js";
import { Board } from "./durable_objects/board.js";
import { Thread } from "./durable_objects/thread.js";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const version = "<a href=\"https://www.3chbbs.com/\">３ちゃんねる</a>(<a href=\"https://wiki.3chbbs.com/index.php/Project_edge\">edge</a>) "
+ "Ver.0.1.1 2025/07/05"
+ "(<a href=\"https://workers.cloudflare.com/\">Cloudflare Workers</a>)";

    /** 保守対応用管理操作 */
    if (url.pathname === "/test/sakubbs.cgi/25") {
      /** 2025/12/12
       */

      return respondPlainText("sakubbs.cgi v25", "plain", "UTF-8");
    }

    /* bbs.cgi (subbbs.cgiも) */
    if(/^\/test\/(sub)?bbs.cgi/.test(url.pathname)) {
      return await bbs_entry(request, env, ctx);
    }

    /* 認証用js */
    if(url.pathname === "/auth.js") {
      const script = `/* test */`;
      return new Response(script,
      {
        headers: {
          "content-type": "application/javascript;charset=UTF-8",
        }
      });
    }

    /* bbsmenu.html */
    if(url.pathname === "/bbsmenu.html") {
      /* Cache API */
      const cacheKey = new Request(url.toString(), request);
      const cache = caches.default;
      let response = await cache.match(cacheKey);
      if (response) return response;

      const filename = "headline.txt";
      let ifModifiedSinceHeader = "";
      let lastModified = new Date();
      let error = "";
      let html = `<HTML>
<HEAD>
<META http-equiv="Content-Type" content="text/html; charset=Shift_JIS">
<TITLE>BBS MENU for 3ch</TITLE>
</HEAD>
<BODY TEXT="#CC3300" BGCOLOR="#FFFFFF" link="#0000FF" alink="#ff0000" vlink="#660099">
<font size=2>
<A HREF=https://www.3chbbs.com/ TARGET=_blank>3chの入口</A><br>
<A HREF=https://www.3chbbs.com/html/guide/ TARGET=_blank>3ch総合案内</A><br>
<A HREF=https://wiki.3chbbs.com/ TARGET=_blank>3ch Wiki</A><br>
<BR><BR><B>３ちゃんねる</B><BR>
`;

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          const headLineText = await object.text();
          const headLineArray = headLineText.split("\n");

          /* スレの配列に最低2個要素がないとおかしい */
          if(headLineArray.length > 1) {
            /* 最終更新時刻を取得。ログの一行目に入っている。 */
            lastModified = new Date(parseInt(headLineArray.shift()) * 1000);
            
            for(let i = 0; i < headLineArray.length; i++) {
              /* 0:dir_name<>1:板名<>2:最終更新時刻<>3:最終書き込みスレッド名<>4:最終書き込みレスの一部 */
              const headLine = headLineArray[i].trim().split("<>");

              /* 0無し、0が^[a-zA-Z0-9\-_]+$でない、1無し、4まで<>がない -> 表示しない */
              if(!headLine[0] || !/^[a-zA-Z0-9\-_]+$/.test(headLine[0]) || !headLine[1] || headLine[4] == null) continue;
              
              html += `<A HREF=https://www.3chbbs.com/${headLine[0]}/>${headLine[1]}</A><br>\n`;
            }
          }
        }else error = "404";
      }catch(e) {
        error = `/bbsmenu.html Error -> ${e}`;
      }

      html += `
<BR><BR><B>他のサイト</B><BR>
<A HREF=https://bbs.2ch2.net/kisikenn/>3ch避難所@bbs.2ch2.net</A><br>

<br>更新日 ${lastModified.toLocaleString("ja-JP")}
</font>
</BODY></HTML>
`;

      if(!error) {
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        /* max-age=5, s-maxage=1 */
        let headers = {};
        headers["max-age"] = "60";
        headers["s-maxage"] = "60";

        response = respondPlainText(html, "html", "Shift_JIS", "", lastModified, ifModifiedSinceHeader, headers);
                
        /* Cache API */
        if (response.status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));

        return response;
      }else if(error === "404") return respond404("/bbsmenu.html");
      else return respond500(error);
    }

    /* 全体のheadline */
    if(url.pathname === "/headline/subject.txt") {
      /* Cache API */
      const cacheKey = new Request(url.toString(), request);
      const cache = caches.default;
      let response = await cache.match(cacheKey);
      if (response) return response;

      const filename = "headline.txt";
      let ifModifiedSinceHeader = "";
      let lastModified;
      let headLineTxt = "";
      let error = "";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          const headLineText = await object.text();
          const headLineArray = headLineText.split("\n");

          /* スレの配列に最低2個要素がないとおかしい */
          if(headLineArray.length > 1) {
            /* 最終更新時刻を取得。ログの一行目に入っている。 */
            lastModified = new Date(parseInt(headLineArray.shift()) * 1000);
            
            for(let i = 0; i < headLineArray.length; i++) {
              /* 0:dir_name<>1:板名<>2:最終更新時刻<>3:最終書き込みスレッド名<>4:最終書き込みレスの一部 */
              const headLine = headLineArray[i].trim().split("<>");

              /* 0無し、0が^[a-zA-Z0-9\-_]+$でない、1無し、4まで<>がない -> 表示しない */
              if(!headLine[0] || !/^[a-zA-Z0-9\-_]+$/.test(headLine[0]) || !headLine[1] || headLine[4] == null) continue;
              
              headLineTxt += `${headLine[0]}<>${headLine[1]}<>${headLine[2]}<>${headLine[3]}<>${headLine[4]}\n`;
            }
          }
        }else error = "404";
      }catch(e) {
        error = `headline.txt Error -> ${e}`;
      }

      if(!error) {
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        /* max-age=5, s-maxage=1 */
        let headers = {};
        headers["max-age"] = "60";
        headers["s-maxage"] = "60";

        response = respondPlainText(headLineTxt, "plain", "UTF-8", "", lastModified ?? new Date(), ifModifiedSinceHeader, headers);

        /* Cache API */
        if (response.status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));

        return response;
      }else if(error === "404") return respond404("headline.txt");
      else return respond500(error);
    }

    /* dat */
    if(/^\/[a-zA-Z0-9\-_]+\/dat\/[0-9]{10}\.dat/.test(url.pathname)) {
      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/dat\/([0-9]{10})\.dat/);
      const bbs = match[1];
      const key = match[2];

      const ENCODING = "Shift_JIS";
      let rangeHeader, ifModifiedSinceHeader = "";
      let lastModified;
      let dat = "";
      let error = "";

      try {
        // Durable Objectからスレッドデータを取得
        const threadId = env.THREAD_DO.idFromName(bbs + "/" + key);
        const threadStub = env.THREAD_DO.get(threadId);
        dat = await threadStub.getDat(bbs, key);
        // 1762009199以降1762087165以前のkeyの場合でdatが空の場合、keyのみの旧DOから取得を試みる
        // 取得できたら、現行スレdoの方に移す
        if (!dat && parseInt(key) > 1761990000 && parseInt(key) < 1762087165) {
          const oldThreadId = env.THREAD_DO.idFromName(key);
          const oldThreadStub = env.THREAD_DO.get(oldThreadId);
          dat = await oldThreadStub.getDat();
          if (dat) await threadStub.setDat(dat);
        }

        if (dat) {
          // 最終更新時刻を取得
          const lines = dat.trim().split('\n');
          const lastLine = lines[lines.length - 1];
          const fields = lastLine.split('<>');
          // 2番目のフィールド(日付)からunixtimeを抽出
          const dateStr = fields[2];
          if (dateStr) {
            const dateMatch = dateStr.match(/(\d{4}\/\d{2}\/\d{2}\([日月火水木金土]\) \d{2}:\d{2}:\d{2})/);
            if (dateMatch) lastModified = new Date(dateMatch[1]);
          }
        } else {
          error = "404";
        }
      } catch (e) {
        error = `/${bbs}/dat/${key}.dat Error -> ${e}`;
      }

      if(!error) {
        /* 差分取得 */
        if(request.headers.get('range')) rangeHeader = request.headers.get('range');
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        /* max-age=5, s-maxage=1 */
        let headers = {};
        headers["max-age"] = "5";
        headers["s-maxage"] = "1";

        const response = respondPlainText(dat, "plain", ENCODING, rangeHeader, lastModified ?? new Date(), ifModifiedSinceHeader, headers);
        
        return response;
      }else if(error === "404") return respond404(`/${bbs}/dat/${key}.dat`);
      else return respond500(error);
    }

    /* subject.txt */
    if(/^\/[a-zA-Z0-9\-_]+\/subject\.txt/.test(url.pathname)) {
      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/subject\.txt/);
      const bbs = match[1];
      const ENCODING = "Shift_JIS";
      let ifModifiedSinceHeader = "";
      let lastModified;
      let subject = "";
      let error = "";
      let subjectCount = 0;
 
      try {
        // Board DOからsubject.txtを取得
        const boardId = env.BOARD_DO.idFromName(bbs);
        const boardStub = env.BOARD_DO.get(boardId);
        const subjectText = await boardStub.getSubject();
 
        if (subjectText) {
          // 最終更新時刻はファイルヘッダからではなく、現在時刻をベースにする
          lastModified = new Date();
          const subjectArray = subjectText.split("\n");
 
          for (const line of subjectArray) {
            if (!line.trim()) continue;
            subject += line.trim() + "\n";
            subjectCount++;
          }
        }
      } catch (e) {
        error = `/${bbs}/subject.txt Error -> ${e}`;
      }

      if(!error) {
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        /* max-age=5, s-maxage=1 */
        let headers = {};
        headers["max-age"] = "5";
        headers["s-maxage"] = "1";

        const response = respondPlainText(subject, "plain", ENCODING, "", lastModified ?? new Date(), ifModifiedSinceHeader, headers);
        return response;
      }else if(error === "404") return respond404(`/${bbs}/subject.txt`);
      else return respond500(error);
    }

    /* 過去ログ倉庫 (v2のみ) */
    if(/^\/[a-zA-Z0-9\-_]+\/kako\/[0-9]{4}\/subject\.txt/.test(url.pathname)) {
      /* Cache API */
      const cacheKey = new Request(url.toString(), request);
      const cache = caches.default;
      let response = await cache.match(cacheKey);
      if (response) return response;

      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/kako\/([0-9]{4})\/subject\.txt/);
      const bbs = match[1];
      const kako = match[2];
      const filename = `${bbs}/thread/${kako}/subject.txt`;
      let ifModifiedSinceHeader = "";
      let lastModified;
      let subject = "";
      let error = "";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          const subjectText = await object.text();
          const subjectArray = subjectText.split("\n");

          /* スレの配列に最低2個要素がないとおかしい */
          if(subjectArray.length > 1) {
            /* 最終更新時刻を取得。ログの一行目に入っている。 */
            lastModified = new Date(subjectArray.shift() * 1000);
            
            for(let i = 0; i < subjectArray.length; i++) {
              /* 0:key<>1:title<>2:名前<>3:sikibetsuId<>4:id<>5:>>1の本文 */
              const thread = subjectArray[i].trim().split("<>");

              /* key無し、keyが10桁の数字でない、5まで<>がない -> 表示しない */
              if(!thread[0] || !/^[0-9]{10}$/.test(thread[0]) || thread[5] == null) continue;
              
              subject += `${thread[0]}<>${thread[1]}<>${thread[2]}<>${thread[3]}<>${thread[4]}<>${thread[5]}\n`;
            }
          }else {
            lastModified = new Date();
          }
        }else error = "404";
      }catch(e) {
        error = `/${bbs}/kako/${kako}/subject.txt Error -> ${e}`;
      }

      if(!error) {
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        let headers = {};
        headers["max-age"] = "60";
        headers["s-maxage"] = "60";

        response = respondPlainText(subject, "plain", "UTF-8", "", lastModified ?? new Date(), ifModifiedSinceHeader, headers);
        
        /* Cache API */
        if (response.status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));

        return response;
      }else if(error === "404") return respond404(`/${bbs}/kako/${kako}/subject.txt`);
      else return respond500(error);
    }

    /* 倉庫一覧 */
    if(/^\/[a-zA-Z0-9\-_]+\/kako\/subject\.txt/.test(url.pathname)) {
      /* Cache API */
      const cacheKey = new Request(url.toString(), request);
      const cache = caches.default;
      let response = await cache.match(cacheKey);
      if (response) return response;

      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/kako\/subject\.txt/);
      const bbs = match[1];
      const kako = match[2];
      const filename = `${bbs}/thread/subject.txt`;
      let ifModifiedSinceHeader = "";
      let lastModified;
      let subject = "";
      let error = "";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          const subjectText = await object.text();
          const subjectArray = subjectText.split("\n");

          /* スレの配列に最低2個要素がないとおかしい */
          if(subjectArray.length > 1) {
            /* 最終更新時刻を取得。ログの一行目に入っている。 */
            lastModified = new Date(subjectArray.shift() * 1000);
            
            for(let i = 0; i < subjectArray.length; i++) {
              /* 0:key上位4桁<>1:作成時unixtime */
              const thread = subjectArray[i].trim().split("<>");

              /* 0無し、0が4桁の数字でない、1無し、1が10桁の数字でない -> 表示しない */
              if(!thread[0] || !/^[0-9]{4}$/.test(thread[0]) || !thread[1] || !/^[0-9]{10}$/.test(thread[1])) continue;
              
              subject += `${thread[0]}<>${thread[1]}\n`;
            }
          }else {
            lastModified = new Date();
          }
        }else error = "404";
      }catch(e) {
        error = `/${bbs}/kako/subject.txt Error -> ${e}`;
      }

      if(!error) {
        /* 最終更新時刻 */
        if(request.headers.get('if-modified-since')) ifModifiedSinceHeader = request.headers.get('if-modified-since');

        let headers = {};
        headers["max-age"] = "86400";
        headers["s-maxage"] = "86400";

        response = respondPlainText(subject, "plain", "UTF-8", "", lastModified ?? new Date(), ifModifiedSinceHeader, headers);

        /* Cache API */
        if (response.status === 200) ctx.waitUntil(cache.put(cacheKey, response.clone()));

        return response;
      }else if(error === "404") return respond404(`/${bbs}/kako/subject.txt`);
      else return respond500(error);
    }

    /* head.txt */
    if(/^\/[a-zA-Z0-9\-_]+\/head\.txt/.test(url.pathname)) {
      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/head\.txt/);
      const bbs = match[1];
      const filename = `${bbs}/head.txt`;
      const ENCODING = "Shift_JIS";
      let headTxt = "";
      let error = "";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          headTxt = await object.text();
        }
      }catch(e) {
        error = `/${bbs}/head.txt Error -> ${e}`;
      }

      if(!error) {
        return respondPlainText(headTxt, "plain", ENCODING);
      }else return respond500(error);
    }

    /* SETTING.TXT */
    if(/^\/[a-zA-Z0-9\-_]+\/SETTING\.TXT/.test(url.pathname)) {
      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/SETTING\.TXT/);
      const bbs = match[1];
      const filename = `${bbs}/SETTING.TXT`;
      const ENCODING = "Shift_JIS";
      let settingTxt = settingTxtDefault;
      let error = "";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          settingTxt = await object.text();
        }
        
        settingTxt = settingTxt.replace(/<DIR_NAME>/g, bbs); 
      }catch(e) {
        error = `/${bbs}/SETTING.TXT Error -> ${e}`;
      }

      if(!error) {
        return respondPlainText(settingTxt, "plain", ENCODING);
      }else return respond500(error);
    }

    // 板トップ（専ブラのみ）
    // 板名を出力する
    if (/^\/[a-zA-Z0-9\-_]+\/(index\.html)?$/.test(url.pathname)) {
      const match = url.pathname.match(/^\/([a-zA-Z0-9\-_]+)\/(index\.html)?$/);
      const bbs = match[1];
      const filename = `${bbs}/SETTING.TXT`;
      const ENCODING = "Shift_JIS";
      let settingTxt = settingTxtDefault;
      let error = "";
      let bbsTitle = bbs + "＠3ch掲示板";

      try {
        const object = await env.ch3www.get(filename);
        if(object !== null) {
          settingTxt = await object.text();
        }
        
        settingTxt = settingTxt.replace(/<DIR_NAME>/g, bbs);

        const settingsArray = settingTxt.trim().split("\n");

        for (let i = 0; i < settingsArray.length; i++) {
          const setting = settingsArray[i].split("=");
          if (!setting[0]) continue;
          setting[1] ??= "";
          if (setting[0] === "BBS_TITLE") {
            bbsTitle = setting[1].trim();
            break;
          }

        }
      } catch(e) {
        error = `/${bbs}/SETTING.TXT Error -> ${e}`;
      }

      if (!error) {
        let indexHtml = `<!DOCTYPE HTML><!-- 専ブラ用 旧仕様板top -->
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=Shift_JIS">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Cache-Control" content="no-cache">
<script type="text/javascript" src="https://code.jquery.com/jquery-2.2.4.min.js"></script>
<script>
/*
function dojQueryAjax() {
    $.ajax({
        type: "GET",
 
        url: "../test/headline.php?bbs=livegalileo",
 
        cache: false,

        success: function (data) {
        		$('#headline').html(data);
        },

        error: function () {
 
        }
    });
 
}
setTimeout(dojQueryAjax, 0);
setInterval(dojQueryAjax, 3000);
*/
</script>
<!--<script type="text/javascript" src="//www2.3ch.shop/snow/be.js" defer></script>-->
<script type="text/javascript"><!--
function l(e){
 var N=getCookie("NAME"),M=getCookie("MAIL"),i;
 with(document) for(i=0;i<forms.length;i++)if(forms[i].FROM&&forms[i].mail)with(forms[i]){
  FROM.value=N;
  mail.value=M;
 }
}
onload=l;
function getCookie(key, tmp1, tmp2, xx1, xx2, xx3) {
 tmp1 = " " + document.cookie + ";";
 while(tmp1.match(/\+/)) {tmp1 = tmp1.replace("+", " ");};
 xx1 = xx2 = 0;
 len = tmp1.length;
 while (xx1 < len) {
  xx2 = tmp1.indexOf(";", xx1);
  tmp2 = tmp1.substring(xx1 + 1, xx2);
  xx3 = tmp2.indexOf("=");
  if (tmp2.substring(0, xx3) == key) {return(unescape(tmp2.substring(xx3 + 1, xx2 - xx1 - 1)));}
  xx1 = xx2 + 1;
 }
 return("");
}
//--></script>
<style type="text/css"><!--
img {border:0;}
.post{margin-top:10px}
.number{display:inline-block}
.name{display:inline-block;margin-left:12px}
div.name{color:green;}
.email{display:inline-block;margin-left:5px}
.date{display:inline-block;margin-left:5px}
.message{margin-left:40px;clear:both}
a{ word-break: break-all; }
body { word-break: break-word; overflow-wrap: break-word; overscroll-behavior-y: contain; }
.AA {font-family:"ヒラギノ角ゴ Pro W3","Hiragino Kaku Gothic Pro",Osaka,"ＭＳ Ｐゴシック","MS PGothic",sans-serif; font-size: 16px;}
.rep-comment { background-color: lavender; border: 1px outset lavender; color: black; opacity: 0.95; position: absolute; text-decoration: none; left: 7em; padding-top: 5px; padding-left: 5px; padding-right: 5px; }
display:inline-block;
--></style>
<title>${bbsTitle}</title>
<!--nobanner-->
<!--<link href="https://cdnjs.cloudflare.com/ajax/libs/lightbox2/2.11.3/css/lightbox.css" rel="stylesheet">
<script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script>-->
</head>
<body bgcolor="#FFFFFF" text="#000000" link="#0000FF" alink="#FF0000" vlink="#660099" background="../ba.gif">
<center><a href="//3ch.shop"></a></center>
<table border="1" cellspacing="7" cellpadding="3" width="95%" bgcolor="#CCFFCC" align="center">
 <tr>
  <td align="center">
   <table border="0" cellpadding="1" width="100%">
    <tr>
     <td nowrap colspan="2"><font size="+1"><b>${bbsTitle}</b></font><br></td>
     <td nowrap="" width="5%" align="right" valign="top"><a href="../setting.cgi?bbs=livegalileo" style="background-color:dimgray;border:1px outset dimgray;color:palegreen;text-decoration:none;">閲覧設定</a>&nbsp;
        <a href="../test/history.cgi?bbs=livegalileo">履歴</a>&nbsp; <a href="#menu">■</a> <a href="#1">▼</a></td>
    </tr>
    <tr>
     <td colspan="3"></td>
    </tr>
   </table>
  </td>
 </tr>
</table>
<center><br>
<b>どのような形の削除依頼であれ公開させていただきます。</b>
</center>
<br>
<p style="margin: 0; padding: 0.25em 0; text-align: center; color: #333;">
<a href="//3ch.shop/">３ちゃんねる</a> BBS.CGI ver 1.07.427 - 2022/04/27 22:56 JST<br>
<a href="./SETTING.TXT">SETTING.TXT</a></p>
<div align="right" id="kanri"><a href="./admin/admin.php?bbs=livegalileo"><input type="submit" value="管理画面へ"></a></div>
</body>
</html>
`;
        return respondPlainText(indexHtml, "html", ENCODING);
      } else return respond500(error);
    }
    
    /* 無効なURL */
    return respond404(url.pathname);
  },
}

export { Board };
export { Thread };
