import "./style.css";
import {createClient} from "honox/client";
import {islandPropsProblem} from "hx4s-vite/client";
import {createElement, type ElementType, type ReactElement} from "react";
import {hydrateRoot} from "react-dom/client";

void createClient<ReactElement>({
  hydrate: (element, root) => {
    // HTML と client の世代がずれて props が合わないときは hydrate せず、SSR の markup を残す（template 内の island も確かめる）
    const problem = islandPropsProblem(element);
    if (problem !== undefined) {
      console.error(`[hx4s] skipped hydration of ${root.getAttribute("component-name")}: ${problem}`);
      return;
    }
    hydrateRoot(root, element);
  },
  createElement: (type: ElementType, props: Record<string, unknown>) =>
    createElement(type, props),
});
