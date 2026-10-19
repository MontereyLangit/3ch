import { createRoute } from "honox/factory";
// @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin.
import { publicHandler } from "scalajs:server.js";

export default createRoute((c) => publicHandler(c));
