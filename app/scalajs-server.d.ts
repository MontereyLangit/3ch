declare module "scalajs:server.js" {
  export function renderShell(
    children: import("react").ReactNode,
    title: string | undefined,
    clientScriptSrc: string,
  ): import("react").ReactElement;
}
