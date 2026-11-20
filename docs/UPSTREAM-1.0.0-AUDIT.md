# Upstream 1.0.0 review for StreamBridge 0.1.19

Reviewed tag snapshots (not branch tips): official `NuvioMedia/NuvioMobile`
`511ebd5f175dd39e31b9cc0e7b259259e887cf6f`, Enhanced
`yesnt10/NuvioMobile-Enhanced` `3138f33fee8803d324a3ee213f588697995aafda`.
Both tag refs were verified against GitHub. The official tag is Kotlin
Multiplatform; the exact Enhanced tag is an older Expo/React Native tree, not
the later Kotlin Enhanced branch used in the existing CloudStream runtime audit.
It contains no CloudStream implementation under `src`. It is not a drop-in
upstream overlay for the current application.

The existing StreamBridge tree is based on earlier documented core/Enhanced
pins and contains substantial StreamBridge-specific changes; a comparison of
`composeApp` feature code to official 1.0.0 shows hundreds of changed files.
No full 1.0.0 rebase or wholesale sync has been performed. In particular, no
upstream feature is claimed as newly merged in this change. A complete review,
selective integration and device regression testing remain outstanding. The
existing `docs/CLOUDSTREAM-RUNTIME-AUDIT.md` compares a later Enhanced branch,
not the exact 1.0.0 tag.

MPVKit/native pins were not changed: the tag comparison alone does not establish
binary compatibility with StreamBridge's native overlays. `store.json` remains
at the last published version; 0.1.19 metadata requires real production
artifacts and verified checksums. Android Play and iOS remain non-executing
CloudStream distributions. Android Full loads third-party code in-process;
authorized repositories and hashes do not make code malware-free. A missing
published hash is unverified. These checks are not a sandbox.
