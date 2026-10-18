# Own Veilark routing scenarios

HAPP is a usability analogy only. Veilark does not import its format or GEO files.

**Russia direct remains the existing ready-to-use preset.** Its packaged GEO
files, routing semantics and engine-specific behavior are preserved. Own scenarios
are a separate manual option; creating/saving/importing one does not replace or
modify the preset. The user can still select Russia direct without creating a
scenario or downloading files.

In Routing, open **My GEO filter**. Enter a name, domains/IP ranges for direct
traffic and VPN traffic. Validate to preview normalized rules and counts.
Save keeps a named scenario locally; saved scenarios can be opened, edited,
replaced by the same exact name, or deleted. **Use these rules** fills the routing
draft. Only the enclosing **Apply** changes traffic, using existing sing-box or
TrustTunnel validation. An active connection requires reconnect afterwards.
Other traffic goes through VPN; existing application/DPI settings are retained.

## GitHub data inputs

- Public GitHub blob or raw HTTPS `.json`: own Veilark scenario, replaces the
  editable preview only.
- Public GitHub `.txt`: one suffix domain or IP/CIDR per line; blank lines and
  lines beginning with `#` are ignored. Choose VPN (default) or Direct before
  import. The validated list is added to that draft group.

No repository checkout, scripting, includes, arbitrary commands, authentication,
URL query tokens or redirects are supported. Unknown/invalid rule syntax fails
explicitly. Binary `.dat`/`.srs`, category includes and foreign settings cannot
be substituted silently. Imports are bounded to 256 KiB, previews to 2000 rules;
32 saved scenarios share a 1 MiB atomic app-private store. Failed/corrupt writes
retain the previous store. This saved file contains rules, never VPN credentials.

Example own scenario:

```json
{
  "schema": "veilark-custom-geo-v1",
  "name": "My scenario",
  "direct": ["bank.example", "192.0.2.0/24"],
  "vpn": ["service.example"]
}
```

These are documentation-only domains and networks. Choose actual rules yourself.

## Managed GEO update status

The routing screen also shows the managed GEO update result, last attempt, last
successful update and source generation. Manual refresh retains existing verified
files after an error or interruption. Dates/results survive app restart; previewing
or saving an own scenario does not refresh or replace managed GEO databases.

Own scenarios currently save the rule groups, not a full snapshot of per-app,
DPI or DNS settings. GitHub rules are imported snapshots; automatic source refresh
is not implemented. UI/device acceptance remains separate from JVM validation.
