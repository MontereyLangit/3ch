declare module "scalajs:server.js" {
  // Workers が生成する DO。TS 側ではエントリポイントへの再 export のみ行う。
  export class V6PlaceholderDurableObject {
    constructor(state: object, env: unknown);
  }
  export class Board {
    constructor(state: object, env: unknown);
  }
  export class Thread {
    constructor(state: object, env: object);
  }

  export function renderShell(
    children: import("react").ReactNode,
    title: string | undefined,
    clientScriptSrc: string,
  ): import("react").ReactElement;
}
