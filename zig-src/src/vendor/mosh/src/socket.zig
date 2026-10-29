//! Cross-platform UDP socket wrapper for mosh transport.
//!
//! Uses libc sockets directly for Android/iOS compatibility.

const std = @import("std");

pub const SocketError = error{
    CreateFailed,
    BindFailed,
    ResolveFailed,
    SendFailed,
    ReceiveFailed,
    WouldBlock,
    NotOpen,
    AlreadyOpen,
};

pub const MoshSocket = struct {
    const Self = @This();
    const sockaddr_in = std.c.sockaddr.in;

    fd: ?std.c.fd_t = null,
    remote_addr: sockaddr_in = std.mem.zeroes(sockaddr_in),
    local_port: u16 = 0,

    pub fn init() Self {
        return .{};
    }

    /// Open a non-blocking UDP socket and optionally bind to `local_port`.
    /// If `local_port` is 0 the OS assigns an ephemeral port.
    pub fn open(self: *Self, local_port: u16) SocketError!void {
        if (self.fd != null) return SocketError.AlreadyOpen;

        const sock = std.c.socket(
            std.c.AF.INET,
            std.c.SOCK.DGRAM | std.c.SOCK.NONBLOCK | std.c.SOCK.CLOEXEC,
            0,
        );
        if (sock < 0) return SocketError.CreateFailed;
        errdefer _ = std.c.close(sock);

        const addr: sockaddr_in = .{
            .port = std.mem.nativeToBig(u16, local_port),
            .addr = 0, // INADDR_ANY
        };
        if (std.c.bind(sock, @ptrCast(&addr), @sizeOf(sockaddr_in)) != 0) {
            return SocketError.BindFailed;
        }

        self.fd = sock;
        self.local_port = local_port;

        // Resolve actual bound port if ephemeral
        if (local_port == 0) {
            var bound_addr: sockaddr_in = undefined;
            var bound_len: std.c.socklen_t = @sizeOf(sockaddr_in);
            if (std.c.getsockname(sock, @ptrCast(&bound_addr), &bound_len) == 0) {
                self.local_port = std.mem.bigToNative(u16, bound_addr.port);
            }
        }
    }

    /// Set the remote endpoint address. Call before send/receive.
    pub fn setRemote(self: *Self, host: []const u8, port: u16) SocketError!void {
        const parsed = std.Io.net.Ip4Address.parse(host, port) catch {
            return SocketError.ResolveFailed;
        };
        self.remote_addr = .{
            .port = std.mem.nativeToBig(u16, parsed.port),
            .addr = @bitCast(parsed.bytes),
        };
    }

    /// Send a datagram to the configured remote endpoint.
    pub fn send(self: *Self, data: []const u8) SocketError!void {
        const sock = self.fd orelse return SocketError.NotOpen;
        const rc = std.c.sendto(
            sock,
            data.ptr,
            data.len,
            0,
            @ptrCast(&self.remote_addr),
            @sizeOf(sockaddr_in),
        );
        if (rc < 0) return switch (std.c.errno(rc)) {
            .AGAIN => SocketError.WouldBlock,
            else => SocketError.SendFailed,
        };
    }

    /// Receive a datagram. Returns `null` if no data is available (non-blocking).
    pub fn receive(self: *Self, buf: []u8) SocketError!?usize {
        const sock = self.fd orelse return SocketError.NotOpen;
        const rc = std.c.recvfrom(sock, buf.ptr, buf.len, 0, null, null);
        if (rc < 0) return switch (std.c.errno(rc)) {
            .AGAIN => null,
            .CONNREFUSED => null, // ICMP port unreachable on some platforms
            else => SocketError.ReceiveFailed,
        };
        return @intCast(rc);
    }

    pub fn close(self: *Self) void {
        if (self.fd) |sock| {
            _ = std.c.close(sock);
            self.fd = null;
        }
    }

    pub fn deinit(self: *Self) void {
        self.close();
        self.* = .{
            .fd = null,
            .remote_addr = std.mem.zeroes(sockaddr_in),
            .local_port = 0,
        };
    }
};

test "open bind send receive roundtrip" {
    var server = MoshSocket.init();
    defer server.deinit();
    try server.open(0);
    const server_port = server.local_port;
    try std.testing.expect(server_port > 0);

    var client = MoshSocket.init();
    defer client.deinit();
    try client.open(0);

    try client.setRemote("127.0.0.1", server_port);
    try client.send("ping");

    var buf: [64]u8 = undefined;
    var received: ?usize = null;
    // Retry a few times because non-blocking recv may race with send.
    for (0..100) |_| {
        received = try server.receive(&buf);
        if (received != null) break;
        sleepNs(1_000_000); // 1ms
    }
    try std.testing.expect(received != null);
    try std.testing.expectEqualStrings("ping", buf[0..received.?]);
}

test "nonblocking receive returns null when empty" {
    var sock = MoshSocket.init();
    defer sock.deinit();
    try sock.open(0);

    var buf: [64]u8 = undefined;
    const received = try sock.receive(&buf);
    try std.testing.expect(received == null);
}

test "send without open fails" {
    var sock = MoshSocket.init();
    defer sock.deinit();
    try sock.setRemote("127.0.0.1", 12345);
    try std.testing.expectError(SocketError.NotOpen, sock.send("x"));
}

fn sleepNs(ns: u64) void {
    const ts: std.c.timespec = .{ .sec = 0, .nsec = @intCast(ns) };
    _ = std.c.nanosleep(&ts, null);
}
