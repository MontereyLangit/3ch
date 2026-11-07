import path from "node:path";
import { defineConfig } from "vite";
import scalaJSPlugin from "@scala-js/vite-plugin-scalajs";
import tailwindcss from "@tailwindcss/vite";

// 管理画面の SPA（docs/admin-spa-auth-plan.md §5.2）。HonoX を使わず、dist/admin/ に起動ページと資産だけを出す。
// client・workers のビルドの後に実行する（workers のビルドが dist/ を空にしても消えないように）。
export default defineConfig({
  root: path.resolve(import.meta.dirname, "admin-web"),
  base: "/admin/",
  publicDir: false,
  plugins: [
    tailwindcss(),
    scalaJSPlugin({ cwd: path.resolve(import.meta.dirname), projectID: "adminClient" }),
  ],
  build: {
    outDir: path.resolve(import.meta.dirname, "dist/admin"),
    emptyOutDir: true,
    // Scala.js と React を 1 本にまとめるので既定の 500 kB を超える
    chunkSizeWarningLimit: 1024,
  },
});
