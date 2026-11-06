declare module "scalajs:server.js" {
  export function authApi(request: Request, env: unknown): Promise<Response>;
  export function acceptClientHints(response: Response): Response;
  export function renderShell(
    children: import("react").ReactNode,
    title: string | undefined,
    clientScriptSrc: string,
  ): import("react").ReactElement;
}
