import { Context } from "hono";
import { BbsCgiViewModel } from "@/model/bbs_cgi_view_model";

/* 
 * bbs.cgiサービス
 * @param {Context<{ Bindings: CloudflareBindings }>} c
 * @param {BbsCgiViewModel} model
 */
export async function post(c: Context<{ Bindings: CloudflareBindings }>, model: BbsCgiViewModel) {

}
