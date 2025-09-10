export const bbsCgiErrorTemplete = model => `<font size=+1 color=#FF0000><b>ERROR: ${model.errMsg}</b></font>
<ul>
<br>ホスト<b>${model.host}</B><br><b>${model.form.subject} </b><br>
名前： <b>${model.form.FROM}</b><br>E-mail： ${model.form.mail}<br>
内容：<br>${model.form.MESSAGE}<br><br>
</ul>
<a href="https://wiki.3chbbs.com/index.php/%E3%82%A2%E3%82%AF%E3%82%BB%E3%82%B9%E8%A6%8F%E5%88%B6%E4%B8%AD%E3%81%A7%E3%82%82%E6%9B%B8%E3%81%91%E3%82%8B%E6%9D%BF">★ アクセス規制中でも書ける板 ★</a><br><br>
<hr>
こちらでリロードしてください。<a href="/test/read.cgi/${model.bbs}/${model.key}/l50"> GO! </a><br>
自分で解決してみよう! <a href="https://wiki.3chbbs.com/index.php/%E6%9B%B8%E3%81%8D%E8%BE%BC%E3%82%81%E3%81%AA%E3%81%84%E6%99%82%E3%81%AE%E6%97%A9%E8%A6%8B%E8%A1%A8">書き込めない時の早見表</a><br>
分からないことがあったら<a href="https://www.3chbbs.com/html/guide/">３ちゃんねるガイド</a>へ。。。<br><br>

<p>
`;
