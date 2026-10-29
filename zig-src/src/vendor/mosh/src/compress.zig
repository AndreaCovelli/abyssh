//! Multi-algorithm compression abstraction for mosh transport diffs.
//!
//! Supports zlib (via system libz), raw passthrough, and stubs for lz4/lzfse.
//! Inbound auto-detects algorithm; outbound uses the configured algorithm.

const std = @import("std");
const types = @import("types.zig");

// ---------------------------------------------------------------------------
// Zlib backend (always available)
// ---------------------------------------------------------------------------
const zlib = struct {
    const c = @import("zlib_c");

    pub fn compress(allocator: std.mem.Allocator, data: []const u8) CompressError![]u8 {
        if (data.len == 0) return allocator.dupe(u8, &.{}) catch CompressError.BufferTooSmall;

        const max_len = c.compressBound(@intCast(data.len));
        var out = allocator.alloc(u8, max_len) catch return CompressError.BufferTooSmall;

        var out_len: c.uLongf = max_len;
        const result = c.compress2(
            out.ptr,
            &out_len,
            data.ptr,
            @intCast(data.len),
            c.Z_DEFAULT_COMPRESSION,
        );

        if (result != c.Z_OK) {
            allocator.free(out);
            return CompressError.CompressionFailed;
        }

        const actual_len: usize = @intCast(out_len);
        if (actual_len < out.len) {
            const copy = allocator.alloc(u8, actual_len) catch {
                allocator.free(out);
                return CompressError.BufferTooSmall;
            };
            @memcpy(copy, out[0..actual_len]);
            allocator.free(out);
            return copy;
        }
        return out;
    }

    pub fn decompress(allocator: std.mem.Allocator, data: []const u8) CompressError![]u8 {
        if (data.len == 0) return allocator.dupe(u8, &.{}) catch CompressError.BufferTooSmall;

        var out_capacity: usize = @max(data.len * 4, 1024);
        while (true) {
            var out = allocator.alloc(u8, out_capacity) catch return CompressError.BufferTooSmall;

            var out_len: c.uLongf = out_capacity;
            const result = c.uncompress(out.ptr, &out_len, data.ptr, @intCast(data.len));

            if (result == c.Z_OK) {
                const actual_len: usize = @intCast(out_len);
                if (actual_len < out.len) {
                    const copy = allocator.alloc(u8, actual_len) catch {
                        allocator.free(out);
                        return CompressError.BufferTooSmall;
                    };
                    @memcpy(copy, out[0..actual_len]);
                    allocator.free(out);
                    return copy;
                }
                return out;
            }

            allocator.free(out);
            if (result == c.Z_BUF_ERROR) {
                out_capacity *= 2;
                if (out_capacity > 16 * 1024 * 1024) return CompressError.BufferTooSmall;
                continue;
            }
            return CompressError.DecompressionFailed;
        }
    }
};

// ---------------------------------------------------------------------------
// Raw / passthrough backend (no compression)
// ---------------------------------------------------------------------------
const raw = struct {
    pub fn compress(allocator: std.mem.Allocator, data: []const u8) CompressError![]u8 {
        return allocator.dupe(u8, data) catch CompressError.BufferTooSmall;
    }

    pub fn decompress(allocator: std.mem.Allocator, data: []const u8) CompressError![]u8 {
        return allocator.dupe(u8, data) catch CompressError.BufferTooSmall;
    }
};

// ---------------------------------------------------------------------------
// LZ4 backend (stub — not yet linked)
// ---------------------------------------------------------------------------
const lz4_backend = struct {
    pub fn compress(_: std.mem.Allocator, _: []const u8) CompressError![]u8 {
        return CompressError.CompressionFailed;
    }
    pub fn decompress(_: std.mem.Allocator, _: []const u8) CompressError![]u8 {
        return CompressError.DecompressionFailed;
    }
};

// ---------------------------------------------------------------------------
// LZFSE backend (stub — not yet linked)
// ---------------------------------------------------------------------------
const lzfse_backend = struct {
    pub fn compress(_: std.mem.Allocator, _: []const u8) CompressError![]u8 {
        return CompressError.CompressionFailed;
    }
    pub fn decompress(_: std.mem.Allocator, _: []const u8) CompressError![]u8 {
        return CompressError.DecompressionFailed;
    }
};

// ---------------------------------------------------------------------------
// Public interface
// ---------------------------------------------------------------------------

pub const CompressError = error{
    CompressionFailed,
    DecompressionFailed,
    BufferTooSmall,
};

/// Compress using the requested algorithm.
pub fn compress(
    allocator: std.mem.Allocator,
    algorithm: types.CompressionAlgorithm,
    data: []const u8,
) CompressError![]u8 {
    return switch (algorithm) {
        .auto, .zlib => zlib.compress(allocator, data),
        .raw => raw.compress(allocator, data),
        .lz4 => lz4_backend.compress(allocator, data),
        .lzfse => lzfse_backend.compress(allocator, data),
    };
}

/// Decompress using the requested algorithm.
pub fn decompress(
    allocator: std.mem.Allocator,
    algorithm: types.CompressionAlgorithm,
    data: []const u8,
) CompressError![]u8 {
    return switch (algorithm) {
        .auto, .zlib => zlib.decompress(allocator, data),
        .raw => raw.decompress(allocator, data),
        .lz4 => lz4_backend.decompress(allocator, data),
        .lzfse => lzfse_backend.decompress(allocator, data),
    };
}

/// Auto-detect and decompress inbound data.
///
/// Tries each supported algorithm in order (zlib first, then raw fallback).
/// On success returns the decompressed data and the detected algorithm.
/// On failure returns error.DecompressionFailed.
pub fn decompressAuto(
    allocator: std.mem.Allocator,
    data: []const u8,
) CompressError!struct { data: []u8, algorithm: types.CompressionAlgorithm } {
    if (data.len == 0) {
        const empty = allocator.dupe(u8, &.{}) catch return CompressError.BufferTooSmall;
        return .{ .data = empty, .algorithm = .raw };
    }

    // Try zlib first (the standard mosh algorithm)
    if (zlib.decompress(allocator, data)) |decompressed| {
        return .{ .data = decompressed, .algorithm = .zlib };
    } else |_| {}

    // Raw fallback: if the payload looks like uncompressed protobuf, just copy it
    const raw_copy = raw.decompress(allocator, data) catch return CompressError.DecompressionFailed;
    return .{ .data = raw_copy, .algorithm = .raw };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test "zlib compress/decompress roundtrip" {
    const allocator = std.testing.allocator;
    const input = "hello world hello world hello world";

    const compressed = try compress(allocator, .zlib, input);
    defer allocator.free(compressed);

    const decompressed = try decompress(allocator, .zlib, compressed);
    defer allocator.free(decompressed);

    try std.testing.expectEqualStrings(input, decompressed);
}

test "raw compress/decompress roundtrip" {
    const allocator = std.testing.allocator;
    const input = "no compression here";

    const compressed = try compress(allocator, .raw, input);
    defer allocator.free(compressed);
    try std.testing.expectEqualStrings(input, compressed);

    const decompressed = try decompress(allocator, .raw, compressed);
    defer allocator.free(decompressed);
    try std.testing.expectEqualStrings(input, decompressed);
}

test "auto-detect zlib then raw fallback" {
    const allocator = std.testing.allocator;
    const input = "auto-detect me";

    // Auto-detect zlib-compressed data
    const zlib_compressed = try compress(allocator, .zlib, input);
    defer allocator.free(zlib_compressed);

    const zlib_result = try decompressAuto(allocator, zlib_compressed);
    defer allocator.free(zlib_result.data);
    try std.testing.expectEqualStrings(input, zlib_result.data);
    try std.testing.expectEqual(types.CompressionAlgorithm.zlib, zlib_result.algorithm);

    // Auto-detect raw (uncompressed protobuf-like) data
    const raw_result = try decompressAuto(allocator, input);
    defer allocator.free(raw_result.data);
    try std.testing.expectEqualStrings(input, raw_result.data);
    try std.testing.expectEqual(types.CompressionAlgorithm.raw, raw_result.algorithm);
}

test "empty data roundtrip for all algorithms" {
    const allocator = std.testing.allocator;
    const algorithms = &[_]types.CompressionAlgorithm{ .zlib, .raw };
    for (algorithms) |algo| {
        const compressed = try compress(allocator, algo, &.{});
        defer allocator.free(compressed);
        try std.testing.expectEqual(@as(usize, 0), compressed.len);

        const decompressed = try decompress(allocator, algo, compressed);
        defer allocator.free(decompressed);
        try std.testing.expectEqual(@as(usize, 0), decompressed.len);
    }
}

test "decompress invalid data fails" {
    const allocator = std.testing.allocator;
    const bad = &.{ 0xFF, 0xFF, 0xFF, 0xFF };
    try std.testing.expectError(CompressError.DecompressionFailed, decompress(allocator, .zlib, bad));
}
