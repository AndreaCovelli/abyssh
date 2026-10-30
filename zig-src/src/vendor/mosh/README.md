# Vendored Mosh client

A Mosh client protocol implementation (UDP transport, AES-128-OCB crypto,
state synchronization) used by `src/bridge/abyssh_mosh.zig`.

Source: [jossephus/zig-mosh-client](https://github.com/jossephus/zig-mosh-client)
at commit `7ddaa2df217ee2e48106a2c970db4a22403924a8`.

The upstream repository has no license file. It is vendored here because
upstream supports only Zig 0.15 and Abyssh needs Zig 0.17.

## Local changes

Ported to Zig 0.17:

- `build.zig`: takes a `libc_file` option and translates `src/zlib_c.h` with
  the ziglang `translate_c` package, which replaces `@cImport`. On Android it
  defines `BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD` so Bionic's overloaded `ioctl`
  translates.
- `src/zlib_c.h`: new, includes `<zlib.h>` for the translated `zlib_c` module.
- `src/compress.zig`: imports `zlib_c` instead of using `@cImport`.
- `src/socket.zig`: uses `std.c` sockets, since Zig 0.17 removed most of
  `std.posix`'s socket API and `std.net`.
- `src/ffi.zig`: reads the clock with `clock_gettime(CLOCK_REALTIME)`.
- `src/protolite.zig`: `std.enums.fromInt` replaces `std.meta.intToEnum`.
- `src/core.zig`: `ArrayList.print`, a switch prong without a discarded
  capture, and `nanosleep` in a test.

Changed behavior:

- `src/socket.zig`: opens an IPv4 or IPv6 socket to match the remote address,
  which may carry a numeric scope (`fe80::1%3`). A send that fails because the
  network is down or unreachable returns `NetworkUnavailable`.
- `src/core.zig`: drops a datagram that fails with `NetworkUnavailable`
  without counting it toward the send-failure limit, so losing the network
  while switching networks doesn't end the session. `startShutdown` sends
  Mosh's shutdown (state number `maxInt(u64)`) and resends it until the
  server acks, which makes `mosh-server` exit. When the server sends its own
  shutdown because its shell exited, the client acks it once, as Mosh's own
  client does, and moves to `stopped`.
- `src/ffi.zig`, `src/mosh_ffi.h`: `mosh_client_start_shutdown` and
  `mosh_client_shutdown_acknowledged`.

Removed: `src/test_live.zig`, a standalone program for testing against a live
server. Nothing built it, and it did not compile on Zig 0.17.

`build.zig` has no test step, so the `test` blocks don't run.
