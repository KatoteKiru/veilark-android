# Core shared policy config preparation

Status: preparation only, not activated by Android services. Native real TCP/UDP flow runtime is still required. This builder must not be advertised as working Core GEO support.

`CoreFlowRoutingConfig.build` takes an independent authenticated IPv4 loopback session, declared API v1 TCP/UDP capabilities and the existing routing/application selections. It obtains the same in-memory TUN/DNS template as SubscriptionParser, supplies only SOCKS `auto` plus `direct`, and reuses `ProfileSelection.applyRouting` and `applyApplications`. Subscription imports, user config persistence, enrollment secrets and active engine ownership are not involved. Original subscription auto-selector/probe fields and output order remain unchanged by the template extraction.

The Core-specific prefix rejects IPv6 before any Direct selection, sniffs, hijacks DNS and resolves destinations to IPv4 using the existing selected DNS policy. Default and secure DNS go through Core; ready RU and direct geosite DNS branches continue using bootstrap as selected by existing policy. SOCKS proxy host domain_resolver alone does not resolve CONNECT targets: upstream SOCKS5 sends domain targets unchanged, so the explicit route resolve action is necessary. Proxy rules disable UDP domain unmapping to preserve IP-only native destination framing. Both TCP and standard UDP ASSOCIATE remain enabled; UoT and TLS fragmentation are unsupported. Existing ready RU rule paths and matching policy remain unchanged for IPv4; IPv6 is explicitly rejected for this Core scope.

RFC1929 username/password are bounded printable ASCII, 1..255 bytes; owner must generate independent random session credentials and must not derive them from enrollment. Endpoint is literal 127.0.0.1 with a valid nonzero port. Credential/config wrappers redact toString; ephemeral JSON is exposed only through useJson and must never enter diagnostics or profile stores. This does not claim JVM String zeroization.

Actual `Libbox.checkConfig` is a mandatory production validator callback before returning prepared config. Unit tests use test validators and prove policy boundaries, not native schema support. Bundled libbox.aar SHA256 B82148CCE2853BFC3C0796F0C32D057229F4C6B45103BF86B53A59B66BA71EF4 contains Android ARM64/ARMv7 JNI libraries and embedded Go module version `(devel)`; exact semantic version is unavailable from that metadata. Android JNI validation against that exact artifact, actual flow TCP/UDP traffic, carrier protection, bounded stop and physical acceptance remain open. No service wiring or release is authorized by this preparation alone.

Primary schema/behavior references examined:
- https://sing-box.sagernet.org/configuration/outbound/socks/
- https://sing-box.sagernet.org/configuration/route/rule_action/
- https://sing-box.sagernet.org/configuration/shared/dial/
- https://github.com/SagerNet/sing-box/blob/v1.12.0/protocol/socks/outbound.go

The references support the preparation design; they do not establish the bundled `(devel)` artifact's runtime acceptance.
