//! How much of a fling reaches a full-screen program. After the finger lifts, the
//! canvas keeps sending scroll steps marked as momentum. In the scrollback they all
//! scroll. A program that takes the mouse gets them as wheel events, and one in
//! alternate scroll mode as arrow keys, so there they are capped: each step sends a
//! few rows at most, and the fling stops reaching the program a short time after the
//! finger's last scroll, so a flick can't queue a long burst over a slow link.
const std = @import("std");

/// How long after the finger's last scroll step a fling still reaches the program.
pub const window_ms: u64 = 1000;

/// The most rows one fling step sends to the program. The canvas merges scroll steps
/// into at most one a frame, so this caps the rows a frame sends.
pub const max_rows_per_step: i32 = 6;

/// The rows of a fling step of [delta] rows that go to the program, [elapsed_ms]
/// after the finger's last scroll step, or null when the finger never scrolled.
pub fn rowsForProgram(delta: i32, elapsed_ms: ?u64) i32 {
    const elapsed = elapsed_ms orelse return 0;
    if (elapsed > window_ms) return 0;
    return std.math.clamp(delta, -max_rows_per_step, max_rows_per_step);
}

test "a fling step right after the finger scrolled goes through" {
    try std.testing.expectEqual(@as(i32, 2), rowsForProgram(2, 16));
    try std.testing.expectEqual(@as(i32, -3), rowsForProgram(-3, 0));
}

test "a large fling step is capped in either direction" {
    try std.testing.expectEqual(max_rows_per_step, rowsForProgram(40, 100));
    try std.testing.expectEqual(-max_rows_per_step, rowsForProgram(-40, 100));
}

test "a fling stops reaching the program once the window closes" {
    try std.testing.expectEqual(@as(i32, 1), rowsForProgram(1, window_ms));
    try std.testing.expectEqual(@as(i32, 0), rowsForProgram(1, window_ms + 1));
}

test "a fling without a finger scroll before it sends nothing" {
    try std.testing.expectEqual(@as(i32, 0), rowsForProgram(5, null));
}
