// HonoX公式README「BYOR - React case」どおりのclient.ts。v6s骨格の時点ではhydrateすべき
// island コンポーネントがまだ無いため、`bbs-client`（`ch3s.bbsclient.ClientEntry`）が
// `client.js` モジュールへ実際にexportされていることだけを確かめる（次タスクで最初のislandが
// 増えたら、参照実装 ch3s-migration の client.ts（hydrateRoot経由のReact hydration）に置き換える）。
import "./style.css";
// @ts-expect-error scalajs:client.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール（.d.ts無し）。
import { v6ClientPing } from "scalajs:client.js";

console.log("[v6s]", v6ClientPing());
