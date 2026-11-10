//! Puts decoded Kitty images in GPU memory. Each image is copied once, premultiplied,
//! into an AHardwareBuffer, which Kotlin wraps with `Bitmap.wrapHardwareBuffer`. The
//! renderer draws from that buffer directly, so no software bitmap holds a second copy
//! of the pixels and hwui uploads no third.
const std = @import("std");
const c = @import("c");

const log_tag = "AbyssKittyNative";

/// Copies straight-alpha RGBA `pixels` of `width` by `height` into a new hardware
/// buffer, multiplying each color by alpha as Android's bitmaps expect, and returns it
/// as a local reference to a Java `HardwareBuffer`. Returns null when the buffer can't
/// be allocated or locked, such as for an image larger than the GPU's textures.
pub fn newHardwareBuffer(env: *c.JNIEnv, pixels: []const u8, width: u32, height: u32) c.jobject {
    const row_bytes = @as(usize, width) * 4;
    if (width == 0 or height == 0 or pixels.len < row_bytes * height) return null;

    var desc = std.mem.zeroes(c.AHardwareBuffer_Desc);
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = c.AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = c.AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | c.AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    var buffer: ?*c.AHardwareBuffer = null;
    const allocated = c.AHardwareBuffer_allocate(&desc, &buffer);
    if (allocated != 0 or buffer == null) {
        logWarn("hardware buffer allocation failed size={}x{} status={}", .{ width, height, allocated });
        return null;
    }
    // The Java object takes its own reference, so this one is always released.
    defer c.AHardwareBuffer_release(buffer);

    // The stride, in pixels, can be wider than the image.
    c.AHardwareBuffer_describe(buffer, &desc);
    var address: ?*anyopaque = null;
    const locked = c.AHardwareBuffer_lock(buffer, c.AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, null, &address);
    if (locked != 0 or address == null) {
        logWarn("hardware buffer lock failed size={}x{} status={}", .{ width, height, locked });
        return null;
    }
    const dst: [*]u8 = @ptrCast(address.?);
    const stride_bytes = @as(usize, desc.stride) * 4;
    for (0..height) |y| {
        copyPremultiplied(dst[y * stride_bytes ..][0..row_bytes], pixels[y * row_bytes ..][0..row_bytes]);
    }
    _ = c.AHardwareBuffer_unlock(buffer, null);
    return c.AHardwareBuffer_toHardwareBuffer(env, buffer);
}

/// Copies RGBA pixels, multiplying each color channel by alpha. Kitty images carry
/// straight alpha, but Android's bitmaps hold premultiplied pixels: a transparent
/// #FFFFFF00 pixel, as ImageMagick writes around snacks.nvim's math images, would
/// otherwise draw as solid white.
fn copyPremultiplied(dst: []u8, src: []const u8) void {
    var i: usize = 0;
    while (i + 4 <= src.len) : (i += 4) {
        const a: u32 = src[i + 3];
        if (a == 0xff) {
            @memcpy(dst[i .. i + 4], src[i .. i + 4]);
            continue;
        }
        inline for (0..3) |ch| {
            dst[i + ch] = @intCast((@as(u32, src[i + ch]) * a + 127) / 255);
        }
        dst[i + 3] = src[i + 3];
    }
}

fn logWarn(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const line = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch return;
    _ = c.__android_log_write(c.ANDROID_LOG_WARN, log_tag, line.ptr);
}
