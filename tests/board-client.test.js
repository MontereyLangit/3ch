import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {JSDOM} from 'jsdom';
import ts from 'typescript';

const source = readFileSync(new URL('../app/board-client.ts', import.meta.url), 'utf8');
const compiled = ts.transpileModule(source, {
    compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ES2022
    }
}).outputText;
const {initializeBoardUi} = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);

function fixture() {
    const dom = new JSDOM(`<div class="board-ui"><header class="site-header"><a href="#board-search">検索</a><button>メニュー</button></header><input id="board-search"><div data-thread-key="sample/1" data-res="5"><a class="thread-link" href="#thread">スレ</a><span class="new"></span></div></div>`, {url: 'https://board.example/sample/'});
    const {window} = dom;
    const {document} = window;
    Object.defineProperty(document.querySelector('header'), 'offsetHeight', {value: 64});
    let scrolls = 0;
    document.querySelector('input').scrollIntoView = () => {
        scrolls++;
    };
    const scroll = y => {
        Object.defineProperty(window, 'scrollY', {value: y, configurable: true});
        window.dispatchEvent(new window.Event('scroll'));
    };
    return {window, document, dom, scroll, scrollCount: () => scrolls};
}

test('スレ閲覧履歴は左クリックと中クリックで記録し、右クリックでは記録しない', () => {
    const ui = fixture();
    try {
        const key = '3ch:threadSeen:sample/1';
        ui.window.localStorage.setItem(key, '2');
        initializeBoardUi(ui.document, ui.window);
        assert.equal(ui.document.querySelector('.new').textContent, '+3');
        const link = ui.document.querySelector('.thread-link');
        link.dispatchEvent(new ui.window.MouseEvent('auxclick', {button: 2}));
        assert.equal(ui.window.localStorage.getItem(key), '2');
        link.dispatchEvent(new ui.window.MouseEvent('auxclick', {button: 1}));
        assert.equal(ui.window.localStorage.getItem(key), '5');
        ui.window.localStorage.setItem(key, '2');
        link.dispatchEvent(new ui.window.MouseEvent('click', {button: 0}));
        assert.equal(ui.window.localStorage.getItem(key), '5');
    } finally {
        ui.dom.window.close();
    }
});

test('隠れたヘッダーはキーボードフォーカスで現れ、フォーカス中はスクロールしても隠れない', () => {
    const ui = fixture();
    try {
        initializeBoardUi(ui.document, ui.window);
        const header = ui.document.querySelector('header');
        ui.scroll(100);
        assert.equal(header.classList.contains('header-hidden'), true);
        ui.document.querySelector('header button').focus();
        assert.equal(header.classList.contains('header-hidden'), false);
        assert.equal(ui.document.querySelector('.board-ui').style.getPropertyValue('--header-height'), '64px');
        ui.scroll(200);
        assert.equal(header.classList.contains('header-hidden'), false);
        ui.document.querySelector('input').focus();
        ui.scroll(300);
        assert.equal(header.classList.contains('header-hidden'), true);
    } finally {
        ui.dom.window.close();
    }
});

test('ヘッダーの検索リンクは入力欄へフォーカスを移してスクロールする', () => {
    const ui = fixture();
    try {
        initializeBoardUi(ui.document, ui.window);
        const event = new ui.window.MouseEvent('click', {bubbles: true, cancelable: true});
        ui.document.querySelector('header a').dispatchEvent(event);
        assert.equal(event.defaultPrevented, true);
        assert.equal(ui.document.activeElement, ui.document.querySelector('input'));
        assert.equal(ui.scrollCount(), 1);
    } finally {
        ui.dom.window.close();
    }
});
