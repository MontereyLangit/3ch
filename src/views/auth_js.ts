import { AuthViewModel } from '../model/auth_view_model';

// config.jsからインポートする値をここで定義（実際のパスはプロジェクト構成に合わせてください）
const ConfirmationText = "投稿には認証が必要です。";
const turnstileSiteKey = "0x4AAAAAABribdBm9BQV21jp"; // This should be managed securely

export const viewAuthJs = (model: AuthViewModel): string => {
    if (model.error) {
        return `
if (document.getElementById("auth")) {
  document.getElementById("auth").innerHTML = "認証に失敗しました（${model.error}）";
}
`;
    }

    // prettier-ignore
    return `
if (document.getElementById("auth")) {
  document.getElementById("auth").innerHTML = '<div><font size="+1" color="#FF0000"><b>投稿確認＆認証</b></font><br>現在、荒らし対策で認証を行わないと書きこみできないようにしています。<br>以下の内容に同意できる場合のみ、認証を行ってください。<br>${ConfirmationText}</div><form id="authForm" action="javascript:void(0);"><div>認証コード:<input name="validate"></div> <div class="cf-turnstile" data-sitekey="${turnstileSiteKey}" data-callback="onTurnstileSuccess"></div><button type="submit">認証</button></form>';
  
  (function() {
    const script = document.createElement('script');
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js';
    script.async = true;
    document.head.appendChild(script);
  })();
}

document.getElementById("authForm")?.addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target as HTMLFormElement;
  const formData = new FormData(form);

  const hap = location.hash.startsWith("#!") ? location.hash.slice(2) : "";
  formData.append("hap", hap);

  const deviceInformation = createDeviceInformationString();
  formData.append("d", deviceInformation);

  const cert = await createCertFromFormData(formData);
  formData.append("cert", cert);

  // time, token, authSig
  formData.append("time", "${model.time}");
  formData.append("token", "${model.token}");
  formData.append("authSig", "${model.authSig}");

  const response = await fetch("/api/auth", {
    method: "POST",
    body: new URLSearchParams(Array.from(formData.entries()) as [string, string][])
  });
  const result = await response.text();
  const authDiv = document.getElementById("auth");
  if (authDiv) {
    authDiv.innerHTML = result;
    if (result === "認証成功") {
      authDiv.style.display = "none";
    }
  }
});

function createDeviceInformationString() {
  const d1 = window.screen.width + "x" + window.screen.height + "@" + window.devicePixelRatio + "_" + navigator.maxTouchPoints + "_" + window.screen.colorDepth;
  let d2 = '???', d3 = '???';
  try {
    const canvas = document.createElement('canvas');
    const gl = canvas.getContext('webgl');
    const debugInfo = gl.getExtension('WEBGL_debug_renderer_info');
    d2 = gl.getParameter(debugInfo.UNMASKED_VENDOR_WEBGL) + "|" + gl.getParameter(debugInfo.UNMASKED_RENDERER_WEBGL);
    const ctx = canvas.getContext('2d');
    ctx.textBaseline = 'top';
    ctx.font = '14px Arial';
    ctx.fillText('ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890あ', 2, 2);
    d3 = canvas.toDataURL();
  } catch (e) { /* ignore */ }
  return d1 + "_" + d2 + "_" + d3;
}

async function createCertFromFormData(formData) {
  const concatenated = Array.from(formData.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => key + "=" + value).join("\\n");
  const hashBuffer = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(concatenated));
  return Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
`;
};
