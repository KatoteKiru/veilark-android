# Protected subscription enrollment: joint review draft

23 October 2026. Atlas proposal with Vector's conditional composition review.
**Not frozen, implemented, enabled or released.** OTA83, legacy import and deployed wire1 remain unchanged.
This is application provisioning, not a replacement VPN carrier or routing plan.

## Decision proposed

Use a signed profile inside an encrypted delivery object: JWS Compact Ed25519,
then JWE Compact `dir` / `A256GCM`. Use existing reviewed cryptographic primitives;
do not write AES, Ed25519 or a general algorithm-negotiation framework.
The receiver accepts exactly this suite, never algorithms chosen by the sender.

This deliberately proposes one refinement to the older carrier-host draft:
standard JWS signs its encoded protected header and payload, without a custom
prefix. Purpose separation instead uses a dedicated issuer key, an exact signed
`typ`, and an exact signed payload purpose. Vector accepted that refinement;
the remaining lifecycle gates still precede byte freeze. Appending a custom prefix while calling it standard JWS
would be incorrect. A separately designed prefix format is not a second fallback.
Vector accepted this refinement in the joint review; lifecycle/bootstrap and
matched-consumer gates below still prevent byte freeze or activation.

## Threat boundary and trust bootstrap

- An authenticated app release embeds `(issuer, kid) -> Ed25519 public key`
  and an allowlist of delivery origins. Separate keys/purpose from OTA signing.
  The object cannot add a key, URL, certificate, trust root or algorithm.
- The operator issuing a pre-generated private profile already knows that secret.
  Delivery encryption does not undo that. Device-generated enrollment is a
  separate future transaction, not an implicit property of this portable format.
- A full enrollment link is a bearer secret: whoever receives it can decrypt and
  copy the profile. AEAD protects ciphertext; issuer signature prevents a link
  holder who knows the encryption key from substituting their own endpoint.
- Fetcher compromise without the issuer signing key cannot mint a trusted
  profile. It can withhold, replay or correlate downloads. Compromised phones,
  screenshots, clipboard managers and full-link sharing remain outside that claim.
- Offline signatures do not provide immediate revocation. Server-side credential
  revocation is authoritative; deleting a link alone does not revoke an imported
  profile. This proposal makes no invisibility or domain-anonymity claim.

## Link and fetch lifecycle

Proposed new handler (placeholder values, not a usable credential):
`veilark://enroll/v2?t=<43-char locator>#k=<43-char delivery key>`.

Both values encode independent CSPRNG 32-byte values with canonical unpadded
base64url. Exactly one `t` and one fragment `k`; no unknown fields, percent
escapes, alternate hosts/paths, padding or whitespace. Bound the complete URI to
160 ASCII bytes. Existing `import?t=` / `?code=` retain their old meanings.
An old app must report unsupported enrollment, never decode it as legacy JSON.

The app maps the locator to ONE embedded active HTTPS delivery origin and a fixed
enrollment path; it never uses an arbitrary URL from the link or decrypted data.
The new link has no issuer/origin selector: multiple origins or trial fetches
would be ambiguous before decryption. The existing Android origin and operator
publisher agree on `https://nl2.senyasenyavski.uk:2096/veilark/`. Proposed v2 fetch
is that exact origin/path prefix plus `hex(decodedLocator) + ".jwe"`, lower-case
64-character hex. Legacy `hex(token) + ".json"` remains untouched. `.jwe` and
`.json` are distinct immutable objects; never try one when the other fails.
This is a concrete candidate using the existing delivery origin, not a deployed
route/file. Vector accepted this exact mapping for the draft operator pilot;
that is not public activation or permission to overwrite legacy files.
The 32-byte delivery key stays in the app: never attach it to HTTP, Referer,
redirects, analytics, exception messages or browser navigation. No redirects,
automatic cross-origin fallback, WebView or third-party QR service. A browser
landing page is outside this change and needs a separate reviewed flow.

The fetch endpoint returns only JWE bytes with a 65,536-byte streaming limit,
finite connect/read/overall deadlines, no transparent decompression, and no
authenticated session cookie forwarded from another service. Failed import
does not consume a one-time link: bounded retries must survive interrupted fetch.
Rate limits and authenticated issuance/revocation belong to the backend contract.
Do not log the locator either. Backend retention must separate ciphertext/locator
from the delivery key after issuance, including logs and backups; any re-display
feature must explicitly account for key retention rather than claim zero knowledge.

### Issuance transaction proposed for backend review

Initial pilot scope is a manually invoked authenticated operator publisher only.
It must explicitly authorize the local operator, create a new immutable object,
read back its exact SHA-256, and keep a durable request/revision receipt for crash
recovery. This does not assume a customer API, identity database or key vault.
The customer-API transaction below is a future extension, not a pilot prerequisite
implemented by merely renaming the current static publisher. The pilot must
either implement bounded protected recovery custody or report issued/key
unavailable and require explicit reissue with a higher revision. Never silently
regenerate a key or object under the same locator/revision after a crash.

An authenticated owner requests issuance using a random request ID. A unique
`(owner,request_id)` record binds the exact normalized request digest; retries
with changed parameters conflict. The backend verifies profile ownership before
reserving its next revision and immutable locator. Concurrent requests serialize
revision allocation. Publication is forbidden until signed bytes/ciphertext and
the issuance record are durably committed; an incomplete signed candidate is not
a published subscription. A crash after commit must retry publication of the SAME
object, not sign new content under the reserved revision. Credential provisioning
is a separately tracked transaction, never an implicit side effect of reissuing
an enrollment link. Compensation cannot revoke another revision's credentials.

To replay the identical link after a lost response, the backend must retain its
delivery key temporarily. Proposed policy: encrypted at rest under a backend
secret-management key, accessible only to the authenticated issuing owner for a
fixed 10-minute retry window. Purge that recoverable key after the window; retain
the nonsecret issuance receipt and immutable ciphertext. Later retries report
already issued/key unavailable; an explicit new request creates a higher revision
for the same profile ID, never a duplicate account/profile or silent key rotation.
This is disclosed temporary key custody, not zero knowledge. Retention, publication
worker and crash recovery must be agreed and implemented before activating v2.

## Exact candidate cryptographic bytes

`b64(x)` means canonical unpadded base64url; `ASCII` is byte encoding, not JSON
serialization. UTF-8 is strict, no BOM. All limits include actual encoded bytes.

Inner JWS has exactly three segments `H.P.S`:

- `H = b64(UTF8(protected header))`, exact field set `alg`, `kid`, `typ`.
  `alg` is **`Ed25519`**, not polymorphic `EdDSA`; `typ` is exactly
  `veilark-profile+jws;v=2`. `kid` matches `[A-Za-z0-9_-]{1,48}`.
- `P = b64(payloadBytes)`; payload bytes are the exact UTF-8 JSON signed by the
  issuer. Verify `Ed25519(ASCII(H + "." + P))`, 64-byte signature, using only
  the embedded `(iss,kid)` trust map. Never reserialize JSON before verification.
- No `none`, `jku`, embedded `jwk`, `x5u`, unprotected headers, detached payload,
  `b64:false`, compression or additional algorithms. Header decoded <=512 bytes;
  payload decoded <=24,576 bytes; inner compact ASCII <=34,000 bytes.

Outer JWE has exactly five segments `EH..IV.C.T` (empty encrypted-key segment).
The decoded protected header is **exactly** the following UTF-8 bytes, without
spaces/newline; require its canonical base64url encoding:

```json
{"alg":"dir","enc":"A256GCM","typ":"veilark-enrollment+jwe;v=2","cty":"veilark-profile+jws;v=2"}
```

CEK = the 32-byte delivery key. IV = fresh independent CSPRNG 12 bytes; tag =
16 bytes. Plaintext is the exact inner JWS ASCII. AAD = `ASCII(EH)`, as required
by JWE Compact. Ciphertext length equals plaintext length and is <=34,000 bytes.
Exporter creates a fresh key AND IV for every new/reissued delivery object;
never encrypt different data under a reused key/IV. Serving the same immutable
object again is a retry, not another encryption operation. Fixed IV/key are
permitted only in clearly synthetic checked-in interoperability fixtures.
An issued locator serves one immutable object. A new link/locator, renewed
expiry, changed header/key ID or any other signed-content change requires a
strictly higher profile revision, even if the underlying VPN profile is identical.
Never overwrite a locator's object with another ciphertext/key pair.

## Signed payload and bounded semantics

Exact top-level fields: `v`, `purpose`, `iss`, `lid`, `profile_id`, `revision`,
`iat`, `nbf`, `exp`, `profile_b64`. Reject missing/extra/duplicate decoded keys.

- `v=2`; `purpose="veilark.profile.enrollment.v2"`.
- `iss` and `profile_id`: ASCII `[A-Za-z0-9_-]{1,48}`. No email, Telegram ID or
  personal name. Profile ID remains stable across credential/profile revisions.
- `lid` equals the 43-character locator in the importing link. This binds the
  signature to delivery identity and rejects swapping two complete objects.
- `revision`: integer 1..9,007,199,254,740,991, no exponent/fraction/coercion.
- Times describe the **import window only**, not VPN credential/session TTL.
  Positive integer UTC epoch seconds within the same exact integer bound;
  `iat <= nbf < exp`, `exp - iat <= 2,592,000` (30 days). Allow at most 300 seconds
  clock skew for not-before/issued checks; expiry has no silent grace bypass.
  A clearly unusable device clock is a bounded error, never verify-without-time.
- `profile_b64`: canonical base64url of exact existing profile JSON bytes, at
  most 16,384 decoded bytes. Invoke the matching existing strict semantic parser
  only after issuer verification. It binds every endpoint, server key, client
  credential, network, transport and explicit wire setting as issued. No silent
  conversion from Original to Argon or wire1 to profile2; no lab address defaults.

Proposed initial envelope allowlist is only `veilark-argon-profile-v1`,
`engine=argon-v1`, `beta=true`, existing legacy wire1 with no new wire selector.
Original and diagnostic schema2 remain supported by their existing raw handlers,
but are not silently accepted by this new authenticated format. Expansion needs
an explicit jointly reviewed policy/version, not endpoint-driven negotiation.

JSON object depth <=8; reject duplicate decoded keys at every object, invalid
UTF-8, trailing data, unsupported profile schemas and invalid numbers. Reject
oversize data before allocating/decrypting/decoding the next layer. Exact bytes
are signed; there is no JSON canonicalization dependency. Generic legacy import
keeps its own existing limits, not these enrollment-only limits.

## Import, expiry, rollback and storage transaction

State sequence: bounded link -> bounded ciphertext -> AEAD -> bounded inner
header/payload metadata -> trusted issuer signature -> time/locator/schema/
profile semantics -> revision check -> one durable import transaction.
Untrusted metadata may only select an existing trust-map entry, never network
endpoints or write operations. No auto-connect or replacement of selected profile.

Per `(iss,profile_id)` store the highest accepted revision and
`SHA-256(ASCII(H + "." + P))` after successful verification.
Equal revision + same digest = idempotent success without a duplicate profile
(or restore its deleted local record if explicitly imported again and still valid).
Same revision + different content = conflict; lower = rollback
error. Commit floor and profile atomically only on complete success. Keep the floor
after profile deletion; a deliberate reset/app-data clear loses local protection,
so backend credential revocation remains necessary. Clock rollback is not solved
by a local revision floor. No secrets in UI previews, crash details or diagnostics.

Persist verified issuer/profile/revision/digest/import-window metadata atomically
with the usable profile, through a verifier-created internal value. A raw JSON
import, even if copied from a previously verified profile, has no issuer-trusted
status. Do not persist or accept a caller-provided `verified=true` flag.

Deletion removes the local usable profile and its stored credentials. An expired
envelope cannot be newly imported. **It does not revoke the enclosed VPN key or
forbid its later use**: someone can copy decrypted credentials into a legacy raw
importer. Backend authorization must independently enforce credential entitlement,
expiry and revocation for new/active sessions. No claim that app-only `exp` does
that job. Renewal of the import window issues a new immutable object/revision;
automatic renewal and active-session entitlement are separate backend agreements.
Do not disconnect working legacy users under this draft.
Android encrypted storage/Keystore protects stored material as already supported;
managed-memory zeroization is best effort, never promise perfect key erasure.

## Review decisions and implementation gate

Vector conditionally accepted composition, the standard JWS purpose refinement,
the explicit portable-bearer model, and the exact single-origin `.jwe` mapping for
an authenticated manual operator pilot. Still to jointly freeze: schema allowlist,
operator authorization/crash journal and recovery custody policy; 30-day import window/renewal and independent
backend session entitlement; bootstrap/key rotation; transactional issue/revoke
and temporary key-retention behavior. No production producer/consumer is enabled.
Issuer rotation ships authenticated app trust-map changes before issuing new
`kid`; retain explicitly authorized previous keys during migration, never TOFU.
Shipping must wait for agreed lifecycle and matched producer/consumer fixtures,
not merely agreement that AES-GCM and Ed25519 are available.

Shared deterministic fixtures must include valid round trip, key/IV/tag/locator
mutation, known-CEK attacker forging AEAD but invalid issuer signature, unknown
issuer/kid, wrong purpose/alg, duplicate escaped key, expiry/clock bounds, exact
max sizes, truncation/noncanonical base64, equal-revision idempotence/conflict,
rollback, cancellation/failed-storage atomicity and preservation of legacy import.
They use only public synthetic keys/profiles. The fixture generator is not the
production exporter; Kotlin and backend must independently consume the same bytes.

## Work completed for this draft

Bounded independent review found size/signing/AAD composition consistent and
requested the explicit locator/revision rule, now included. Candidate bytes are
in `docs/fixtures/enrollment-v2-draft-20261023.json`, generated offline by
`scripts/enrollment_draft_fixtures.py` with public deterministic test material.
Python Ed25519/AES-GCM round trip, altered-tag rejection and known-CEK valid-AEAD/
invalid-signature rejection ran successfully. URI is 111 bytes; maximum bounded
inner/outer lengths are 33,539 / 45,504 bytes. Locator-mismatch and expired entries
are proposed semantic vectors, **not executed importer acceptance**. No Kotlin/
backend interoperable consumer, backend lifecycle, APK, native or server change
is implied by these fixtures. Do not deploy the fixture keys or locator.

### Existing integration and legacy compatibility checked

`ImportDeepLink.kt` hardcodes the existing operator origin and maps a canonical
32-byte `import?t=` token to lower-case hex `.json`. HTTPS `?code=` and direct
HTTPS imports have separate unchanged handling. The legacy `SubscriptionFetcher`
allows bounded HTTPS redirects and negotiates generic subscription formats; it
must NOT be reused unchanged for v2's no-redirect/fixed-origin JWE fetch contract.

Operator script `C:/AI-Agent/scripts/deliver-core-beta10-profile-20261016.py`
publishes an existing private profile through a pinned SSH/SFTP connection to
`/var/www/html/veilark/`, refuses differing bytes at an existing locator and
checks HTTPS readback. This is an operator-side static publisher, not evidence of
an authenticated customer issuance/revocation API, owner/request-ID database,
backend key vault or renewal worker. Those proposed lifecycle pieces cannot be
treated as existing infrastructure. Do not execute the old publisher as a check:
it can write files and state. Any v2 pilot publisher must remain separately gated
and cannot overwrite its `.json` profiles or rotate existing credentials.

Read-only verification on 23 October fetched one retained owner test profile:
HTTP200, exact original bytes, legacy Argonv1 schema, and its old compact token
still resolves to that same URL. New aggregate report is
`C:/AI-Agent/reports/enrollment-v2-draft-20261023/legacy-link-readonly-20261023.json`;
bearer URLs/keys are excluded. This does not certify all testers' links, their
individual subscription expiry or an actual VPN session. Vector separately
confirmed no live server/credential/lookup/NAT changes during source fairness work.

## Primary references checked

- [RFC7515 JWS](https://www.rfc-editor.org/rfc/rfc7515.html), sections 3/5:
  compact signing input and validation.
- [RFC7516 JWE](https://www.rfc-editor.org/rfc/rfc7516.html), sections 3/5:
  compact serialization and protected-header authenticated bytes.
- [RFC7518 JWA](https://www.rfc-editor.org/rfc/rfc7518.html), sections 4.5/5.3:
  direct-key empty segment and AES-GCM key/IV/tag sizes.
- [RFC9864](https://www.rfc-editor.org/rfc/rfc9864.html), section 2.2:
  fully specified Ed25519 JOSE identifier.
- [RFC8725](https://www.rfc-editor.org/rfc/rfc8725.html), sections 3.1/3.3/3.11:
  fixed algorithm policy, validation of both nested operations and explicit typing.
  This is not a JWT authentication token; those defensive composition rules apply.
