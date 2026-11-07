package io._3ch.admin.components

/** 管理画面の部品のクラス。値は板トップとモック（docs/ui/auth-admin.css）に合わせる */
object Styles {
  val button = "cursor-pointer border border-[#d6dce3] bg-white px-2.5 py-1 text-[#333] hover:border-[#b9c4d0] hover:text-[#2464b5] disabled:cursor-not-allowed disabled:opacity-50"
  val danger = "cursor-pointer border border-[#e8a0a0] bg-white px-2.5 py-1 text-[#c00] hover:border-[#c00] disabled:cursor-not-allowed disabled:opacity-50"
  val primary = "cursor-pointer border border-[#f55] bg-[#f66] px-[18px] py-[7px] font-bold text-white hover:bg-[#e95555] disabled:cursor-not-allowed disabled:border-[#f3b3b3] disabled:bg-[#f3b3b3]"
  val textButton = "cursor-pointer border-0 bg-transparent px-1 py-[3px] text-xs text-[#333] hover:text-[#2464b5]"
  val input = "w-full max-w-full border border-[#d6dce3] bg-white px-2.5 py-1 text-sm text-[#333] hover:border-[#b9c4d0]"
  val inputBad = "w-full max-w-full border border-[#e07070] bg-[#fff6f6] px-2.5 py-1 text-sm text-[#333]"
  val select = "max-w-full border border-[#d6dce3] bg-white px-[7px] py-[5px] text-[13px] text-[#465565] hover:border-[#b9c4d0]"
  val textarea = "block w-full border border-[#d6dce3] bg-white px-2.5 py-1 font-mono text-[13px] text-[#333] hover:border-[#b9c4d0]"
  val panel = "mb-3 border border-[#dce1e6] bg-white p-[15px] text-[13px]"
  val h1 = "my-2 text-lg font-bold"
  val h2 = "mb-2 text-sm font-bold"
  val small = "text-xs font-normal text-[#697785]"
  val notice = "text-xs leading-normal text-[#697785]"
  val error = "text-xs text-[#d00]"
  val link = "cursor-pointer text-[#06f] no-underline hover:underline"
  val pre = "m-0 max-h-[480px] overflow-auto whitespace-pre-wrap break-all border border-[#e3e7eb] bg-[#f7f8fa] px-2.5 py-2 text-xs"
  val help = "mt-1.5 border border-[#dce1e6] bg-white px-3 py-2 text-xs leading-relaxed text-[#465565]"
  val tag = "inline-block self-start border border-[#d6dce3] px-1.5 text-[11px] font-normal leading-4 text-[#465565]"
  val tagExplicit = "inline-block self-start border border-[#9cc3f2] px-1.5 text-[11px] font-normal leading-4 text-[#2464b5]"
}
