export const viewBbsHtml = model => `<!DOCTYPE HTML>
<!--
専ブラ用 旧仕様板top /<bbs>/
-->
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=Shift_JIS">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Cache-Control" content="no-cache">
<script>
// Webブラウザはjsで /boards/<bbs>/ にリダイレクトすること
// 専ブラはjsを解釈しないため、チェック不要
location.href = "/boards/${model.bbs}/";
</script>
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
<title>${model.bbsTitle}</title>
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
     <td nowrap colspan="2"><font size="+1"><b>${model.bbsTitle}</b></font><br></td>
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
