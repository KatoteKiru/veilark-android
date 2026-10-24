# Import intent lifecycle repair - 24 October 2026

MainActivity previously parsed an external import URI without consuming its
Intent. After the transient UI request was cleared, Activity recreation could
parse the retained URI again. The Activity also retained a plaintext copy of
the bearer in its data and potentially duplicated ClipData/EXTRA_TEXT.

Both cold-start and onNewIntent now use ImportDeepLink.consume: recognized
imports are transferred to transient state, then data, ClipData, EXTRA_TEXT and
action are cleared. A consumed Intent cannot replay the import. Unrecognized
URIs and file-import grants remain untouched. This removes app-owned copies;
it cannot erase copies held by the sender or Android's task manager.

Actual API35 emulator instrumentation: two cases PASS. The first covers new
protected enrollment and both legacy compact/wrapped links, one-time transfer,
ClipData/text removal and a recreated Intent. The second checks content-file
and unrecognized-URI preservation, including read grants. Synthetic public
tokens only: no network, real profile, VPN start, or publication.

The x86_64 test-only app and test APK compiled offline. Frozen app SHA256:
4e31366149b1e18c949979353bbfa9ccb771e1cad3387a762841615bf4ef72e9.
Test APK SHA256:
a14717b8c4621efb461e4d46e3e129cb105ad12cadb43db9fcc3e8b89ed2e550.
Raw log SHA256:
a9cda10503a0b32e450562550650f661cafd99a1f5e22b2b740ac64520e53299.
Workspace receipt: reports/enrollment-bootstrap-20261023/intent-consumption.json.
This is not real HTTPS import acceptance or OTA approval; OTA83 is unchanged.
