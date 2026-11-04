//! Zig wrapper around zigimg PNG decoding.
const std = @import("std");
const zigimg = @import("zigimg");
const c = @import("c");

comptime {
    _ = @import("abyssh_snapshot.zig");
    _ = @import("abyssh_ssh.zig");
    _ = @import("abyssh_mosh.zig");
    _ = @import("abyssh_local_shell.zig");
    _ = @import("abyssh_backup.zig");
    _ = @import("abyssh_wake.zig");
}

const c_allocator = std.heap.c_allocator;
const LOG_TAG = "AbyssKittyNative";

fn logLine(prio: c_int, message: []const u8) void {
    _ = c.__android_log_print(prio, LOG_TAG, "%.*s", @as(c_int, @intCast(message.len)), message.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_INFO, line);
}

fn logWarn(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrint(&buf, fmt, args) catch return;
    logLine(c.ANDROID_LOG_WARN, line);
}

/// Decode a PNG buffer into RGBA pixels.
/// Returns null on failure. Caller must free with `freePixels`.
pub fn decodePng(
    data: [*]const u8,
    len: usize,
    out_w: *u32,
    out_h: *u32,
) ?[*]u8 {
    // Check the size in the header first: a few bytes of PNG can claim an image that
    // would take gigabytes to decode.
    const header = pngHeaderSize(data[0..len]) orelse {
        logWarn("png header invalid len={}", .{len});
        return null;
    };
    if (header.width == 0 or header.height == 0 or
        header.width > max_png_dimension or header.height > max_png_dimension)
    {
        logWarn("png too large width={} height={}", .{ header.width, header.height });
        return null;
    }

    var read_stream = zigimg.io.ReadStream.initMemory(data[0..len]);
    var img = zigimg.formats.png.PNG.readImage(c_allocator, &read_stream) catch {
        logWarn("zigimg decode failed len={}", .{len});
        return null;
    };
    defer img.deinit(c_allocator);

    img.convert(c_allocator, .rgba32) catch {
        logWarn("zigimg rgba32 convert failed len={}", .{len});
        return null;
    };

    if (img.width > std.math.maxInt(u32) or img.height > std.math.maxInt(u32)) {
        logWarn("zigimg image too large width={} height={}", .{ img.width, img.height });
        return null;
    }
    logInfo("zigimg decode ok cols={} rows={}", .{ img.width, img.height });
    out_w.* = @intCast(img.width);
    out_h.* = @intCast(img.height);

    const pixels = c_allocator.alloc(u8, img.rawBytes().len) catch {
        logWarn("zigimg alloc failed bytes={}", .{img.rawBytes().len});
        return null;
    };
    @memcpy(pixels, img.rawBytes());
    return pixels.ptr;
}

/// The largest width or height accepted, the same limit Ghostty applies to Kitty images.
const max_png_dimension = 10_000;

const png_signature = "\x89PNG\r\n\x1a\n";

const PngSize = struct { width: u32, height: u32 };

/// The width and height from the IHDR chunk, which must come first, or null when
/// `bytes` doesn't start with a PNG signature and an IHDR chunk.
fn pngHeaderSize(bytes: []const u8) ?PngSize {
    // Signature, then the IHDR chunk's length and type, then width and height.
    if (bytes.len < png_signature.len + 16) return null;
    if (!std.mem.eql(u8, bytes[0..png_signature.len], png_signature)) return null;
    const chunk = bytes[png_signature.len..];
    if (!std.mem.eql(u8, chunk[4..8], "IHDR")) return null;
    return .{
        .width = std.mem.readInt(u32, chunk[8..12], .big),
        .height = std.mem.readInt(u32, chunk[12..16], .big),
    };
}

/// Free pixel data previously returned by `decodePng`.
pub fn freePixels(ptr: ?[*]u8, w: u32, h: u32) void {
    if (ptr) |p| {
        const total_bytes = @as(usize, w) * @as(usize, h) * 4;
        c_allocator.free(p[0..total_bytes]);
    }
}
