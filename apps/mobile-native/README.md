# Halo mobile (native)

The native mobile client: Compose Multiplatform UI over an owned libmpv
player core, replacing the Expo/React Native app in `apps/mobile` once it
reaches feature parity. Until then `apps/mobile` stays the shipping client
(fixes-only) and this app is built out screen by screen.

Standalone Gradle project — deliberately not part of the pnpm workspace
(no `package.json`; the JS toolchain never sees it).

## Current state

The proven engine/boundary layer, plus the diagnostics gate harness its test
suites drive. Product screens (auth, catalog, player UI, downloads) land on
top of this boundary; the gate shell is debug/test scaffolding, not product UI.

- Gradle 9.5.0, Kotlin 2.4.10, Compose Multiplatform 1.11.1, Ktor 3.5.1
- Targets: `iosArm64`, `iosSimulatorArm64`, `androidTarget` (Android kept
  compiling as one-codebase insurance; feature work is iOS-first)
- Static `ComposeApp` framework consumed by the XcodeGen host in `iosApp/`
  (bundle id `moe.ditto.halo`, iOS 15.1 floor)
- Swift-owned hosts behind Kotlin-exported protocols: the libmpv player
  (`MPVCore` + `MPVPlayerHost`, MoltenVK `wid` embed with the live-resize
  patch), and OIDC auth (`ASWebAuthenticationSession` + PKCE + hand-built
  `/token/` POST that preserves the trailing slash)
- Local-mode auth in common code (`auth/`): Ktor login/refresh against the
  Halo API, persisted sessions behind an owned `SecureStorage` (Keychain on
  iOS; Android is plaintext prefs until a Keystore pass), expiry-band
  single-flight refresh, offline session restore, and the sign-out rule —
  only a definitive 401 from refresh ends a session, never a network failure
- Android mirror hosts over a thin owned `MpvCore` JNI adapter
  (`dev.jdtech.mpv` prebuilt is emulator-only; the shipping build will be an
  owned reproducible libmpv build like iOS's)
- Common tests (auth discovery, login state machine, player lifecycle,
  responsive classification), iOS host-bridge tests, eight XCUITest suites
  (ownership, playback, resize, core/app lifecycle, soak, OIDC incl. negative
  modes, local-mode sign-in incl. the cross-process Keychain persistence
  proof), and an instrumented Android ownership test
- `fixtures/`: a stdlib-only Python fixture server (OIDC flows with injectable
  negative modes, local-mode login/refresh with real token rotation, and
  Range-capable media serving) used by the integration suites

## Local commands

Windows metadata and compile checks:

```powershell
.\gradlew.bat :composeApp:compileCommonMainKotlinMetadata
.\gradlew.bat :composeApp:compileTestKotlinIosSimulatorArm64
python -m unittest discover -s fixtures/tests -v
```

Mac/Xcode:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
cd iosApp && xcodegen generate && open Halo.xcodeproj
```

The app target's pre-build phase runs
`:composeApp:embedAndSignAppleFrameworkForXcode`. UI test suites run against
a booted arm64 simulator via
`xcodebuild test -project iosApp/Halo.xcodeproj -scheme Halo
-only-testing:HaloUITests/<Suite>`, with the fixture server providing auth
flows and Range-capable media. The OIDC suites expect it on `:18787` in its
default mode; `LocalAuthUITests` expects a second instance on `:18788` with
`--auth-mode local` (fixture credentials `fixture-user` / `fixture-pass`);
the local-media suites additionally need `TEST_RUNNER_HALO_MEDIA_LOCAL_BASE`
pointing at a `file://` copy of the samples.
