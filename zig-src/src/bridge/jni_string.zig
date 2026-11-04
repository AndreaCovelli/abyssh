//! Converts between Java strings and UTF-8.
//!
//! JNI's GetStringUTFChars and NewStringUTF use modified UTF-8, not UTF-8: they encode
//! NUL as two bytes and characters outside the Basic Multilingual Plane as two
//! three-byte surrogates, and NewStringUTF aborts the process on invalid input when
//! CheckJNI is on. These helpers go through the UTF-16 calls instead and replace
//! anything that can't be converted with U+FFFD.

const std = @import("std");
const unicode = std.unicode;

const c = @import("c");

const replacement: u21 = 0xFFFD;

/// The UTF-8 form of `s`, owned by the caller. Null when `s` is null or on failure.
/// The result is built in place without a scratch copy, so callers that pass secrets
/// can wipe the one buffer.
pub fn dupUtf8(env: *c.JNIEnv, allocator: std.mem.Allocator, s: c.jstring) ?[]u8 {
    if (s == null) return null;
    const len = env.*.*.GetStringLength.?(env, s);
    if (len < 0) return null;
    const chars = env.*.*.GetStringChars.?(env, s, null) orelse return null;
    defer env.*.*.ReleaseStringChars.?(env, s, chars);

    const units = @as([*]const u16, @ptrCast(chars))[0..@intCast(len)];
    var size: usize = 0;
    var i: usize = 0;
    while (i < units.len) size += unicode.utf8CodepointSequenceLength(nextUtf16(units, &i)) catch unreachable;

    const out = allocator.alloc(u8, size) catch return null;
    var pos: usize = 0;
    i = 0;
    while (i < units.len) pos += unicode.utf8Encode(nextUtf16(units, &i), out[pos..]) catch unreachable;
    return out;
}

/// A Java string holding `bytes`, with invalid UTF-8 replaced. Null on failure, with
/// the Java exception left pending if the VM raised one. The UTF-16 copy is wiped,
/// since private keys pass through here.
pub fn newString(env: *c.JNIEnv, allocator: std.mem.Allocator, bytes: []const u8) c.jstring {
    var count: usize = 0;
    var i: usize = 0;
    while (i < bytes.len) count += utf16Len(nextUtf8(bytes, &i));
    if (count > std.math.maxInt(c.jsize)) return null;

    var stack: [256]u16 = undefined;
    const units = if (count <= stack.len) stack[0..count] else allocator.alloc(u16, count) catch return null;
    defer {
        std.crypto.secureZero(u16, units);
        if (count > stack.len) allocator.free(units);
    }

    var pos: usize = 0;
    i = 0;
    while (i < bytes.len) {
        const cp = nextUtf8(bytes, &i);
        if (cp >= 0x10000) {
            const v = cp - 0x10000;
            units[pos] = @intCast(0xD800 + (v >> 10));
            units[pos + 1] = @intCast(0xDC00 + (v & 0x3FF));
            pos += 2;
        } else {
            units[pos] = @intCast(cp);
            pos += 1;
        }
    }
    return env.*.*.NewString.?(env, @ptrCast(units.ptr), @intCast(count));
}

/// Decodes the code point at `i.*` and advances past it. An unpaired surrogate
/// becomes U+FFFD.
fn nextUtf16(units: []const u16, i: *usize) u21 {
    const unit = units[i.*];
    i.* += 1;
    if (unicode.utf16IsHighSurrogate(unit)) {
        if (i.* < units.len and unicode.utf16IsLowSurrogate(units[i.*])) {
            const low = units[i.*];
            i.* += 1;
            return 0x10000 + ((@as(u21, unit - 0xD800) << 10) | (low - 0xDC00));
        }
        return replacement;
    }
    if (unicode.utf16IsLowSurrogate(unit)) return replacement;
    return unit;
}

/// Decodes the code point at `i.*` and advances past it. A byte that doesn't start a
/// valid sequence becomes U+FFFD and is skipped on its own.
fn nextUtf8(bytes: []const u8, i: *usize) u21 {
    const start = i.*;
    i.* += 1;
    const seq_len = unicode.utf8ByteSequenceLength(bytes[start]) catch return replacement;
    if (seq_len > bytes.len - start) return replacement;
    const cp = unicode.utf8Decode(bytes[start..][0..seq_len]) catch return replacement;
    i.* = start + seq_len;
    return cp;
}

fn utf16Len(cp: u21) usize {
    return if (cp >= 0x10000) 2 else 1;
}
