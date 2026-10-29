//! Timestamps for the bridges. Zig 0.17 moved clocks behind std.Io, so these
//! read the clocks through libc, as std.time did before.
const std = @import("std");

pub fn nanoTimestamp() i128 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.REALTIME, &ts) != 0) return 0;
    return @as(i128, ts.sec) * std.time.ns_per_s + ts.nsec;
}

pub fn milliTimestamp() i64 {
    return @intCast(@divTrunc(nanoTimestamp(), std.time.ns_per_ms));
}

/// Milliseconds on CLOCK_MONOTONIC, for intervals. It never jumps when the wall
/// clock is set, which makes it the clock for Kitty animation timing.
pub fn monotonicMillis() u64 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.MONOTONIC, &ts) != 0) return 0;
    return @as(u64, @intCast(ts.sec)) * std.time.ms_per_s + @as(u64, @intCast(ts.nsec)) / std.time.ns_per_ms;
}
