package com.example.veilark.protocol

enum class Protocol(
  val title: String,
  val subtitle: String,
) {
  Automatic("Автовыбор", "Адаптивная стратегия"),
  TrustTunnel("TrustTunnel", "HTTPS · H2/H3"),
  Hysteria2("Hysteria 2", "QUIC · UDP"),
  Trojan("Trojan", "TLS · TCP"),
  VlessReality("VLESS Reality", "Reality · TCP/gRPC"),
  Shadowsocks("Shadowsocks", "2022 · AEAD"),
  Tuic("TUIC", "QUIC · UDP"),
  WireGuard("WireGuard", "Нативный туннель"),
}
