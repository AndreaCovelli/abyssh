//! Shrinks decoded Kitty images that are too large to store. The terminal refuses any
//! image whose pixels exceed its storage limit, so a large PNG, such as a diagram
//! exported at 6000 pixels wide, would show nothing. Scaled down to fit, it shows at a
//! resolution still above most screens.
const std = @import("std");

pub const Size = struct { width: u32, height: u32 };

/// The largest size with the aspect ratio of `width` by `height` that has at most
/// `max_pixels` pixels, or the size itself when it already fits. Neither side drops
/// below one pixel.
pub fn fittedSize(width: u32, height: u32, max_pixels: u64) Size {
    std.debug.assert(width > 0 and height > 0 and max_pixels > 0);
    const pixels = @as(u64, width) * height;
    if (pixels <= max_pixels) return .{ .width = width, .height = height };

    const scale = @sqrt(@as(f64, @floatFromInt(max_pixels)) / @as(f64, @floatFromInt(pixels)));
    var w: u64 = @max(1, @as(u64, @intFromFloat(@as(f64, @floatFromInt(width)) * scale)));
    var h: u64 = @max(1, @as(u64, @intFromFloat(@as(f64, @floatFromInt(height)) * scale)));
    // A side held at one pixel, or rounding in the scale, can leave the other too long.
    if (w * h > max_pixels) {
        if (w >= h) w = @max(1, max_pixels / h) else h = @max(1, max_pixels / w);
    }
    return .{ .width = @intCast(w), .height = @intCast(h) };
}

/// Scales straight-alpha RGBA pixels of `src_size` down to `dst_size`, which must be no
/// larger on either side. Each output pixel averages the block of source pixels it
/// covers, weighting colors by alpha so transparent pixels don't darken the edges.
/// The caller owns the returned pixels.
pub fn downscale(alloc: std.mem.Allocator, src: []const u8, src_size: Size, dst_size: Size) ![]u8 {
    std.debug.assert(dst_size.width > 0 and dst_size.height > 0);
    std.debug.assert(dst_size.width <= src_size.width and dst_size.height <= src_size.height);
    const src_w: usize = src_size.width;
    const src_h: usize = src_size.height;
    const dst_w: usize = dst_size.width;
    const dst_h: usize = dst_size.height;
    std.debug.assert(src.len == src_w * src_h * 4);

    const dst = try alloc.alloc(u8, dst_w * dst_h * 4);
    errdefer alloc.free(dst);

    for (0..dst_h) |dy| {
        const y0 = dy * src_h / dst_h;
        const y1 = (dy + 1) * src_h / dst_h;
        for (0..dst_w) |dx| {
            const x0 = dx * src_w / dst_w;
            const x1 = (dx + 1) * src_w / dst_w;
            var weighted = [3]u64{ 0, 0, 0 };
            var alpha: u64 = 0;
            for (y0..y1) |sy| {
                const row = src[(sy * src_w + x0) * 4 .. (sy * src_w + x1) * 4];
                var i: usize = 0;
                while (i < row.len) : (i += 4) {
                    const a: u64 = row[i + 3];
                    weighted[0] += @as(u64, row[i]) * a;
                    weighted[1] += @as(u64, row[i + 1]) * a;
                    weighted[2] += @as(u64, row[i + 2]) * a;
                    alpha += a;
                }
            }
            const count: u64 = (y1 - y0) * (x1 - x0);
            const out = dst[(dy * dst_w + dx) * 4 ..][0..4];
            for (0..3) |ch| {
                out[ch] = if (alpha == 0) 0 else @intCast((weighted[ch] + alpha / 2) / alpha);
            }
            out[3] = @intCast((alpha + count / 2) / count);
        }
    }
    return dst;
}

const testing = std.testing;

test "an image within the limit keeps its size" {
    try testing.expectEqual(Size{ .width = 4000, .height = 4000 }, fittedSize(4000, 4000, 16 * 1024 * 1024));
    try testing.expectEqual(Size{ .width = 1, .height = 1 }, fittedSize(1, 1, 1));
}

test "a larger image fits the limit and keeps its aspect ratio" {
    const max = 16 * 1024 * 1024;
    const size = fittedSize(6000, 5128, max);
    try testing.expect(@as(u64, size.width) * size.height <= max);
    // Within a pixel of the original ratio, and close to the limit.
    const expected_h = @as(f64, @floatFromInt(size.width)) * 5128.0 / 6000.0;
    try testing.expect(@abs(@as(f64, @floatFromInt(size.height)) - expected_h) <= 1.0);
    try testing.expect(@as(u64, size.width) * size.height > max - 2 * 6000);
}

test "a long thin image keeps one pixel on its short side" {
    const size = fittedSize(10_000, 1, 100);
    try testing.expectEqual(Size{ .width = 100, .height = 1 }, size);
    const tall = fittedSize(3, 10_000, 100);
    try testing.expect(tall.width >= 1 and @as(u64, tall.width) * tall.height <= 100);
}

test "downscaling averages each block" {
    // 4x2 to 2x1: the left block is two whites and two blacks, the right all red.
    const src = [_]u8{
        255, 255, 255, 255, 0,   0,   0,   255, 255, 0, 0, 255, 255, 0, 0, 255,
        0,   0,   0,   255, 255, 255, 255, 255, 255, 0, 0, 255, 255, 0, 0, 255,
    };
    const dst = try downscale(testing.allocator, &src, .{ .width = 4, .height = 2 }, .{ .width = 2, .height = 1 });
    defer testing.allocator.free(dst);
    try testing.expectEqualSlices(u8, &.{ 128, 128, 128, 255, 255, 0, 0, 255 }, dst);
}

test "transparent pixels don't change the color of a block" {
    // One opaque green pixel and three transparent white ones.
    const src = [_]u8{
        0,   255, 0,   255, 255, 255, 255, 0,
        255, 255, 255, 0,   255, 255, 255, 0,
    };
    const dst = try downscale(testing.allocator, &src, .{ .width = 2, .height = 2 }, .{ .width = 1, .height = 1 });
    defer testing.allocator.free(dst);
    try testing.expectEqualSlices(u8, &.{ 0, 255, 0, 64 }, dst);
}

test "a fully transparent block stays transparent" {
    const src = [_]u8{ 255, 255, 255, 0, 10, 20, 30, 0 };
    const dst = try downscale(testing.allocator, &src, .{ .width = 2, .height = 1 }, .{ .width = 1, .height = 1 });
    defer testing.allocator.free(dst);
    try testing.expectEqualSlices(u8, &.{ 0, 0, 0, 0 }, dst);
}

test "an uneven ratio covers every source pixel once" {
    // 5x1 to 2x1: blocks of two and three pixels.
    const src = [_]u8{
        0, 0, 0, 255, 100, 100, 100, 255, 30, 30, 30, 255, 60, 60, 60, 255, 90, 90, 90, 255,
    };
    const dst = try downscale(testing.allocator, &src, .{ .width = 5, .height = 1 }, .{ .width = 2, .height = 1 });
    defer testing.allocator.free(dst);
    try testing.expectEqualSlices(u8, &.{ 50, 50, 50, 255, 60, 60, 60, 255 }, dst);
}
