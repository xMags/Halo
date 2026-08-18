# Halo mobile (native)

The native mobile client: Compose Multiplatform UI over an owned libmpv
player core, replacing the Expo/React Native app in `apps/mobile` once it
reaches feature parity. Until then `apps/mobile` stays the shipping client
(fixes-only) and this app is built out screen by screen.

Standalone Gradle project — deliberately not part of the pnpm workspace
(no `package.json`; the JS toolchain never sees it).

## Current state

The proven engine/boundary layer, the auth and sync subsystems on top of it,
and a native product shell matching the original app's visual language. A
signed-in session lands on the four-tab shell. Home, Search, Library, Settings,
and Downloads use real repositories or device state. Downloads has a durable
single-file queue, ranged resume, offline playback, and platform-owned
background execution. The diagnostics gate is debug-only scaffolding
reached from Settings and is absent from a build that is not debuggable.

- Gradle 9.5.0, Kotlin 2.4.10, Compose Multiplatform 1.11.1, Ktor 3.5.1
- Targets: `iosArm64`, `iosSimulatorArm64`, `androidTarget` (Android kept
  compiling as one-codebase insurance; feature work is iOS-first)
- Static `ComposeApp` framework consumed by the XcodeGen host in `iosApp/`
  (bundle id `moe.ditto.halo`, iOS 15.1 floor)
- Swift-owned hosts behind Kotlin-exported protocols: the libmpv player
  (`MPVCore` + `MPVPlayerHost`, MoltenVK `wid` embed with the live-resize
  patch), and OIDC auth (`ASWebAuthenticationSession` + PKCE + hand-built
  `/token/` POST that preserves the trailing slash)
- Local-mode auth in common code (`auth/`): Ktor discovery/login/refresh against
  the Halo API, persisted sessions behind an owned `SecureStorage` (Keychain on
  iOS; AES-GCM with a non-exportable Android Keystore key on Android),
  expiry-band single-flight refresh, offline session restore, and the sign-out
  rule: only a definitive 401 from refresh ends a session, never a network failure
- Android mirror hosts over a thin owned `MpvCore` JNI adapter
  (`dev.jdtech.mpv` prebuilt is emulator-only; the shipping build will be an
  owned reproducible libmpv build like iOS's)
- Application-scoped native downloads: WorkManager runs an Android `dataSync`
  foreground worker with encrypted request records in `noBackupFilesDir`; a
  stable Swift background `URLSession` owns iOS tasks with request records in
  `AfterFirstUnlockThisDeviceOnly` Keychain items. The ordinary v2 index,
  worker input, events, notifications, and diagnostics contain no source URL
  or HTTP validator.
- One active download at a time, oldest first. Ordinary process death and
  restart reconcile with OS-owned work automatically. Explicit Pause, Cancel,
  or sign-out wins over late callbacks. Android force-stop and iOS user
  force-quit remain platform exceptions.
- Compose Multiplatform UI layer: design-system components (poster card and
  grid, catalog row, hero scrim, segmented control, search fields, select
  sheet) over Coil image loading and Haze backdrop blur, plus a four-tab shell
  on a type-safe navigation graph with repository-backed Settings
- Common tests (auth discovery, login state machine, player lifecycle, API
  decoding, cache, sync repositories, device-local stores, responsive
  classification), iOS host-bridge tests, nine XCUITest suites (ownership,
  playback, resize, core/app lifecycle, soak, OIDC incl. negative modes,
  local-mode sign-in incl. the cross-process Keychain persistence proof, and
  the tab shell), Android background-work security checks, and instrumented
  Android ownership tests. Swift/Xcode and Apple background-session runtime
  verification still require a Mac or iOS CI runner.
- `fixtures/`: a stdlib-only Python fixture server (OIDC flows with injectable
  negative modes, local-mode login/refresh with real token rotation, and
  Range-capable media serving) used by the integration suites

Android ownership instrumentation uses one local-mode fixture for both auth and
media. Start `fixtures/fixture_server.py --port 18788 --auth-mode local` with
the required media directory, then run `adb reverse tcp:18788 tcp:18788`.
`PlayerOwnershipInstrumentedTest` passes both `serverUrl` and the debug-only
`mediaHttpBase` launch override to that port, so it does not inherit the default
media route on 18787.

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
default mode; `LocalAuthUITests` and `ShellUITests` expect a second instance on
`:18788` with `--auth-mode local` (fixture credentials `fixture-user` /
`fixture-pass`);
the local-media suites additionally need `TEST_RUNNER_HALO_MEDIA_LOCAL_BASE`
pointing at a `file://` copy of the samples.
