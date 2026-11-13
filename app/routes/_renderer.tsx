import { reactRenderer } from "@hono/react-renderer";
import { renderShell } from "scalajs:server.js";

const clientScriptSrc = import.meta.env.PROD ? "/static/client.js" : "/app/client.ts";

export default reactRenderer(({ children, title, authModal }) => renderShell(children, title, clientScriptSrc, authModal));
