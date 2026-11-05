import "./style.css";
import {createClient} from "honox/client";
import {createElement, type ElementType, type ReactElement} from "react";
import {hydrateRoot} from "react-dom/client";

void createClient<ReactElement>({
  hydrate: (element, root) => {
    hydrateRoot(root, element);
  },
  createElement: (type: ElementType, props: Record<string, unknown>) =>
    createElement(type, props),
});
