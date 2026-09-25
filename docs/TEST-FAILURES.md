# Unit-test status

Status for the StreamBridge 0.1.06 release based on NuvioMobile 0.5.1 and
NuvioMobile-Enhanced 0.5.1-beta.

The host test suite is a release gate (`./gradlew :composeApp:check`). The
previous `continue-on-error` exception has been removed.

## 0.1.06 test reconciliations

The 0.5.1 integration exposed ten stale or harness-specific assertions. They
were reconciled without reducing StreamBridge functionality:

- Home hero height now expects the production `1.5` mobile ratio.
- Backend retry delay now expects the production 30-second cap plus jitter.
- Root tab retention tests only expect tabs that the harness actually visits;
  Live TV remains conditionally mounted when configured.
- Stream orientation tests initialize StreamBridge's updater platform just as
  `MainActivity` does before composing the app.
- Download subtitle verification no longer imposes a serial request order on
  StreamBridge's intentionally concurrent video/subtitle pipeline.
- Episode rating visibility checks verify semantic presence rather than pixel
  visibility in Robolectric, avoiding a false negative caused by host viewport
  clipping while retaining every hide/show assertion.

CloudStream repository-loader tests continue to execute as proper JUnit tests
and remain part of the gated suite.
