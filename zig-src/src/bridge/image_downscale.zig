//! Shrinks decoded Kitty images that are too large to store. The terminal refuses any
//! image whose pixels exceed its storage limit, or whose width or height exceeds
//! Ghostty's limit, so a large PNG, such as a diagram exported at 6000 pixels wide,
//! would show nothing. Scaled down to fit, it shows at a resolution still above most
//! screens.
//!
//! `decodePngScaled` scales a PNG down while decoding it, one row at a time, so a PNG
//! far too large to decode whole, such as 19366 by 7426 pixels (575 MB of RGBA), needs
//! only the scaled image and a few rows in memory.
const std = @import("std");

pub const Size = struct { width: u32, height: u32 };

/// The largest size with the aspect ratio of `width` by `height` that has at most
/// `max_pixels` pixels and at most `max_side` pixels on either side, or the size
/// itself when it already fits. Neither side drops below one pixel.
pub fn fittedSize(width: u32, height: u32, max_pixels: u64, max_side: u32) Size {
    std.debug.assert(width > 0 and height > 0 and max_pixels > 0 and max_side > 0);
    const pixels = @as(u64, width) * height;
    if (pixels <= max_pixels and width <= max_side and height <= max_side) {
        return .{ .width = width, .height = height };
    }

    const w_f: f64 = @floatFromInt(width);
    const h_f: f64 = @floatFromInt(height);
    const side_f: f64 = @floatFromInt(max_side);
    var scale = @sqrt(@as(f64, @floatFromInt(max_pixels)) / @as(f64, @floatFromInt(pixels)));
    scale = @min(scale, side_f / w_f, side_f / h_f, 1.0);
    var w: u64 = @min(max_side, @max(1, @as(u64, @intFromFloat(w_f * scale))));
    var h: u64 = @min(max_side, @max(1, @as(u64, @intFromFloat(h_f * scale))));
    // A side held at one pixel, or rounding in the scale, can leave the other too long.
    if (w * h > max_pixels) {
        if (w >= h) w = @max(1, max_pixels / h) else h = @max(1, max_pixels / w);
    }
    return .{ .width = @intCast(w), .height = @intCast(h) };
}

/// Scales straight-alpha RGBA rows of `src` size down to `dst` size as they arrive.
/// Each output pixel averages the block of source pixels it covers, weighting colors by
/// alpha so transparent pixels don't darken the edges.
const RowScaler = struct {
    src: Size,
    dst: Size,
    /// Where each output column starts in a source row, then the row's width.
    column_starts: []u32,
    /// Per output column: the alpha-weighted red, green, and blue, then the alpha sum.
    sums: []Sum,
    out: []u8,
    src_row: usize = 0,
    dst_row: usize = 0,

    const Sum = @Vector(4, u64);

    fn init(alloc: std.mem.Allocator, src: Size, dst: Size) !RowScaler {
        std.debug.assert(dst.width > 0 and dst.height > 0);
        std.debug.assert(dst.width <= src.width and dst.height <= src.height);
        const column_starts = try alloc.alloc(u32, @as(usize, dst.width) + 1);
        errdefer alloc.free(column_starts);
        for (column_starts, 0..) |*start, dx| start.* = @intCast(dx * src.width / dst.width);
        const sums = try alloc.alloc(Sum, dst.width);
        errdefer alloc.free(sums);
        @memset(sums, @splat(0));
        const out = try alloc.alloc(u8, @as(usize, dst.width) * dst.height * 4);
        return .{ .src = src, .dst = dst, .column_starts = column_starts, .sums = sums, .out = out };
    }

    /// Frees the working memory. The caller owns `out`.
    fn deinit(self: *RowScaler, alloc: std.mem.Allocator) void {
        alloc.free(self.column_starts);
        alloc.free(self.sums);
    }

    fn addRow(self: *RowScaler, row: []const u8) void {
        const src_h: usize = self.src.height;
        const dst_w: usize = self.dst.width;
        const dst_h: usize = self.dst.height;
        std.debug.assert(row.len == @as(usize, self.src.width) * 4 and self.src_row < src_h);

        for (self.sums, 0..) |*sum, dx| {
            var block: Sum = @splat(0);
            for (self.column_starts[dx]..self.column_starts[dx + 1]) |x| {
                const px: @Vector(4, u8) = row[x * 4 ..][0..4].*;
                const a: u64 = px[3];
                block += @as(Sum, px) * Sum{ a, a, a, 1 };
            }
            sum.* += block;
        }
        self.src_row += 1;

        const dy = self.dst_row;
        const y0 = dy * src_h / dst_h;
        const y1 = (dy + 1) * src_h / dst_h;
        if (self.src_row < y1) return;
        for (self.sums, 0..) |sum, dx| {
            const width = self.column_starts[dx + 1] - self.column_starts[dx];
            const count: u64 = (y1 - y0) * width;
            const alpha = sum[3];
            const out = self.out[(dy * dst_w + dx) * 4 ..][0..4];
            inline for (0..3) |ch| {
                out[ch] = if (alpha == 0) 0 else @intCast((sum[ch] + alpha / 2) / alpha);
            }
            out[3] = @intCast((alpha + count / 2) / count);
        }
        @memset(self.sums, @splat(0));
        self.dst_row += 1;
    }
};

/// Scales straight-alpha RGBA pixels of `src_size` down to `dst_size`, which must be no
/// larger on either side. Each output pixel averages the block of source pixels it
/// covers, weighting colors by alpha so transparent pixels don't darken the edges.
/// The caller owns the returned pixels.
pub fn downscale(alloc: std.mem.Allocator, src: []const u8, src_size: Size, dst_size: Size) ![]u8 {
    const row_len = @as(usize, src_size.width) * 4;
    std.debug.assert(src.len == row_len * src_size.height);
    var scaler = try RowScaler.init(alloc, src_size, dst_size);
    defer scaler.deinit(alloc);
    for (0..src_size.height) |y| scaler.addRow(src[y * row_len ..][0..row_len]);
    return scaler.out;
}

pub const DecodeError = error{
    /// Not a valid PNG, or one that ends early.
    InvalidData,
    /// An interlaced PNG, whose rows arrive in seven passes over the whole image.
    Unsupported,
    OutOfMemory,
};

const png_signature = "\x89PNG\r\n\x1a\n";

const ColorType = enum(u8) { gray = 0, rgb = 2, indexed = 3, gray_alpha = 4, rgba = 6, _ };

const Header = struct {
    width: u32,
    height: u32,
    bit_depth: u8,
    color: ColorType,
    interlaced: bool,

    fn parse(body: []const u8) DecodeError!Header {
        if (body.len != 13) return error.InvalidData;
        const header: Header = .{
            .width = std.mem.readInt(u32, body[0..4], .big),
            .height = std.mem.readInt(u32, body[4..8], .big),
            .bit_depth = body[8],
            .color = @fromBackingInt(@intCast(body[9])),
            .interlaced = body[12] == 1,
        };
        // Compression and filter method 0 are the only ones defined.
        if (body[10] != 0 or body[11] != 0 or body[12] > 1) return error.InvalidData;
        if (header.width == 0 or header.height == 0) return error.InvalidData;
        const depth_ok = switch (header.color) {
            .gray => switch (header.bit_depth) {
                1, 2, 4, 8, 16 => true,
                else => false,
            },
            .indexed => switch (header.bit_depth) {
                1, 2, 4, 8 => true,
                else => false,
            },
            .rgb, .gray_alpha, .rgba => header.bit_depth == 8 or header.bit_depth == 16,
            _ => false,
        };
        if (!depth_ok) return error.InvalidData;
        return header;
    }

    fn channels(self: Header) usize {
        return switch (self.color) {
            .gray, .indexed => 1,
            .gray_alpha => 2,
            .rgb => 3,
            .rgba => 4,
            _ => unreachable,
        };
    }

    fn lineBytes(self: Header) usize {
        return (@as(usize, self.width) * self.channels() * self.bit_depth + 7) / 8;
    }

    /// The bytes per complete pixel, at least one, which filters reach back by.
    fn filterStride(self: Header) usize {
        return @max(1, self.channels() * self.bit_depth / 8);
    }
};

/// The palette and transparency of a PNG, which turn its samples into RGBA.
const Colors = struct {
    palette: [256][4]u8 = undefined,
    palette_len: usize = 0,
    /// The gray value, or red, green, and blue, that marks transparent pixels.
    transparent_key: ?[3]u16 = null,
};

/// Decodes the PNG in `bytes`, whose size must be `src_size`, and scales it down to
/// `dst_size` as it goes. The result is the same as decoding the whole image to RGBA,
/// with 16-bit samples truncated to 8 bits, and calling `downscale`. Only the scaled
/// image and a few rows are in memory at once. The caller owns the returned pixels.
pub fn decodePngScaled(alloc: std.mem.Allocator, bytes: []const u8, src_size: Size, dst_size: Size) DecodeError![]u8 {
    if (bytes.len < png_signature.len or !std.mem.eql(u8, bytes[0..png_signature.len], png_signature)) {
        return error.InvalidData;
    }

    var header: ?Header = null;
    var colors: Colors = .{};
    var palette_alpha: []const u8 = &.{};
    // The compressed image data, joined from its IDAT chunks.
    var idat: std.ArrayList(u8) = .empty;
    defer idat.deinit(alloc);
    var idat_ended = false;

    var pos: usize = png_signature.len;
    while (true) {
        if (bytes.len - pos < 12) return error.InvalidData;
        const len = std.mem.readInt(u32, bytes[pos..][0..4], .big);
        const kind = bytes[pos + 4 ..][0..4];
        if (len > bytes.len - pos - 12) return error.InvalidData;
        const body = bytes[pos + 8 ..][0..len];
        pos += 12 + len;

        const h = header orelse {
            if (!std.mem.eql(u8, kind, "IHDR")) return error.InvalidData;
            const parsed = try Header.parse(body);
            if (parsed.interlaced) return error.Unsupported;
            if (parsed.width != src_size.width or parsed.height != src_size.height) return error.InvalidData;
            header = parsed;
            continue;
        };
        if (std.mem.eql(u8, kind, "IDAT")) {
            if (idat_ended) return error.InvalidData;
            try idat.appendSlice(alloc, body);
            continue;
        }
        if (idat.items.len > 0) idat_ended = true;
        if (std.mem.eql(u8, kind, "IEND")) break;
        if (std.mem.eql(u8, kind, "PLTE")) {
            if (len == 0 or len % 3 != 0 or len / 3 > 256) return error.InvalidData;
            colors.palette_len = len / 3;
            for (0..colors.palette_len) |i| {
                colors.palette[i] = .{ body[i * 3], body[i * 3 + 1], body[i * 3 + 2], 255 };
            }
        } else if (std.mem.eql(u8, kind, "tRNS")) {
            switch (h.color) {
                .indexed => palette_alpha = body,
                .gray => if (len == 2) {
                    const g = std.mem.readInt(u16, body[0..2], .big);
                    colors.transparent_key = .{ g, g, g };
                },
                .rgb => if (len == 6) {
                    colors.transparent_key = .{
                        std.mem.readInt(u16, body[0..2], .big),
                        std.mem.readInt(u16, body[2..4], .big),
                        std.mem.readInt(u16, body[4..6], .big),
                    };
                },
                else => {},
            }
        }
    }
    const h = header orelse return error.InvalidData;
    if (idat.items.len == 0) return error.InvalidData;
    if (h.color == .indexed) {
        if (colors.palette_len == 0) return error.InvalidData;
        for (palette_alpha[0..@min(palette_alpha.len, colors.palette_len)], 0..) |a, i| colors.palette[i][3] = a;
    }

    const line_len = h.lineBytes();
    const lines = try alloc.alloc(u8, line_len * 2);
    defer alloc.free(lines);
    @memset(lines, 0);
    var prev = lines[0..line_len];
    var cur = lines[line_len..];
    const rgba_row = try alloc.alloc(u8, @as(usize, h.width) * 4);
    defer alloc.free(rgba_row);
    const window = try alloc.alloc(u8, std.compress.flate.max_window_len);
    defer alloc.free(window);

    var scaler = try RowScaler.init(alloc, src_size, dst_size);
    defer scaler.deinit(alloc);
    errdefer alloc.free(scaler.out);

    var compressed: std.Io.Reader = .fixed(idat.items);
    var inflate: std.compress.flate.Decompress = .init(&compressed, .zlib, window);
    const stride = h.filterStride();
    for (0..h.height) |_| {
        var filter: [1]u8 = undefined;
        inflate.reader.readSliceAll(&filter) catch return error.InvalidData;
        inflate.reader.readSliceAll(cur) catch return error.InvalidData;
        try unfilter(filter[0], cur, prev, stride);
        try toRgba(h, &colors, cur, rgba_row);
        scaler.addRow(rgba_row);
        std.mem.swap([]u8, &prev, &cur);
    }
    return scaler.out;
}

/// Reverses a row's filter in place, given the unfiltered row above it.
fn unfilter(filter: u8, cur: []u8, prev: []const u8, stride: usize) DecodeError!void {
    switch (filter) {
        0 => {},
        2 => for (cur, prev) |*x, b| {
            x.* +%= b;
        },
        // The other filters predict from the pixel to the left, so they go a pixel at a
        // time, with its bytes in a vector.
        inline 1, 3, 4 => |f| switch (stride) {
            inline 1, 2, 3, 4, 6, 8 => |n| unfilterPixels(n, f, cur, prev),
            else => unreachable,
        },
        else => return error.InvalidData,
    }
}

/// Reverses the sub (1), average (3), or Paeth (4) filter on a row of `n`-byte pixels.
fn unfilterPixels(comptime n: usize, comptime filter: u8, cur: []u8, prev: []const u8) void {
    const Bytes = @Vector(n, u8);
    const Wide = @Vector(n, i16);
    std.debug.assert(cur.len % n == 0 and prev.len == cur.len);
    // The pixel to the left (a), above (b), and above left (c), zero past the left edge.
    var a: Wide = @splat(0);
    var c: Wide = @splat(0);
    var i: usize = 0;
    while (i < cur.len) : (i += n) {
        const above: Bytes = prev[i..][0..n].*;
        const b: Wide = above;
        const predictor: Wide = switch (filter) {
            1 => a,
            3 => (a + b) >> @splat(1),
            4 => paeth: {
                const p = a + b - c;
                const pa = @abs(p - a);
                const pb = @abs(p - b);
                const pc = @abs(p - c);
                const no: @Vector(n, bool) = @splat(false);
                const use_a = @select(bool, pa <= pb, pa <= pc, no);
                break :paeth @select(i16, use_a, a, @select(i16, pb <= pc, b, c));
            },
            else => unreachable,
        };
        const filtered: Bytes = cur[i..][0..n].*;
        const pixel = filtered +% @as(Bytes, @truncate(@as(@Vector(n, u16), @bitCast(predictor))));
        cur[i..][0..n].* = pixel;
        a = pixel;
        c = b;
    }
}

/// Sample `index` of an unfiltered row, of `depth` bits.
fn sample(line: []const u8, depth: u8, index: usize) u16 {
    return switch (depth) {
        16 => std.mem.readInt(u16, line[index * 2 ..][0..2], .big),
        8 => line[index],
        else => {
            const bit = index * depth;
            const shift: u3 = @intCast(8 - depth - bit % 8);
            const mask = (@as(u8, 1) << @intCast(depth)) - 1;
            return (line[bit / 8] >> shift) & mask;
        },
    };
}

/// A sample scaled to 8 bits: 16-bit samples truncated, as zigimg does, and smaller
/// ones stretched over the full range.
fn to8(value: u16, depth: u8) u8 {
    return switch (depth) {
        16 => @intCast(value >> 8),
        8 => @intCast(value),
        else => {
            const max = (@as(u16, 1) << @intCast(depth)) - 1;
            return @intCast((@as(u32, value) * 255 + max / 2) / max);
        },
    };
}

/// Converts one unfiltered row to straight-alpha RGBA.
fn toRgba(h: Header, colors: *const Colors, line: []const u8, out: []u8) DecodeError!void {
    const depth = h.bit_depth;
    // The usual formats, which most large PNGs use, without a switch per pixel.
    if (depth == 8 and h.color == .rgba) {
        @memcpy(out, line[0..out.len]);
        return;
    }
    if (depth == 8 and h.color == .rgb and colors.transparent_key == null) {
        for (0..h.width) |x| {
            out[x * 4 ..][0..3].* = line[x * 3 ..][0..3].*;
            out[x * 4 + 3] = 255;
        }
        return;
    }
    for (0..h.width) |x| {
        const px = out[x * 4 ..][0..4];
        switch (h.color) {
            .gray => {
                const g = sample(line, depth, x);
                const v = to8(g, depth);
                const key = colors.transparent_key;
                px.* = .{ v, v, v, if (key != null and key.?[0] == g) 0 else 255 };
            },
            .gray_alpha => {
                const v = to8(sample(line, depth, x * 2), depth);
                px.* = .{ v, v, v, to8(sample(line, depth, x * 2 + 1), depth) };
            },
            .rgb => {
                const r = sample(line, depth, x * 3);
                const g = sample(line, depth, x * 3 + 1);
                const b = sample(line, depth, x * 3 + 2);
                const transparent = if (colors.transparent_key) |key| key[0] == r and key[1] == g and key[2] == b else false;
                px.* = .{ to8(r, depth), to8(g, depth), to8(b, depth), if (transparent) 0 else 255 };
            },
            .rgba => for (0..4) |ch| {
                px[ch] = to8(sample(line, depth, x * 4 + ch), depth);
            },
            .indexed => {
                const index = sample(line, depth, x);
                if (index >= colors.palette_len) return error.InvalidData;
                px.* = colors.palette[index];
            },
            _ => unreachable,
        }
    }
}

const testing = std.testing;

test "an image within the limit keeps its size" {
    try testing.expectEqual(Size{ .width = 4000, .height = 4000 }, fittedSize(4000, 4000, 16 * 1024 * 1024, 10_000));
    try testing.expectEqual(Size{ .width = 1, .height = 1 }, fittedSize(1, 1, 1, 1));
}

test "a larger image fits the limit and keeps its aspect ratio" {
    const max = 16 * 1024 * 1024;
    const size = fittedSize(6000, 5128, max, 10_000);
    try testing.expect(@as(u64, size.width) * size.height <= max);
    // Within a pixel of the original ratio, and close to the limit.
    const expected_h = @as(f64, @floatFromInt(size.width)) * 5128.0 / 6000.0;
    try testing.expect(@abs(@as(f64, @floatFromInt(size.height)) - expected_h) <= 1.0);
    try testing.expect(@as(u64, size.width) * size.height > max - 2 * 6000);
}

test "a wide image fits the pixel limit with both sides under the side limit" {
    // The diagram that showed nothing: 19366 by 7426 pixels.
    const size = fittedSize(19366, 7426, 16 * 1024 * 1024, 10_000);
    try testing.expect(@as(u64, size.width) * size.height <= 16 * 1024 * 1024);
    try testing.expect(size.width <= 10_000 and size.height <= 10_000);
    const expected_h = @as(f64, @floatFromInt(size.width)) * 7426.0 / 19366.0;
    try testing.expect(@abs(@as(f64, @floatFromInt(size.height)) - expected_h) <= 1.0);
}

test "a long image within the pixel limit is held to the side limit" {
    const size = fittedSize(40_000, 300, 16 * 1024 * 1024, 10_000);
    try testing.expectEqual(Size{ .width = 10_000, .height = 75 }, size);
    const tall = fittedSize(300, 40_000, 16 * 1024 * 1024, 10_000);
    try testing.expectEqual(Size{ .width = 75, .height = 10_000 }, tall);
}

test "a long thin image keeps one pixel on its short side" {
    const size = fittedSize(10_000, 1, 100, 10_000);
    try testing.expectEqual(Size{ .width = 100, .height = 1 }, size);
    const tall = fittedSize(3, 10_000, 100, 10_000);
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

test "unfiltering reverses each filter type" {
    // Two-byte pixels (stride 2), with the row above known.
    const prev = [_]u8{ 10, 20, 30, 40, 50, 60 };
    const raw = [_]u8{ 1, 2, 200, 100, 7, 255 };
    // Filter each row as an encoder would, then check unfilter recovers it.
    for (0..5) |filter| {
        var row: [6]u8 = undefined;
        for (raw, 0..) |x, i| {
            const a: i16 = if (i >= 2) raw[i - 2] else 0;
            const b: i16 = prev[i];
            const c: i16 = if (i >= 2) prev[i - 2] else 0;
            const predictor: i16 = switch (filter) {
                0 => 0,
                1 => a,
                2 => b,
                3 => @divFloor(a + b, 2),
                else => paeth: {
                    const p = a + b - c;
                    const pa = @abs(p - a);
                    const pb = @abs(p - b);
                    const pc = @abs(p - c);
                    break :paeth if (pa <= pb and pa <= pc) a else if (pb <= pc) b else c;
                },
            };
            row[i] = x -% @as(u8, @intCast(predictor));
        }
        try unfilter(@intCast(filter), &row, &prev, 2);
        try testing.expectEqualSlices(u8, &raw, &row);
    }
    var row = raw;
    try testing.expectError(error.InvalidData, unfilter(5, &row, &prev, 2));
}

test "sub-byte samples are read high bits first and stretched to 8 bits" {
    const line = [_]u8{ 0b1011_0100, 0b1110_0001 };
    try testing.expectEqual(@as(u16, 1), sample(&line, 1, 0));
    try testing.expectEqual(@as(u16, 0), sample(&line, 1, 1));
    try testing.expectEqual(@as(u16, 0b10), sample(&line, 2, 0));
    try testing.expectEqual(@as(u16, 0b01), sample(&line, 2, 2));
    try testing.expectEqual(@as(u16, 0b00), sample(&line, 2, 3));
    try testing.expectEqual(@as(u16, 0b0100), sample(&line, 4, 1));
    try testing.expectEqual(@as(u16, 0b1110_0001), sample(&line, 8, 1));
    try testing.expectEqual(@as(u16, 0b1011_0100_1110_0001), sample(&line, 16, 0));
    try testing.expectEqual(@as(u8, 255), to8(1, 1));
    try testing.expectEqual(@as(u8, 85), to8(1, 2));
    try testing.expectEqual(@as(u8, 17), to8(1, 4));
    try testing.expectEqual(@as(u8, 0xb4), to8(0xb4e1, 16));
}

// zigimg decodes the whole image, as the app does for PNGs within the limit, so it is
// the reference for decoding while scaling.
const zigimg = @import("zigimg");

/// Fills an image with pseudo-random pixels, palettes, and palette indices.
fn fillRandom(pixels: *zigimg.color.PixelStorage, random: std.Random) void {
    switch (pixels.*) {
        inline .indexed1, .indexed2, .indexed4, .indexed8 => |storage| {
            for (storage.palette) |*entry| {
                entry.* = .{ .r = random.int(u8), .g = random.int(u8), .b = random.int(u8), .a = random.int(u8) };
            }
            for (storage.indices) |*index| index.* = random.int(@TypeOf(index.*));
        },
        inline else => |storage| {
            if (@typeInfo(@TypeOf(storage)) != .pointer) return;
            for (storage) |*px| {
                const info = @typeInfo(@TypeOf(px.*)).@"struct";
                inline for (info.field_names, info.field_types) |name, T| {
                    if (@typeInfo(T) == .int) @field(px, name) = random.int(T);
                }
            }
        },
    }
}

/// A PNG of `width` by `height` random pixels in `format`, as zigimg writes it.
fn randomPng(alloc: std.mem.Allocator, width: u32, height: u32, format: zigimg.PixelFormat, seed: u64) ![]u8 {
    var image = try zigimg.Image.create(alloc, width, height, format);
    defer image.deinit(alloc);
    var prng = std.Random.DefaultPrng.init(seed);
    fillRandom(&image.pixels, prng.random());
    const buffer = try alloc.alloc(u8, 1024 * 1024);
    defer alloc.free(buffer);
    const png = try image.writeToMemory(alloc, buffer, .{ .png = .{} });
    return alloc.dupe(u8, png);
}

/// Decodes `png` whole with zigimg, then scales it with `downscale`.
fn referenceDecode(alloc: std.mem.Allocator, png: []const u8, dst: Size) ![]u8 {
    var stream = zigimg.io.ReadStream.initMemory(png);
    var image = try zigimg.formats.png.PNG.readImage(alloc, &stream);
    defer image.deinit(alloc);
    try image.convert(alloc, .rgba32);
    const src: Size = .{ .width = @intCast(image.width), .height = @intCast(image.height) };
    return downscale(alloc, image.rawBytes(), src, dst);
}

fn expectSameAsReference(png: []const u8, src: Size, dst: Size) !void {
    const expected = try referenceDecode(testing.allocator, png, dst);
    defer testing.allocator.free(expected);
    const actual = try decodePngScaled(testing.allocator, png, src, dst);
    defer testing.allocator.free(actual);
    try testing.expectEqualSlices(u8, expected, actual);
}

/// `png` with a chunk inserted right after its IHDR chunk.
fn withChunkAfterHeader(alloc: std.mem.Allocator, png: []const u8, kind: *const [4]u8, body: []const u8) ![]u8 {
    const header_end = png_signature.len + 12 + 13;
    var out: std.ArrayList(u8) = .empty;
    errdefer out.deinit(alloc);
    try out.appendSlice(alloc, png[0..header_end]);
    var len: [4]u8 = undefined;
    std.mem.writeInt(u32, &len, @intCast(body.len), .big);
    try out.appendSlice(alloc, &len);
    try out.appendSlice(alloc, kind);
    try out.appendSlice(alloc, body);
    var crc = std.hash.Crc32.init();
    crc.update(kind);
    crc.update(body);
    var crc_bytes: [4]u8 = undefined;
    std.mem.writeInt(u32, &crc_bytes, crc.final(), .big);
    try out.appendSlice(alloc, &crc_bytes);
    try out.appendSlice(alloc, png[header_end..]);
    return out.toOwnedSlice(alloc);
}

test "decoding while scaling matches decoding whole, for every PNG format" {
    const formats = [_]zigimg.PixelFormat{
        .rgba32,      .rgb24,           .rgba64,           .rgb48,
        .grayscale1,  .grayscale2,      .grayscale4,       .grayscale8,
        .grayscale16, .grayscale8Alpha, .grayscale16Alpha, .indexed1,
        .indexed2,    .indexed4,        .indexed8,
    };
    const src: Size = .{ .width = 37, .height = 23 };
    const targets = [_]Size{ src, .{ .width = 10, .height = 7 }, .{ .width = 36, .height = 5 }, .{ .width = 1, .height = 1 } };
    for (formats, 0..) |format, seed| {
        const png = try randomPng(testing.allocator, src.width, src.height, format, seed);
        defer testing.allocator.free(png);
        for (targets) |dst| {
            expectSameAsReference(png, src, dst) catch |err| {
                std.debug.print("format {t} to {}x{}\n", .{ format, dst.width, dst.height });
                return err;
            };
        }
    }
}

test "a transparent color key clears the alpha of matching pixels" {
    const src: Size = .{ .width = 16, .height = 9 };
    const dst: Size = .{ .width = 5, .height = 4 };
    // Few gray levels and colors, so plenty of pixels match the key.
    var image = try zigimg.Image.create(testing.allocator, src.width, src.height, .rgb24);
    defer image.deinit(testing.allocator);
    for (image.pixels.rgb24, 0..) |*px, i| {
        const v: u8 = @intCast(i % 3 * 100);
        px.* = .{ .r = v, .g = v, .b = 50 };
    }
    const buffer = try testing.allocator.alloc(u8, 64 * 1024);
    defer testing.allocator.free(buffer);
    const rgb = try image.writeToMemory(testing.allocator, buffer, .{ .png = .{} });
    const rgb_keyed = try withChunkAfterHeader(testing.allocator, rgb, "tRNS", &.{ 0, 100, 0, 100, 0, 50 });
    defer testing.allocator.free(rgb_keyed);
    try expectSameAsReference(rgb_keyed, src, src);
    try expectSameAsReference(rgb_keyed, src, dst);

    try image.convert(testing.allocator, .grayscale8);
    const gray = try image.writeToMemory(testing.allocator, buffer, .{ .png = .{} });
    const gray_keyed = try withChunkAfterHeader(testing.allocator, gray, "tRNS", &.{ 0, 200 });
    defer testing.allocator.free(gray_keyed);
    try expectSameAsReference(gray_keyed, src, src);
    try expectSameAsReference(gray_keyed, src, dst);
}

test "an interlaced PNG is left to the whole-image decoder" {
    // zigimg doesn't write interlaced PNGs, so mark a plain one as interlaced.
    const png = try randomPng(testing.allocator, 12, 8, .rgba32, 1);
    defer testing.allocator.free(png);
    png[png_signature.len + 8 + 12] = 1;
    try testing.expectError(error.Unsupported, decodePngScaled(testing.allocator, png, .{ .width = 12, .height = 8 }, .{ .width = 3, .height = 2 }));
}

test "a truncated or mislabeled PNG is invalid" {
    const src: Size = .{ .width = 20, .height = 20 };
    const dst: Size = .{ .width = 4, .height = 4 };
    const png = try randomPng(testing.allocator, src.width, src.height, .rgba32, 2);
    defer testing.allocator.free(png);
    for ([_]usize{ 0, 7, 20, 40, png.len / 2, png.len - 12 }) |len| {
        try testing.expectError(error.InvalidData, decodePngScaled(testing.allocator, png[0..len], src, dst));
    }
    try testing.expectError(error.InvalidData, decodePngScaled(testing.allocator, png, .{ .width = 21, .height = 20 }, dst));
}
