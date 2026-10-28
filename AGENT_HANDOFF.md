# Atlas Android continuation — 24 October 2026

28Oct managed Fairway source candidate (branch local/android-managed-fairway-20261027):
Opt-in from a selected controlled managed/trust subscription requests an account claim,
stores a per-install X25519 identity encrypted with Android Keystore, and requests a
public profile without sending the private key. Existing static Core profiles retain
priority and are not replaced. Existing routing and UI design are unchanged. A pending
or unavailable enrollment fails closed and a retry keeps the same identity.
OssDebug compilation and FairwayManagedEnrollmentTest: 4 passed, 0 skipped (28Oct).
This is source-only: private release, real Keystore on a device, the deployed account
service, and end-to-end tunnel compatibility have not been verified. No OTA published.
The server's deployed legacy_session_proof compatibility is being integrated separately;
do not assume this candidate can connect until the paired contract is verified.

25Oct RELEASED: Beta15/code85 OTA+Androidcatalog PUBLISHED afterexplicitowner
waiverofphonecheck. Sourceapp477e28f/Core1193f81; APK5aba7c3bfc988edb939f06967f98f4f6d5dbe4e2cadc07cfab4aa1b352df9c48/
58219289B, nativesha476b7099/2946080B/59exports/16KiB. Full503/0fail/0error/3skip
+privateReleaseLintPASS. LiveEd25519/fullpublicAPK+catalogverified; established
signer+bothlegacyABIs/assets retained. Pairedserver135a86d1/configf6accommittedby
Vector, proofenabled/6records/keysprofilespreserved; rootfreshgatePASS. No phone
test, onefreshconnectafterinstallneeded. See rootreports/fairway-stall-20261025/
RELEASE.md receipts/backups; doNOTrerunpublish/updater. Earliercandidate/holdnotes
below historical. Windowsworkmayresume fromapp6d06/Corefc77 underexistingcontract.

25Oct CANDIDATE: Beta15/code85 built+signed LOCAL ONLY. See rootreports/
fairway-stall-20261025/CANDIDATE.md. Core1193f81 cleanpinnedARM64 built,59exports
retained/ELF16KiB; APK5aba7c3bfc988edb939f06967f98f4f6d5dbe4e2cadc07cfab4aa1b352df9c48,
58219289B establishedsigner/v2/v3/ZIP16KiB. Newlegacy-recovery selectorpreserves84.
BothpriorABIs+allother.so unchanged. No phone(noADBphysical), no install/OTA/server
cutover. Existing84/c959/ad5b protected. Needpairedcompatibility+physicalacceptance
andexacttransactionalservercutover/rollback beforepublication. Latest appmessage
toVector failedapprovalreviewquota; noalternatebypassmessageattempt.

25Oct AFTER84 priority: Fairway silentstall investigation11–12MSK. See root
reports/fairway-stall-20261025/INCIDENT.md andANDROID_REPAIR_CONTRACT.md. Server
unchanged; notprovenper-clientcause. VectorisolatedpendingCore repairtypedoutcomes
32/33 (30reservedRelay); Atlasappallowlist recognizesONLYLegacy AFTERJoin+successfulRelease and
keepsboundedretry. NoJavawatchdog/UI/timer/ABIchange. Two targetedJVMtestclasses
PASS againafter32/33renumber:29cases0fail/error/skip38s, noAPK/OTA. Sourceonly;
currentpublished84 intact. Nativecandidate/pairedserverpendingVector. Independent
WIPreviewfoundfullSessionParameterscomparisonblocker; Vectorfixedstableleaseonly.

25Oct LATEST: owner explicitly requested OTA with batch checks. Beta14/code84
PUBLISHED, SHA59b50bef258cc4b955be9cd5f38aa525436be546ccb320e68e468ccc01b4919b,
58202905B, established signer, bothlegacyABIs retained. Exact84 installed onPixel;
baseAPK hashmatched, existingprofile/data retained, realHTTPS NLexit before/after
disconnect+reconnect PASS. Phoneleftconnected. Fullunit502/0fail/0error/3conditional
skips; releaseassembly/R8/lint and exactAPK/native/Geo/export checksPASS. Signed
liveOTA+fullpublicAPK verified; Androidcatalogupdated atomically/no service restart.
See C:/AI-Agent/reports/fairway-beta14-20261025/RELEASE.md and actual receipts.
OldUI preserved; noPassage deploy/newsubscription/camouflage or battery acceptance.
Corefdf6e70/nativeaf457 bundled; serverc959/configad5 unchanged/Kowalskipreserved.
PriorOTA/catalog rollback retained. Do not rerun completedpublisher or recovery.
Below is historical context; next workisolated Passage and clientjournal publication.

25Oct CRITICAL Fairway outage RECOVERED with explicitownerapproval. Rootcause:
stale publicmanifest snapshot after Kowalskiaddition blockedpublicrollback at
step1/DNATclosed; Beta cleanup drift leftorphanNFT/missinganonymousnetns.
Reviewed exactcrossownerrecovery completed usingoriginalbinary/key/config;
publicjournalphaseactive/currentmanifest, CorePID309877, BetaPID309779. Read
C:/AI-Agent/reports/fairway-incident-20261025/RECOVERED.md beforeanyserveraction.
Pixel connected viaexistingprofile; HTTPSexitcheck expectedNLaddressPASS, VPNon.
Do not rerun ONE-SHOTrecovery script; oldPID273384 is stale. No Passage deploy.
Futureconfigpublication needs auditedjournalsnapshot synchronization toavoidrepeat.

25Oct Passage request: owner asks separate subscription+necessary test setup IF
ready. Live readonly HELcoturn active; NLc959 legacy/noPassage3/1 backend. Do not
issue a nonworking link or add account to old daemon. Read readiness report under
reports/fairway-passage-device-20261025/PASSAGE_SERVER_READINESS.md. Existing
namespace+hostforward owner hashes forbid naive newrules: independentnamespace
AND reviewed shared-firewall ownership coexistence/migration required. No remote
writes, credentials or new subscription. Android issuer source now validates
explicit3/1 coturn-rest schema separately from unchanged direct-v1;6purechecksPASS,
6Linuxroot filesystem casesSKIP. Only issuer two source files changed here.
Corefdf6e70 Linuxdaemon now built inreports/passage-server-target-20261025:
SHA9fde5a9971654c0774de5db9b3a40d6b9dfad38f7b746ae1c2586fe4f7a6997a,
1966272B. InitialZigdoubleCRT and genericLLD attempts failed; installedgcc-ld/
ld.lld.exe+stablelinker-flavor=ld succeeds4.65s finalbin; ELF64x86/staticPIE,
noINTERP/NEEDED, RELRO/NX. Builtnotexecuted/notdeployed. Do not
reuse frozen5469 (oldsource) or publish before actual isolatedroute works.
Vector chat send failed: Cannot steer withoutactive turnid. Local read-only
worker supplied currentremote evidence; no claim Vector received new request.

25Oct resume: private APK BUILD PASS6m3s, exact combinedCorefdf6e70/af457283
native verified inside unsigned APK. New pinned selector
veilarkFairwayRepairNative=passage-integration requires repair/private variant;
published active-feedback artifact remains default. Local signed candidate:
C:/AI-Agent/reports/fairway-passage-device-20261025/veilark-fairway-passage-pilot.apk
SHA dd714dd58245c74466f7b6ffe650714c3997aef5ff5a872ca93f1f8cf32c8314,
33028464B, historical signer4c6e3e83...15d3/v2+v3/alignment verified. Same local
version83/beta13; not a new published OTA.25Oct Pixel8Pro reconnected, existing
version83/arm64 verified; adb install-r SUCCESS preserving data. Installedbase.apk
SHA exactlydd714dd...8314, MainActivity launched/PID8566. Unrelatedemulator5554
untouched. Installation/start evidence only, no traffic acceptance for new build.
Existing Fairway subscription remains usable; new Passage needs separate relay
profile and matched server3/1. No new Passage profile/server deployment performed.

Owner clarification: redesign is NOT approved. Preserve its source on local
branch local/design-unapproved-20261024 at93b4acf; next candidate uses pre-redesign
UI5a995a2 plus functional route-choice/blocking notice wiring only. No release of
the redesign without user review. Paired Android ICMP source implemented: request
protocol1/echoID0..65535/destination0, capturedNetwork protected nonblocking ping
socket; TCP/UDP unchanged. Combined compilation and61focused testsPASS/0errors,
log C:/AI-Agent/reports/fairway-passage-ui-icmp-20261024.log. No phone execution.
Vector owns Core merge; only use a matched artifact, no native swap/OTA performed.

Working tree local/w31g03-build, base3df5954; contains private local Passage work.
Do not publish these commits to the public Android repository.

User supplied Downloads/fairway.md and HANDOFF_FOR_CODEX.md. Keep working direct
Fairway; Passage is opt-in. Their claimed deployment/phone results are not current
acceptance. Review: C:/AI-Agent/reports/fairway-passage-review-20261024/REVIEW.md.

Current local changes: explicit relay-only Passage3/1 parser/enum, ABI>=4 gate,
7-int authentication/profile/MTU1357 validation; old Legacy/Carrier2 preserved.
Redesign's previously unused route-choice and blocking-state callbacks now wired.
Focused Gradle run PASS39tests/zero failures and errors; changed UI compiled;
log reports/passage-android-profile-review-20261024.log under workspace root.
No APK/native replacement, server mutation, OTA or real keys.

Vector owns Core merge in separate managed worktree (Passage d202f7b plus latest
Fairway84d5bb1/old source4ee7deb). Do not edit his integration files. Next pair
Android direct-ping change with merged native contract; matched source artifact
required before private pilot. Our previous candidate contains old Fairway but
lacks new direct-ping/RST/UDP changes; do not regress a newer library by swapping it.
