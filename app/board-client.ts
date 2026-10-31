export function initializeBoardUi(root: Document, view: Window): void {
    const board = root.querySelector<HTMLElement>(".board-ui");
    const header = board?.querySelector<HTMLElement>(".site-header");
    board?.querySelectorAll<HTMLButtonElement>("[data-board-panel]").forEach(button => {
        button.addEventListener("click", () => {
            const panel = root.getElementById(button.dataset.boardPanel ?? "");
            if (!panel) return;
            panel.hidden = !panel.hidden;
            button.setAttribute("aria-expanded", String(!panel.hidden));
            const arrow = button.querySelector("span");
            if (arrow) arrow.textContent = panel.hidden ? "▸ " : "▾ ";
        });
    });
    if (board && header) {
        const place = () => board.style.setProperty("--header-height", `${header.classList.contains("header-hidden") ? 0 : header.offsetHeight}px`);
        header.addEventListener("focusin", () => {
            header.classList.remove("header-hidden");
            place();
        });
        header.querySelector<HTMLAnchorElement>('a[href="#board-search"]')?.addEventListener("click", event => {
            const input = root.querySelector<HTMLInputElement>("#board-search");
            if (!input) return;
            event.preventDefault();
            input.focus({preventScroll: true});
            input.scrollIntoView({block: "start"});
        });
        let lastY = view.scrollY;
        view.addEventListener("scroll", () => {
            const y = view.scrollY;
            if (Math.abs(y - lastY) < 5) return;
            header.classList.toggle("header-hidden", y > lastY && y > header.offsetHeight && !header.contains(root.activeElement));
            lastY = y;
            place();
        }, {passive: true});
        view.addEventListener("resize", place);
        place();
    }

    root.querySelectorAll<HTMLElement>(".board-ui [data-thread-key]").forEach(row => {
        const key = `3ch:threadSeen:${row.dataset.threadKey}`;
        const count = Number(row.dataset.res);
        const badge = row.querySelector<HTMLElement>(".new");
        try {
            const stored = view.localStorage.getItem(key);
            const seen = stored === null ? count : Number(stored);
            if (badge && Number.isFinite(seen) && count > seen) badge.textContent = `+${count - seen}`;
        } catch { /* Storage can be disabled by browser preferences. */
        }
        const markSeen = () => {
            try {
                view.localStorage.setItem(key, String(count));
            } catch { /* The link still works without storage. */
            }
        };
        const link = row.querySelector<HTMLAnchorElement>("a.thread-link");
        link?.addEventListener("click", markSeen);
        link?.addEventListener("auxclick", event => {
            if (event.button === 1) markSeen();
        });
    });
}
