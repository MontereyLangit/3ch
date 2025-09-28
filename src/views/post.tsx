import { FC } from 'hono/jsx';
import { jsx } from 'hono/jsx/jsx-runtime';
import { PostViewModel } from '@/model/view/PostViewModel';

const PostSuccessTemplate: FC<{ model: PostViewModel }> = ({ model }) => (
    <main>
        書きこみが終わりました。<br /><br />
        画面を切り替えるまでしばらくお待ち下さい。<br /><br />
        <br /><br /><br /><br /><br />
    </main>
);

const PostConfirmationTemplate: FC<{ model: PostViewModel }> = ({ model }) => (
    <main>
        <font size="+1" color="#FF0000"><b>書きこみ＆クッキー確認</b></font>
        <ul><br /><br />
            <b>{model.subject} </b><br />
            名前： <b>{model.FROM}</b><br />
            E-mail： {model.mail}<br />
            内容：<br />{model.MESSAGE}<br /><br />
        </ul>
        <p>
            投稿確認<br />
            {model.confirmationText}
        </p>
        変更する場合はキャンセルして書き直して下さい。<br /><br />
        現在、荒らし対策でクッキーを設定していないと書きこみできないようにしています。<br />
        <font size="-1">(cookieを設定するとこの画面はでなくなります。)</font><br />
    </main>
);

const PostErrorTemplate: FC<{ model: PostViewModel }> = ({ model }) => (
    <main>
        <font size="+1" color="#FF0000"><b>ERROR: {model.errMsg}</b></font>
        <ul>
            <br />ホスト<b>{model.host}</b><br />
            <b>{model.subject} </b><br />
            名前： <b>{model.FROM}</b><br />E-mail： {model.mail}<br />
            内容：<br />{model.MESSAGE}<br /><br />
        </ul>
        <a href="https://wiki.3chbbs.com/index.php/%E3%82%A2%E3%82%AF%E3%82%BB%E3%82%B9%E8%A6%8F%E5%88%B6%E4%B8%AD%E3%81%A7%E3%82%82%E6%9B%B8%E3%81%91%E3%82%8B%E6%9D%BF">★ アクセス規制中でも書ける板 ★</a><br /><br />
        <hr />
        こちらでリロードしてください。<a href={`/test/read.cgi/${model.bbs}/${model.key}/l50`}> GO! </a><br />
        自分で解決してみよう! <a href="https://wiki.3chbbs.com/index.php/%E6%9B%B8%E3%81%8D%E8%BE%BC%E3%82%81%E3%81%AA%E3%81%84%E6%99%82%E3%81%AE%E6%97%A9%E8%A6%8B%E8%A1%A8">書き込めない時の早見表</a><br />
        分からないことがあったら<a href="https://www.3chbbs.com/html/guide/">３ちゃんねるガイド</a>へ。。。<br /><br />
        <p></p>
    </main>
);

export const ViewPost: FC<{ model: PostViewModel }> = ({ model }) => {
    const hasErrors = model.hasErrors();
    const isConfirmation = model.status === "confirmation";

    const title = hasErrors
        ? (isConfirmation ? "■ 書き込み確認 ■" : "ＥＲＲＯＲ！")
        : "書きこみました。";

    const bodyContent = hasErrors
        ? (isConfirmation ? <PostConfirmationTemplate model={model} /> : <PostErrorTemplate model={model} />)
        : <PostSuccessTemplate model={model} />;

    return (
        <html>
            {hasErrors && (isConfirmation ? jsx('!-- 2ch_X:cookie --', {}) : jsx('!-- 2ch_X:error --', {}))}
            <head>
                <title>{title}</title>
                <meta http-equiv="Content-Type" content={`text/html; charset=${model.charset}`} />
                {!hasErrors && <meta http-equiv="refresh" content={`5;URL=/test/read.cgi/${model.bbs}/${model.key}/l50`} />}
                <meta name="viewport" content="width=device-width,initial-scale=1.0,minimum-scale=1.0,maximum-scale=1.6,user-scalable=yes" />
            </head>
            <body>
                {bodyContent}
            </body>
        </html>
    );
};
