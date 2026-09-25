/**
 * 3chiko.html（read.cgi相当の閲覧UI）。
 * 元は別リポジトリ + Cloudflare Pages経由で配信していたが、v5へ統合した。
 * 本体は本リポジトリの public/3chiko.html（Workers Static Assets）として配信し、
 * ここでは複数のURLパターンからその1ファイルへ内部的に差し向けるだけの薄いラッパーとする。
 */
export async function respondView3chiko(env, request) {
  const assetUrl = new URL("/3chiko.html", request.url);
  return env.ASSETS.fetch(new Request(assetUrl, request));
}

/** リクエストのcookie/クエリから webclient パラメータを取得する */
export function getWebclientParam(request, url) {
  const queryValue = url.searchParams.get("webclient");
  if (queryValue) return queryValue;

  const cookieHeader = request.headers.get("cookie") ?? "";
  for (const pair of cookieHeader.split(/[;,]\s*/)) {
    const [key, val] = pair.split("=", 2);
    if (key === "webclient" && val) return val;
  }
  return "";
}
