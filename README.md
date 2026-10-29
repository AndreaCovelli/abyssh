<p align="center">
  <img src="./assets/logo.png" alt="Abyssh logo" width="64" height="64" />
</p>

<h1 align="center">Abyssh</h1>

<p align="center">An Android terminal for SSH and Mosh, built on libghostty.</p>

---

Abyssh connects your Android phone to remote machines over SSH, Tailscale SSH, or
Mosh, and renders the session with the same terminal engine as
[Ghostty](https://ghostty.org). It is a fork of
[jossephus/chuchu](https://github.com/jossephus/chuchu).

## Features

**Connections**

- SSH with a password, a key, or a key with a passphrase; Tailscale SSH; and Mosh,
  which keeps a session alive across network changes and sleep
- A local shell on the phone
- Host key checks on first connect, with a warning when a known key changes
- A command per host that runs after each connect or reconnect
- tmux and zmx session lists, so you can reattach to a session from the app

**Terminal**

- Ghostty's terminal emulation, with scrollback, mouse support, and images through
  the Kitty graphics protocol
- 463 color themes from Ghostty, with separate light and dark choices
- Fira Code, Geist Mono, Hack, and JetBrains Mono, with Nerd Font symbols
- Search through the scrollback, and tap links in the output to open or copy them
- Mouse and touchpad support: scroll with the wheel, select by dragging
- A configurable row of extra keys above the keyboard, and custom actions
- Several sessions at once in tabs

**Files and keys**

- An SFTP file browser
- Ed25519 and RSA key generation, and import of existing keys
- Encrypted export and import of hosts and keys

**Privacy**

- Saved passwords and private keys are encrypted with a key that the Android Keystore
  protects
- An optional app lock that uses your fingerprint or the phone's screen lock
- An option that hides the app's contents from screenshots and the recent apps list

## Install

There is no published release yet. Build the APK from source, as described below.
Abyssh needs Android 7.0 or later. Release builds include the native library only for
64-bit ARM phones (`arm64-v8a`), which covers nearly all current devices.

## Build from source

You need:

- [mise](https://mise.jdx.dev), which installs the pinned JDK 21, Zig 0.17.0, and
  actionlint
- The Android SDK, with `ANDROID_HOME` set and an NDK installed

```sh
mise install

# Build the native library. Point ANDROID_NDK_HOME at one installed NDK version.
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
mise run native                                   # every ABI
mise run native -Dtarget=aarch64-linux-android    # 64-bit ARM only, faster

mise run install   # build the debug APK, install it on a connected phone, and open it
```

The native build copies `libabyssh_jni.so` into `android/app/src/main/jniLibs/`. Run
it again whenever you change Zig code. Without it the app still builds and opens, but
connections, key generation, and the terminal don't work.

Other tasks (`mise tasks ls` lists them all):

| Task | What it does |
| --- | --- |
| `mise run build` | Build the debug APK |
| `mise run check` | Android Lint, unit tests, the debug build, and a lint of the GitHub workflows |
| `mise run test` | Unit tests only |
| `mise run test:device` | Tests on a connected phone or emulator |

## Project layout

| Path | Contents |
| --- | --- |
| `android/` | The Kotlin and Jetpack Compose app, built with Gradle |
| `zig-src/` | The native library: the terminal engine, SSH, Mosh, the local shell, and backup encryption, built with Zig |
| `.github/workflows/` | CI checks, nightly builds, and releases |
| `assets/` | The logo used in this README |

The native library links [libghostty-vt](https://github.com/ghostty-org/ghostty) for
terminal emulation, libssh2 and OpenSSL for SSH, and a Zig Mosh client.

## Releases

Every push to `main` publishes a nightly APK as a GitHub prerelease. Pushing a tag such
as `v1.2.3` builds a signed APK and app bundle and attaches them to a draft release.
Signing reads the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD` secrets of the `release` environment, which only `main` and `v*` tags
can use. The workflow decodes the keystore into the runner's temporary directory and
deletes it after the build.

To sign a release build locally, keep the keystore outside the repository and point the
build at it:

```sh
cd android
KEYSTORE_PATH="$HOME/path/to/release.jks" KEYSTORE_PASSWORD=… KEY_ALIAS=… KEY_PASSWORD=… \
  ./gradlew assembleRelease
```

Without these variables the release build is unsigned; with only some of them it fails.

## License

MIT; see [LICENSE](LICENSE). Abyssh is based on chuchu by jossephus and its
contributors.
