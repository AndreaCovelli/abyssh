//! Decodes PNGs for Kitty images: with zigimg when they fit the limits, and while
//! scaling them down when they don't.
const std = @import("std");
const zigimg = @import("zigimg");
const c = @import("c");
const image_downscale = @import("image_downscale.zig");

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

/// Decodes a PNG buffer into RGBA pixels, with at most `max_pixels` pixels and at most
/// `max_png_dimension` on either side. A larger PNG is scaled down to fit, keeping its
/// aspect ratio. Returns null on failure. Caller must free with `freePixels`.
pub fn decodePng(
    data: [*]const u8,
    len: usize,
    max_pixels: u64,
    out_w: *u32,
    out_h: *u32,
) ?[*]u8 {
    const bytes = data[0..len];
    // Check the size in the header first: a few bytes of PNG can claim an image that
    // would take gigabytes to decode.
    const header = pngHeader(bytes) orelse {
        logWarn("png header invalid len={}", .{len});
        return null;
    };
    if (header.width == 0 or header.height == 0 or
        header.width > max_source_dimension or header.height > max_source_dimension or
        @as(u64, header.width) * header.height > max_source_pixels)
    {
        logWarn("png too large width={} height={}", .{ header.width, header.height });
        return null;
    }

    const src: image_downscale.Size = .{ .width = header.width, .height = header.height };
    const fitted = image_downscale.fittedSize(header.width, header.height, max_pixels, max_png_dimension);
    if (fitted.width == src.width and fitted.height == src.height) return decodeWhole(bytes, out_w, out_h);

    if (!header.interlaced) {
        const pixels = image_downscale.decodePngScaled(c_allocator, bytes, src, fitted) catch |err| {
            logWarn("png scaled decode failed len={} err={t}", .{ len, err });
            return null;
        };
        logInfo("png decoded scaled width={} height={} to width={} height={}", .{ src.width, src.height, fitted.width, fitted.height });
        out_w.* = fitted.width;
        out_h.* = fitted.height;
        return pixels.ptr;
    }

    // An interlaced PNG's rows arrive in seven passes over the whole image, so it is
    // decoded whole and scaled afterwards, which only one within Ghostty's limit allows.
    if (src.width > max_png_dimension or src.height > max_png_dimension) {
        logWarn("interlaced png too large width={} height={}", .{ src.width, src.height });
        return null;
    }
    var whole_w: u32 = 0;
    var whole_h: u32 = 0;
    const whole = decodeWhole(bytes, &whole_w, &whole_h) orelse return null;
    defer freePixels(whole, whole_w, whole_h);
    if (whole_w != src.width or whole_h != src.height) {
        logWarn("png size differs from header width={} height={}", .{ whole_w, whole_h });
        return null;
    }
    const whole_len = @as(usize, whole_w) * whole_h * 4;
    const pixels = image_downscale.downscale(c_allocator, whole[0..whole_len], src, fitted) catch {
        logWarn("png downscale alloc failed width={} height={}", .{ fitted.width, fitted.height });
        return null;
    };
    logInfo("png scaled width={} height={} to width={} height={}", .{ src.width, src.height, fitted.width, fitted.height });
    out_w.* = fitted.width;
    out_h.* = fitted.height;
    return pixels.ptr;
}

/// Decodes a whole PNG with zigimg into RGBA pixels allocated with `c_allocator`.
fn decodeWhole(bytes: []const u8, out_w: *u32, out_h: *u32) ?[*]u8 {
    var read_stream = zigimg.io.ReadStream.initMemory(bytes);
    var img = zigimg.formats.png.PNG.readImage(c_allocator, &read_stream) catch {
        logWarn("zigimg decode failed len={}", .{bytes.len});
        return null;
    };
    defer img.deinit(c_allocator);

    img.convert(c_allocator, .rgba32) catch {
        logWarn("zigimg rgba32 convert failed len={}", .{bytes.len});
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

/// The largest width or height kept, the same limit Ghostty applies to Kitty images.
const max_png_dimension = 10_000;

/// The largest PNG decoded, scaling it down as it goes. Scaling streams the pixels
/// through, so the limit bounds the time spent rather than memory: this many pixels
/// take a few seconds, and a PNG of a few megabytes can claim far more.
const max_source_pixels = 1 << 28;
const max_source_dimension = 1 << 16;

const png_signature = "\x89PNG\r\n\x1a\n";

const PngHeader = struct { width: u32, height: u32, interlaced: bool };

/// The size and interlacing from the IHDR chunk, which must come first, or null when
/// `bytes` doesn't start with a PNG signature and an IHDR chunk.
fn pngHeader(bytes: []const u8) ?PngHeader {
    // Signature, then the IHDR chunk's length and type, then width, height, bit depth,
    // color type, compression, filter, and interlace method.
    if (bytes.len < png_signature.len + 8 + 13) return null;
    if (!std.mem.eql(u8, bytes[0..png_signature.len], png_signature)) return null;
    const chunk = bytes[png_signature.len..];
    if (!std.mem.eql(u8, chunk[4..8], "IHDR")) return null;
    return .{
        .width = std.mem.readInt(u32, chunk[8..12], .big),
        .height = std.mem.readInt(u32, chunk[12..16], .big),
        .interlaced = chunk[20] == 1,
    };
}

/// Free pixel data previously returned by `decodePng`.
pub fn freePixels(ptr: ?[*]u8, w: u32, h: u32) void {
    if (ptr) |p| {
        const total_bytes = @as(usize, w) * @as(usize, h) * 4;
        c_allocator.free(p[0..total_bytes]);
    }
}
