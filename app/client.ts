// HonoX公式README「BYOR - React case」どおりのclient.ts。v6s骨格の時点ではhydrateすべき
// island コンポーネントがまだ無いため、`bbs-client`（`ch3s.bbsclient.ClientEntry`）が
// `client.js` モジュールへ実際にexportされていることだけを確かめる（次タスクで最初のislandが
// 増えたら、参照実装 ch3s-migration の client.ts（hydrateRoot経由のReact hydration）に置き換える）。
import "./style.css";
// @ts-expect-error scalajs:client.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。
import { v6ClientPing } from "scalajs:client.js";

console.log("[v6s]", v6ClientPing());

// TODO: package.jsonにdependencies:     "react": "19.2.8",     "react-dom": "19.2.8",
// devDependencies:    "@types/react": "^19",     "@types/react-dom": "^19"を足す

// import { createClient } from "honox/client";

// createClient({
//   hydrate: async (elem, root) => {
//     const { hydrateRoot } = await import("react-dom/client");
//     hydrateRoot(root, elem);
//   },
//   createElement: async (type: any, props: any) => {
//     const { createElement } = await import("react");
//     return createElement(type, props);
//   },
// });
