//! Wall-clock timestamps for the bridges. Zig 0.17 moved clocks behind
//! std.Io, so these read CLOCK_REALTIME through libc, as std.time did before.
const std = @import("std");

pub fn nanoTimestamp() i128 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.REALTIME, &ts) != 0) return 0;
    return @as(i128, ts.sec) * std.time.ns_per_s + ts.nsec;
}

pub fn milliTimestamp() i64 {
    return @intCast(@divTrunc(nanoTimestamp(), std.time.ns_per_ms));
}
