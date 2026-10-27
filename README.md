<p align="center">
  <img src="./assets/logo.png" alt="ctulssh logo" width="64" height="64" />
</p>

<h1 align="center">ctulssh</h1>

<p align="center">
  A native Android SSH and Mosh client powered by libghostty
</p>

---

ctulssh is a personal fork of [jossephus/chuchu](https://github.com/jossephus/chuchu),
a native Android SSH client with a terminal-first Compose UI that supports standard
SSH, Mosh, and Tailscale SSH.

## Features

- Password and key authentication, including Tailscale SSH
- Image display through libghostty's Kitty image protocol support
- More than 400 themes from the Ghostty repository
- Configurable accessory keys and custom terminal actions
- Per-host post-connect actions that run after SSH or Mosh connects or reconnects
- Encrypted import and export of SSH keys and server profiles
- A terminal renderer with resize, scrollback, focus, modifier keys, and mouse support

ctulssh installs as a separate app and does not read chuchu data or backups.

## Stack

- Kotlin and Jetpack Compose for the Android app, Room for local storage
- Zig for the native build and the JNI bridge
- Ghostty VT for terminal emulation
- `libssh2` and OpenSSL for SSH

## Development

[mise](https://mise.jdx.dev) pins the JDK, Zig, and actionlint. You also need the
Android SDK (`ANDROID_HOME`) with an NDK installed.

```sh
mise install

# Build libctulssh_jni.so into android/app/src/main/jniLibs.
# ANDROID_NDK_HOME must point at one NDK version.
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/<version>"
mise run native -Dtarget=aarch64-linux-android

mise run build     # debug APK
mise run install   # install on a connected device and launch
mise run check     # lint, unit tests, debug build, workflow lint
```

`mise tasks ls` lists every task. Without mise, use JDK 21 and Zig 0.15.2 and run the
same commands directly:

```sh
(cd zig-src && zig build -Doptimize=ReleaseSmall jni -Dtarget=aarch64-linux-android)
(cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug)
```

The APK must contain `lib/arm64-v8a/libctulssh_jni.so`; without it, SSH and key
generation fail on device.

## License

MIT, see [LICENSE](LICENSE). Based on chuchu by jossephus and its contributors.
