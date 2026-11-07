# Release cycle record — v0.1.16

Tag `v0.1.16` → commit `5396f86f2f13f8d91465c369dd922bea2f4ce87c` (merge of
PR #271: Nuvio Enhanced 0.6.9-beta sync + release wiring). The tag was created
once, after Android and iOS CI had passed on that exact merge commit, and was
never moved.

## Build fix during the cycle

The first CI run on the sync branch failed before any unit test ran:
`:composeApp:generateRuntimeConfigs` — "property 'localPropertiesFile' doesn't
have a configured value". Upstream 0.6.9 turned the task's `localPropertiesFile`
from `@Optional @InputFile` into `@InputFiles`, and StreamBridge only set it when
`local.properties` existed (never on CI). Fixed in `d9d02b4` by wiring
`rootProject.layout.projectDirectory.file("local.properties")` unconditionally,
exactly like upstream; `@InputFiles` tolerates the missing file, so the
"absent local.properties yields empty tracking configs" CI check still passes.

## Release workflow

`release-draft.yml` run 42349268711 (triggered by the tag push): production
signed APKs for all four ABIs, draft created with the approved notes, unsigned
IPA built and attached, all assets from `5396f86`. Published manually after the
draft was verified (6 assets, body identical to `docs/RELEASE-NOTES-0.1.16.md`).

| Asset | Size | SHA-256 (GitHub asset digest) |
|---|---:|---|
| `androidApp-full-arm64-v8a-release.apk` | 55995060 | `cf663f800ff4f0bce75017e90380e4936f37ec03a44f413316f9e25ad05f08ee` |
| `androidApp-full-armeabi-v7a-release.apk` | 54111903 | `e74026b939621dc1adaf9a56848059a470fea0cce77d472754f0009b9229efa1` |
| `androidApp-full-x86-release.apk` | 56439733 | `7169a4976965fcd284c0f9080474d1d0808ea2fb8a41848f859542696ae73a1f` |
| `androidApp-full-x86_64-release.apk` | 57494463 | `94717ece99ea009d658b37ab1570e3091f9aafc06a7c6fb475424c6cdb201f16` |
| `checksums.sha256` | 577 | `2380e46c5d0a6103a84cbffeb3cf09ca7f959f433e4042e50855171b086b9dd0` |
| `StreamBridge-0.1.16-iOS-unsigned.ipa` | 74649935 | `05530ca0a885ea6feba803afaf2ada3e37a831fefa46abaab4af69582fb618f3` |

## Sideload source and the `main-protection` ruleset

The release-triggered **Update Sideload Source** run (42354586334) produced the
verified 0.1.16 entry and then failed at "Commit sideload source": the
`main-protection` ruleset (created 2026-11-06, after the v0.1.15 cycle, no
bypass actors) rejects direct pushes to `main`. The workflow was changed to keep
the direct push as the first attempt and, when it is rejected, push the same bot
commit to `store/<tag>` and open a pull request. The re-run from the fixed
workflow (42355002317) pushed `ffa327c` to `store/v0.1.16`; `GITHUB_TOKEN`
could not open the pull request, so it was opened by hand as PR #274 and merged
(`efa6540`). `main` now serves the 0.1.16 entry (size 74649935, SHA-256
`05530ca0…`).

To let the workflow open the pull request itself, allow "GitHub Actions to
create and approve pull requests" in the repository Actions settings. No ruleset
was weakened.

## Telegram announcement

The genuine release-triggered **Telegram release announcement** run
(42354586317) resolved and validated the release, froze the plan (3 pages,
5 documents) in the protected ledger and attempted `page:1`. Telegram's response
was not confirmed, so the ledger recorded `delivery_unknown` /
`review_required` (`unconfirmed_telegram_response`) and the run failed by
design. Per `docs/TELEGRAM-ANNOUNCEMENTS.md`, that operation must **never be
resent** (including `resume_unsent`); it blocks later automatic deliveries until
an operator reconciles it. Nothing was resent or replayed during this cycle.

Operator checklist: look at @StreamBridge_App for a 0.1.16 page-1 message. If
it is present and the run log carries a single `TELEGRAM_CONFIRMED_RESPONSE`
marker, follow the documented reconcile dispatch; if there is no marker there
is no proof of delivery and the operation stays blocked, as the protocol
requires.
