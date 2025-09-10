export const bbsCgiComfirmationTemplete = model => `<font size=+1 color=#FF0000><b>書きこみ＆クッキー確認</b></font>
<ul><br><br>
<b>${model.form.subject} </b><br>
名前： <b>${model.form.FROM}</b><br>
E-mail： ${model.form.mail}<br>
内容：<br>${model.form.MESSAGE}<br><br></ul>
<b>
投稿確認<br>
${model.confirmationText}
</b>
(3chiko.htmlの場合はリロード（再読み込み）してください)<br>
変更する場合はキャンセルして書き直して下さい。<br><br>
現在、荒らし対策でクッキーを設定していないと書きこみできないようにしています。<br>
<font size=-1>(cookieを設定するとこの画面はでなくなります。)</font><br>
`;
