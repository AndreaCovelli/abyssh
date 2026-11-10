# AGENTS.md

Abyssh is an Android SSH, Tailscale SSH, and Mosh client. A Kotlin and Jetpack Compose
app (`android/`) drives a native library written in Zig (`zig-src/`) that holds the
terminal engine (libghostty-vt), SSH (libssh2 and OpenSSL), Mosh, the local shell, and
backup encryption. It is a fork of jossephus/chuchu.

## Commands

mise pins JDK 21, Zig 0.17.0, actionlint, and rumdl; the Gradle wrapper pins Gradle; the
version catalog pins AGP, Kotlin, and libraries. Run `mise install` once.

| Command | Use |
| --- | --- |
| `mise run native` | Build `libabyssh_jni.so` for every ABI into `android/app/src/main/jniLibs/`. Needs `ANDROID_NDK_HOME` set to one NDK version, such as `$ANDROID_HOME/ndk/28.2.13676358`. Add `-Dtarget=aarch64-linux-android` to build arm64 only |
| `mise run check` | Formatting, Detekt, Android Lint, unit tests, compiling the instrumented tests, the debug build, actionlint, and the Markdown lint. Run before every commit. When every file changed since the merge base with `origin/main` is Markdown, it runs only `check:docs`; pass `--full` to run everything |
| `mise run check:docs` | Lint Markdown with rumdl, configured in `.rumdl.toml`. Prose wraps at 90 columns |
| `mise run check:native` | Build the arm64 native library with the pinned NDK, run `zig build test`, check that every library in `jniLibs/` is aligned for 16 KB pages, and check that every Kotlin `external fun` has a `Java_*` export. Uses `ANDROID_NDK_HOME`, or NDK 28.2.13676358 under `ANDROID_HOME`. Run it after changing a bridge or Zig code |
| `mise run deps:android`, `mise run deps:zig` | Warm the Gradle and Zig dependency caches. CI runs them only to seed a missing cache after a push that a pull request already validated |
| `mise run format` | Format Kotlin sources and Gradle scripts with ktlint through Spotless. `mise run format:check` only reports |
| `mise run detekt` | Detekt static analysis, configured in `android/config/detekt/detekt.yml`, with no baseline |
| `mise run test` | Unit tests only. Filter with `cd android && ./gradlew testDebugUnitTest --tests '<pattern>'` |
| `mise run test:device` | Instrumented tests on a connected device or emulator |
| `mise run test:device:compile` | Compile the instrumented tests without a device. Part of `mise run check` |
| `mise run install` | Install the debug APK on a connected device and launch it |
| `mise run release` | Tag the next version from the commits since the last tag (`mise-tasks/release`). Before 1.0, breaking changes bump minor and everything else bumps patch. Never tag by hand |
| `cd zig-src && zig build test` | Run the Zig tests (the backup format, the fling cap, the scaling of oversized PNGs, the Kitty image cache, and the vendored Mosh client) on the host |
| `cd zig-src && zig build fmt` | Format the Zig sources |

Run one Gradle build at a time. The native build takes several minutes for all ABIs;
build arm64 only while iterating.

## Layout

```text
android/app/src/main/java/io/github/andreacovelli/abyssh/
  data/       Room database, DAOs, repositories, backup planning
  model/      Plain data types (HostProfile, Transport, AuthMethod, SshKey)
  service/    Connections and sessions: ssh/, mosh/, terminal/, multiplexer/, backup/
  ui/         Navigation, screens/, shared components/, terminal/ rendering and input, theme/
android/app/schemas/   Committed Room schema JSON, one file per database version
zig-src/src/bridge/    JNI exports, one file per Kotlin bridge class
zig-src/src/vendor/    Vendored Mosh client, build scripts for libssh2 and mbedtls
```

## The native bridge

Kotlin declares `external fun native*` methods in six bridge classes: `GhosttyBridge`,
`NativeLocalShellBridge`, `NativeSshBridge`, `NativeMoshBridge`, `NativeBackupBridge`,
and `NativeWakeBridge`. Zig implements each one as
`export fn Java_io_github_andreacovelli_abyssh_<package>_<Class>_<method>` in
`zig-src/src/bridge/abyssh_*.zig`.

- The JNI symbol name encodes the Kotlin package, class, and method. Renaming or moving
  any of them in Kotlin without renaming the Zig export compiles cleanly and then fails
  at runtime with `UnsatisfiedLinkError`. Change both sides and run
  `mise run check:native`, which rebuilds the arm64 library and fails on any
  `external fun` without a matching export. It handles `external fun`s that are
  members of a top-level class, as all bridges are, and rejects overloads.
- Keep the Kotlin and Zig signatures in step: JNI types (`jlong`, `jbyteArray`, …) map
  to Kotlin `Long`, `ByteArray`, and so on, and nothing checks them at build time.
- A new bridge file is compiled only if `src/bridge/zignal_png.zig`, the root module,
  imports it in its `comptime` block.
- `src/bridge/version-script.map` exports only `Java_*`, `abyssh_*`, and a few named
  symbols. Name new exported C functions `abyssh_*` or add them to the map.
- Each bridge class loads the library inside `runCatching` and exposes `isLoaded()`.
  CI and unit tests run without the native library, so Kotlin code must check
  `isLoaded()` rather than assume the library is there.
- The Zig package name and fingerprint are in `zig-src/build.zig.zon`. Renaming the
  package invalidates the fingerprint; `zig build` prints the new value to use.

## Android conventions

- Single activity (`MainActivity`) with Navigation Compose routes in
  `ui/ApplicationNavController.kt`.
- There is no DI framework. Repositories and stores are singletons reached through
  `getInstance(context)`, and each ViewModel provides a `factory(application)`. Follow
  that pattern rather than adding Hilt or Koin.
- The UI uses Material 3 (`androidx.compose.material3`). `AbyssTheme` maps the active
  Ghostty theme onto a Material color scheme and the bundled Inter font
  (`res/font/inter_*.ttf`) onto the Material type scale. The terminal and the `code`
  and `codeSmall` styles use the monospace font chosen in settings; use them for
  literal values such as hosts, paths, keys, and commands, and pass
  `monospace = true` to `AbyssTextField` for fields that hold them. UI text uses
  sentence case. Build screens from the `Abyss*` components in `ui/components/`
  (buttons, icon buttons, text fields, cards, dialogs, switches, segmented controls),
  with colors from `AbyssColors.current` and type from `AbyssTypography`. Icons are
  vector drawables in `res/drawable/` shown with `Icon` or `AbyssIconButton`; don't
  draw icons with Canvas or Unicode glyphs. Dense rows such as the accessory bar use
  `AbyssKey`, which has no minimum button width.
- Each screen lives in a lowercase package under `ui/screens/` (`addserver`,
  `terminal`, `settings`, …).
- User-visible text goes in `res/values/strings.xml`, with plurals for counts. Read it
  with `stringResource` in composables and with `LocalResources.current` inside
  callbacks; Lint rejects `LocalContext.current.getString`. Product names, key caps,
  and log or exception messages stay in code.
- ViewModels expose `StateFlow`; screens collect it with `collectAsStateWithLifecycle`.
- Declare every dependency in `android/gradle/libs.versions.toml`. Room uses KSP and
  the Room Gradle plugin.
- SDK levels: compileSdk 37, targetSdk 37, minSdk 31. Change them only in a dedicated
  commit, and ask before raising minSdk.
- The version comes from git: release builds pass the tag's numbers in
  `VERSION_MAJOR`, `VERSION_MINOR`, and `VERSION_PATCH`, and other builds use
  `git describe`, so four commits after v0.8.13 build `0.8.13-4-g<sha>` with versionCode
  813004. A clone without tags builds `0.0.0-dev`.
- Kotlin style is ktlint's `android_studio` style, set in the root `.editorconfig`.
  Detekt thresholds come from `detekt.yml`. Only the JNI bridges (`GhosttyBridge`,
  `NativeSshBridge`) suppress `TooManyFunctions`, because each method mirrors a native
  export. Split anything else that crosses a size or complexity threshold into
  collaborator classes or smaller composables rather than suppress it. A broad catch at a
  native or transport boundary carries `@Suppress("TooGenericExceptionCaught")` with a
  comment saying why.
- Database changes: bump `version` in `data/db/AppDatabase.kt`, add an `AutoMigration`
  (with a spec for renames and deletes), build to generate the new schema JSON, and
  commit it. Version 11 is the baseline; there are no migrations from older versions.
- Passwords, key passphrases, and private keys are sealed with AES-GCM
  (`data/secure/SecretBox`) before they reach the database. The DAOs seal on write and
  open on read, so new secret columns must go through them too. The data key lives in
  `no_backup/`, wrapped by an Android Keystore key (`data/secure/DataKeyStore`); when a
  new key is created, the database is deleted because nothing sealed before can be read.

## Testing

- Unit tests live in `android/app/src/test/` and run on the JVM; Robolectric covers
  code that needs Android classes. Put new tests there unless they need a real device.
- `ui/theme/ThemeScreenshotTest` renders components with Roborazzi into
  `android/app/build/outputs/roborazzi/`. Pass `-Proborazzi.test.record=true` to write
  the images; nothing compares them automatically.
- Instrumented tests in `android/app/src/androidTest/` need a device to run. Say so
  when none was available. `mise run check` compiles them without one
  (`mise run test:device:compile`), so a changed signature can't leave them broken.
  Without a phone, run them on the Gradle-managed emulator with
  `cd android && ./gradlew dev34DebugAndroidTest`, after building the x86_64 native
  library (`mise run native -Dtarget=x86_64-linux-android`). CI runs them there too.
- The R8-minified `minified` build type runs only the smoke tests in
  `src/androidTest/minified/`, with
  `./gradlew -PtestBuildType=minified dev34MinifiedAndroidTest`. They start the app and
  call the bridges, which catches R8 removing or renaming what the native code reaches
  over JNI. R8 removes the app code that only tests call, so these tests use only
  what the app itself uses. The debug run includes them too.
- `zig build test` runs the Zig `test` blocks on the host, for the code that doesn't
  need Android: the backup format (`src/bridge/backup_container.zig`), the cap on how
  much of a fling reaches a full-screen program (`src/bridge/scroll_momentum.zig`),
  the decoding and scaling of PNGs too large to store
  (`src/bridge/image_downscale.zig`), checked against zigimg, the cache that restores
  evicted Kitty images (`src/bridge/kitty_image_cache.zig`),
  which runs Ghostty's terminal, and the vendored Mosh client. Keep such code out of
  the JNI files so it can be tested; test the rest of the bridge logic through its
  Kotlin callers.
- The instrumented `MigrationPathTest` migrates a version 11 database through every
  version and checks each against the committed schema. Extend it when you add a
  migration.
- Changes to the bridge, SSH, Mosh, or the terminal need a check on a device: build the
  native library, run `mise run install`, open a connection, and read
  `adb logcat` for `UnsatisfiedLinkError` or crashes.

## Zig

- The code targets Zig 0.17, whose standard library differs from older docs and
  examples. Check APIs against the installed source (`zig env` prints `std_dir`)
  rather than from memory.
  - `std.ArrayList` is unmanaged: initialize with `.empty` and pass the allocator
    to every call. Format into one with `list.print(gpa, fmt, args)`.
  - Files, clocks, randomness, and networking moved behind `std.Io`. The bridges
    have no `Io` of their own, so they call libc through `std.c`: `time.zig` for
    timestamps and `std.c.arc4random_buf` for random bytes. Ghostty's terminal
    takes `ghostty.TinyIo.init.io()`.
- `@cImport` is gone. C headers for the bridges are listed in
  `src/bridge/abyssh_c.h`, which `build.zig` translates with the `translate_c`
  package into the `c` module (`const c = @import("c");`). Add new headers there.
- `zig build` unpacks dependencies into `zig-src/zig-pkg/`, which git ignores.
  After changing a dependency, `zig fetch <url>` prints the hash for
  `build.zig.zon`.
- Pass allocators explicitly and pair allocations with `defer` or `errdefer`.
- Log through `__android_log_print` with a tag such as `AbyssKittyNative`; stdout and
  stderr are discarded on Android.
- Every value that crosses JNI is untrusted: check lengths and null handles before
  using them.

## Security

- Never log or print passwords, private keys, passphrases, or session output.
- `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` keep credentials
  out of Android backup and device transfer. Preserve those exclusions when adding
  stored data; credentials leave the device only through the encrypted export.
- Never commit `local.properties`, keystores, signing passwords, or API keys. Keep the
  release keystore outside the repository. Release signing reads its path
  (`KEYSTORE_PATH`), passwords, and alias from environment variables in
  `android/app/build.gradle.kts`.

## Git

- Use Conventional Commits: `<type>(<scope>): <description>`, with `!` for breaking
  changes.
- `origin` is AndreaCovelli/abyssh. `upstream` is jossephus/chuchu, fetch only; push is
  disabled. When porting an upstream change, apply the rename:
  `com.jossephus.chuchu` → `io.github.andreacovelli.abyssh`, `chuchu` → `abyssh`,
  `Chuchu` → `Abyssh`, `Chu*` classes → `Abyss*`, and upstream's `chuchu_*` Zig files and
  `Java_com_jossephus_chuchu_*` exports to their `abyssh` names.
- GitHub Actions: CI (`ci.yml`) calls the shared workflow in AndreaCovelli/repo-baseline
  three times, each gated by its own path filter: `Docs` runs `mise run check:docs` when
  Markdown changes, `Check` runs `mise run check`, without the native library, when the
  app or tooling changes, and `Native` runs `mise run check:native` when Zig code, a
  `*Bridge.kt` file, or the tool pins change. `Native x86_64` builds the x86_64 library
  when Check's paths or Zig code change, and the two `Device` jobs then run the
  instrumented tests on the managed emulator, against the debug and the minified builds.
  CI also scans new commits with Gitleaks (`.gitleaks.toml`). Its `Validate` job is the
  only check the `main` ruleset requires. Add any new CI job to `Validate`'s `needs`.
  Release builds signed artifacts from `v*.*.*` tags and attaches
  `native-debug-symbols.zip`, the symbols of the stripped native library, for reading
  crash backtraces; Dependency Submission sends the Gradle dependency graph to GitHub when
  build files change on `main`. The signing secrets live in the `release` environment,
  which admits only `main` and `v*` tags; a job that signs must declare
  `environment: release`. Pin actions to a full commit SHA with a version comment, and run
  `mise run check:actions` after editing workflows.
- Renovate extends AndreaCovelli/renovate-config: on the first Sunday of each month it
  opens one grouped PR, which automerges non-breaking updates released at least 7 days
  earlier once `Validate` passes, and a separate PR for major updates. `forkProcessing`
  is enabled because GitHub still lists this repository as a fork. Renovate does not
  update Zig, the dependencies in `build.zig.zon`, or the NDK version; update those by
  hand. Merge pull requests by squash; `main` keeps a linear history.
