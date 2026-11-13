//! How a shell channel whose output is paused is read and waited on. A paused channel
//! still processes the socket, so keepalives, their replies, and the server's EOF and
//! close are handled, but nothing takes its output off libssh2's queue. libssh2 widens
//! the channel's receive window only when a read takes data off that queue, so once
//! the queue holds a full window the server stops sending, and the program writing to
//! the terminal blocks until the output is resumed and read.
const std = @import("std");

pub const ReadMode = enum {
    /// Read the queued output, which lets libssh2 widen the window again.
    read,
    /// Process the socket without taking output off the queue.
    pump,
};

/// How to read a channel. Once the server has sent EOF or closed the channel, nothing
/// more comes after the queued output, so a paused channel reads it too and reaches
/// the end, rather than keeping a finished session open while it is hidden. A server
/// sends its EOF after the output before it, so when the window is full the EOF waits
/// with the output the server has no credit to send, and the session ends once resumed.
pub fn readMode(paused: bool, remote_finished: bool) ReadMode {
    if (paused and !remote_finished) return .pump;
    return .read;
}

/// Whether a wait returns at once, before polling the socket. [queued] is the output
/// libssh2 holds for the channel, and [eof] whether libssh2_channel_eof reports the
/// end. Queued output makes a running channel's wait return, so it is read; a paused
/// channel keeps it queued and waits on the socket instead, until the server finishes.
pub fn waitReadyAtOnce(paused: bool, queued: u64, eof: bool, remote_finished: bool) bool {
    if (eof) return true;
    if (paused) return remote_finished;
    return queued > 0;
}

test "a running channel reads whatever arrived" {
    try std.testing.expectEqual(ReadMode.read, readMode(false, false));
    try std.testing.expectEqual(ReadMode.read, readMode(false, true));
}

test "a paused channel only pumps until the server finishes" {
    try std.testing.expectEqual(ReadMode.pump, readMode(true, false));
    try std.testing.expectEqual(ReadMode.read, readMode(true, true));
}

test "a running channel's wait returns at once for queued output or EOF" {
    try std.testing.expect(waitReadyAtOnce(false, 1, false, false));
    try std.testing.expect(waitReadyAtOnce(false, 0, true, true));
    try std.testing.expect(!waitReadyAtOnce(false, 0, false, false));
}

test "a paused channel's wait ignores queued output" {
    try std.testing.expect(!waitReadyAtOnce(true, 2 * 1024 * 1024, false, false));
    try std.testing.expect(!waitReadyAtOnce(true, 0, false, false));
}

test "a paused channel's wait returns once the server finishes" {
    try std.testing.expect(waitReadyAtOnce(true, 4096, false, true));
    try std.testing.expect(waitReadyAtOnce(true, 0, true, true));
}
