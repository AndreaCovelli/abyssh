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

/// A numeric IPv4 or IPv6 socket address.
const RemoteAddress = union(enum) {
    v4: std.c.sockaddr.in,
    v6: std.c.sockaddr.in6,

    fn family(self: RemoteAddress) c_uint {
        return switch (self) {
            .v4 => std.c.AF.INET,
            .v6 => std.c.AF.INET6,
        };
    }

    fn ptr(self: *const RemoteAddress) *const std.c.sockaddr {
        return switch (self.*) {
            .v4 => |*addr| @ptrCast(addr),
            .v6 => |*addr| @ptrCast(addr),
        };
    }

    fn len(self: RemoteAddress) std.c.socklen_t {
        return switch (self) {
            .v4 => @sizeOf(std.c.sockaddr.in),
            .v6 => @sizeOf(std.c.sockaddr.in6),
        };
    }
};

/// Parses a numeric IPv4 or IPv6 address. An IPv6 address may end in a
/// numeric scope such as `fe80::1%3`, the interface index of a link-local peer.
fn parseRemote(host: []const u8, port: u16) SocketError!RemoteAddress {
    const scope_start = std.mem.findScalar(u8, host, '%');
    const address_text = host[0 .. scope_start orelse host.len];
    const parsed = std.Io.net.IpAddress.parse(address_text, port) catch {
        return SocketError.ResolveFailed;
    };
    switch (parsed) {
        .ip4 => |ip4| {
            if (scope_start != null) return SocketError.ResolveFailed;
            return .{ .v4 = .{
                .port = std.mem.nativeToBig(u16, ip4.port),
                .addr = @bitCast(ip4.bytes),
            } };
        },
        .ip6 => |ip6| {
            const scope_id: u32 = if (scope_start) |start|
                std.fmt.parseInt(u32, host[start + 1 ..], 10) catch return SocketError.ResolveFailed
            else
                0;
            return .{ .v6 = .{
                .port = std.mem.nativeToBig(u16, ip6.port),
                .flowinfo = 0,
                .addr = ip6.bytes,
                .scope_id = scope_id,
            } };
        },
    }
}

pub const MoshSocket = struct {
    const Self = @This();

    fd: ?std.c.fd_t = null,
    remote_addr: RemoteAddress = .{ .v4 = std.mem.zeroes(std.c.sockaddr.in) },
    local_port: u16 = 0,

    pub fn init() Self {
        return .{};
    }

    /// Open a non-blocking UDP socket of the remote address's family and
    /// optionally bind to `local_port`. Call `setRemote` first; without it the
    /// socket is IPv4. If `local_port` is 0 the OS assigns an ephemeral port.
    pub fn open(self: *Self, local_port: u16) SocketError!void {
        if (self.fd != null) return SocketError.AlreadyOpen;

        const family = self.remote_addr.family();
        const sock = std.c.socket(
            family,
            std.c.SOCK.DGRAM | std.c.SOCK.NONBLOCK | std.c.SOCK.CLOEXEC,
            0,
        );
        if (sock < 0) return SocketError.CreateFailed;
        errdefer _ = std.c.close(sock);

        // Bind to the wildcard address of the same family.
        var local: RemoteAddress = switch (self.remote_addr) {
            .v4 => .{
                .v4 = .{
                    .port = std.mem.nativeToBig(u16, local_port),
                    .addr = 0, // INADDR_ANY
                },
            },
            .v6 => .{
                .v6 = .{
                    .port = std.mem.nativeToBig(u16, local_port),
                    .flowinfo = 0,
                    .addr = @splat(0), // in6addr_any
                    .scope_id = 0,
                },
            },
        };
        if (std.c.bind(sock, local.ptr(), local.len()) != 0) {
            return SocketError.BindFailed;
        }

        self.fd = sock;
        self.local_port = local_port;

        // Resolve actual bound port if ephemeral
        if (local_port == 0) {
            var bound_len = local.len();
            const bound_ptr: *std.c.sockaddr = @constCast(local.ptr());
            if (std.c.getsockname(sock, bound_ptr, &bound_len) == 0) {
                self.local_port = switch (local) {
                    .v4 => |addr| std.mem.bigToNative(u16, addr.port),
                    .v6 => |addr| std.mem.bigToNative(u16, addr.port),
                };
            }
        }
    }

    /// Set the remote endpoint to a numeric IPv4 or IPv6 address. Call before
    /// `open`, which creates the socket in the address's family.
    pub fn setRemote(self: *Self, host: []const u8, port: u16) SocketError!void {
        const remote = try parseRemote(host, port);
        if (self.fd != null and remote.family() != self.remote_addr.family()) {
            return SocketError.AlreadyOpen;
        }
        self.remote_addr = remote;
    }

    /// Send a datagram to the configured remote endpoint.
    pub fn send(self: *Self, data: []const u8) SocketError!void {
        const sock = self.fd orelse return SocketError.NotOpen;
        const rc = std.c.sendto(
            sock,
            data.ptr,
            data.len,
            0,
            self.remote_addr.ptr(),
            self.remote_addr.len(),
        );
        if (rc < 0) {
            const err = std.c.errno(rc);
            if (err == .AGAIN) return SocketError.WouldBlock;
            return SocketError.SendFailed;
        }
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
        self.* = .{};
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

test "ipv6 open bind send receive roundtrip" {
    var server = MoshSocket.init();
    defer server.deinit();
    try server.setRemote("::1", 1);
    try server.open(0);
    const server_port = server.local_port;
    try std.testing.expect(server_port > 0);

    var client = MoshSocket.init();
    defer client.deinit();
    try client.setRemote("::1", server_port);
    try client.open(0);
    try client.send("ping6");

    var buf: [64]u8 = undefined;
    var received: ?usize = null;
    for (0..100) |_| {
        received = try server.receive(&buf);
        if (received != null) break;
        sleepNs(1_000_000); // 1ms
    }
    try std.testing.expect(received != null);
    try std.testing.expectEqualStrings("ping6", buf[0..received.?]);
}

test "parses scoped ipv6 and rejects scoped ipv4 and names" {
    const scoped = try parseRemote("fe80::1%3", 60001);
    try std.testing.expectEqual(@as(u32, 3), scoped.v6.scope_id);
    try std.testing.expectEqual(std.mem.nativeToBig(u16, 60001), scoped.v6.port);
    try std.testing.expectError(SocketError.ResolveFailed, parseRemote("10.0.0.1%3", 1));
    try std.testing.expectError(SocketError.ResolveFailed, parseRemote("fe80::1%wlan0", 1));
    try std.testing.expectError(SocketError.ResolveFailed, parseRemote("example.com", 1));
}

fn sleepNs(ns: u64) void {
    const ts: std.c.timespec = .{ .sec = 0, .nsec = @intCast(ns) };
    _ = std.c.nanosleep(&ts, null);
}
