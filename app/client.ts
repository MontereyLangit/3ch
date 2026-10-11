import "./style.css";
import { createClient } from "honox/client";
import { createElement, type ElementType, type ReactElement } from "react";
import { hydrateRoot } from "react-dom/client";
// @ts-expect-error scalajs:client.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。
import { v6ClientPing } from "scalajs:client.js";

console.log("[v6s]", v6ClientPing());

void createClient<ReactElement>({
  hydrate: (element, root) => {
    hydrateRoot(root, element);
  },
  createElement: (type: ElementType, props: Record<string, unknown>) =>
    createElement(type, props),
});
