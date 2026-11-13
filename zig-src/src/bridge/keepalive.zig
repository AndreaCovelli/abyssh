//! When an SSH connection that hears nothing counts as lost. Two counts run side by
//! side. The host's is its keepalive interval times the intervals that may pass with
//! nothing received, as OpenSSH's ServerAliveInterval and ServerAliveCountMax. A probe
//! is a shorter count the app starts when it has a reason to doubt the link, and ends
//! a few seconds later. The connection is lost when either runs out.
//!
//! A probe has its own start, so starting or ending one leaves the host's count where
//! it was: a probe that starts late in a silence can only make the connection fail
//! sooner than the host's count would, never later.
const std = @import("std");

/// The shortest interval libssh2 sends keepalives at.
pub const min_interval_s = 2;
pub const max_interval_s = 24 * 60 * 60;
pub const max_missed_limit = 100;

pub const Probe = struct {
    interval_s: u32,
    max_missed: u32,
    /// When the probe started, in the clock of `State.last_received_ms`.
    started_ms: u64,
};

pub const State = struct {
    /// Seconds between the host's keepalives, or 0 for none.
    interval_s: u32 = 0,
    max_missed: u32 = 3,
    /// When the host's count last started over because its interval changed.
    counted_from_ms: u64 = 0,
    /// When the socket last delivered bytes, in CLOCK_MONOTONIC ms. That clock stops
    /// while the phone sleeps, so a nap doesn't count as missed replies.
    last_received_ms: u64 = 0,
    probe: ?Probe = null,

    /// Sets the host's keepalive and starts its count over, so a shorter interval
    /// doesn't make the silence under the longer one count as lost. An interval of 1
    /// becomes 2. A running probe keeps its own count.
    pub fn setHost(self: *State, interval_s: i32, max_missed: i32, now_ms: u64) void {
        self.interval_s = if (interval_s <= 0) 0 else clampInterval(interval_s);
        self.max_missed = clampMissed(max_missed);
        self.counted_from_ms = now_ms;
    }

    /// Starts a probe, in place of any that is running.
    pub fn startProbe(self: *State, interval_s: i32, max_missed: i32, now_ms: u64) void {
        self.probe = .{ .interval_s = clampInterval(interval_s), .max_missed = clampMissed(max_missed), .started_ms = now_ms };
    }

    pub fn endProbe(self: *State) void {
        self.probe = null;
    }

    /// Seconds between keepalives now, or 0 for none: the shorter of the host's
    /// interval and the probe's.
    pub fn sendIntervalS(self: State) u32 {
        const probe = self.probe orelse return self.interval_s;
        if (self.interval_s == 0) return probe.interval_s;
        return @min(self.interval_s, probe.interval_s);
    }

    /// How long until the connection counts as lost if nothing arrives, 0 once it
    /// does, or null when no count is running.
    pub fn msUntilLost(self: State, now_ms: u64) ?u64 {
        var left: ?u64 = null;
        if (self.interval_s > 0) {
            left = limitMs(self.interval_s, self.max_missed) -| (now_ms -| @max(self.last_received_ms, self.counted_from_ms));
        }
        if (self.probe) |probe| {
            const probe_left = limitMs(probe.interval_s, probe.max_missed) -| (now_ms -| @max(self.last_received_ms, probe.started_ms));
            left = if (left) |host_left| @min(host_left, probe_left) else probe_left;
        }
        return left;
    }

    /// The seconds of silence that ran out, for the error message, or null while the
    /// connection isn't lost.
    pub fn lostAfterS(self: State, now_ms: u64) ?u64 {
        if (self.probe) |probe| {
            const limit = limitMs(probe.interval_s, probe.max_missed);
            if (now_ms -| @max(self.last_received_ms, probe.started_ms) >= limit) return limit / std.time.ms_per_s;
        }
        if (self.interval_s > 0) {
            const limit = limitMs(self.interval_s, self.max_missed);
            if (now_ms -| @max(self.last_received_ms, self.counted_from_ms) >= limit) return limit / std.time.ms_per_s;
        }
        return null;
    }
};

fn clampInterval(interval_s: i32) u32 {
    return @intCast(std.math.clamp(interval_s, min_interval_s, max_interval_s));
}

fn clampMissed(max_missed: i32) u32 {
    return @intCast(std.math.clamp(max_missed, 1, max_missed_limit));
}

fn limitMs(interval_s: u32, max_missed: u32) u64 {
    return @as(u64, interval_s) * std.time.ms_per_s * max_missed;
}

const expectEqual = std.testing.expectEqual;

test "nothing counts while keepalives are off and no probe runs" {
    const state: State = .{};
    try expectEqual(0, state.sendIntervalS());
    try expectEqual(null, state.msUntilLost(1_000_000));
    try expectEqual(null, state.lostAfterS(1_000_000));
}

test "the host's count runs from the last bytes received" {
    var state: State = .{};
    state.setHost(30, 3, 1_000);
    try expectEqual(30, state.sendIntervalS());
    try expectEqual(90_000, state.msUntilLost(1_000));
    state.last_received_ms = 50_000;
    try expectEqual(90_000, state.msUntilLost(50_000));
    try expectEqual(1, state.msUntilLost(139_999));
    try expectEqual(null, state.lostAfterS(139_999));
    try expectEqual(0, state.msUntilLost(140_000));
    try expectEqual(90, state.lostAfterS(140_000));
}

test "a new host interval starts the host's count over" {
    var state: State = .{};
    state.setHost(120, 3, 0);
    state.last_received_ms = 10_000;
    // 100 s of silence under the long interval would already be past the short one's limit.
    state.setHost(30, 3, 110_000);
    try expectEqual(null, state.lostAfterS(110_000));
    try expectEqual(90, state.lostAfterS(200_000));
}

test "the host's interval and count are clamped" {
    var state: State = .{};
    state.setHost(1, 0, 0);
    try expectEqual(2, state.sendIntervalS());
    try expectEqual(2_000, state.msUntilLost(0));
    state.setHost(std.math.maxInt(i32), std.math.maxInt(i32), 0);
    try expectEqual(max_interval_s, state.sendIntervalS());
    try expectEqual(@as(u64, max_interval_s) * 1_000 * max_missed_limit, state.msUntilLost(0));
    state.setHost(-5, 3, 0);
    try expectEqual(0, state.sendIntervalS());
}

test "a probe fails when nothing arrives for its limit after it started" {
    var state: State = .{};
    state.setHost(30, 3, 0);
    state.last_received_ms = 1_000;
    state.startProbe(5, 3, 20_000);
    try expectEqual(5, state.sendIntervalS());
    try expectEqual(15_000, state.msUntilLost(20_000));
    try expectEqual(null, state.lostAfterS(34_999));
    try expectEqual(15, state.lostAfterS(35_000));
}

test "bytes that arrive during a probe start its count over" {
    var state: State = .{};
    state.setHost(30, 3, 0);
    state.startProbe(2, 2, 10_000);
    state.last_received_ms = 12_000;
    try expectEqual(null, state.lostAfterS(15_999));
    try expectEqual(4, state.lostAfterS(16_000));
}

test "a probe that starts late in a silence doesn't outlast the host's count" {
    var state: State = .{};
    state.setHost(30, 3, 0);
    // The link died at 0; the user types at 80 s and the probe starts at 83 s.
    state.startProbe(5, 3, 83_000);
    try expectEqual(7_000, state.msUntilLost(83_000));
    try expectEqual(null, state.lostAfterS(89_999));
    try expectEqual(90, state.lostAfterS(90_000));
}

test "ending a probe restores the host's interval without restarting its count" {
    var state: State = .{};
    state.setHost(30, 3, 0);
    state.last_received_ms = 40_000;
    state.startProbe(5, 3, 60_000);
    state.last_received_ms = 61_000;
    state.endProbe();
    try expectEqual(30, state.sendIntervalS());
    try expectEqual(90_000 - 16_000, state.msUntilLost(77_000));
}

test "a host without keepalives is probed and has no count once the probe ends" {
    var state: State = .{};
    state.startProbe(5, 3, 1_000);
    try expectEqual(5, state.sendIntervalS());
    try expectEqual(15, state.lostAfterS(16_000));
    state.endProbe();
    try expectEqual(0, state.sendIntervalS());
    try expectEqual(null, state.lostAfterS(16_000));
}

test "a probe never sends keepalives less often than the host" {
    var state: State = .{};
    state.setHost(2, 3, 0);
    state.startProbe(5, 3, 0);
    try expectEqual(2, state.sendIntervalS());
    try expectEqual(6_000, state.msUntilLost(0));
}

test "a new probe replaces the running one" {
    var state: State = .{};
    state.startProbe(5, 3, 0);
    state.startProbe(2, 2, 10_000);
    try expectEqual(2, state.sendIntervalS());
    try expectEqual(null, state.lostAfterS(13_999));
    try expectEqual(4, state.lostAfterS(14_000));
}

test "a host interval set during a probe leaves the probe's count alone" {
    var state: State = .{};
    state.setHost(30, 3, 0);
    state.startProbe(5, 3, 10_000);
    state.setHost(120, 3, 20_000);
    try expectEqual(5, state.sendIntervalS());
    try expectEqual(15, state.lostAfterS(25_000));
}
