package io._3ch.common.domain.device

/** 認証時に端末情報を受け付けない理由 */
enum DeviceRejection(val code: String) {
  case Fingerprint extends DeviceRejection("fingerprint")
  case DeclarationMismatch extends DeviceRejection("declaration_mismatch")
}
