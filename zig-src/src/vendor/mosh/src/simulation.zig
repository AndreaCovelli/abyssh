//! Deterministic simulation harness primitives for mosh reliability testing.

const std = @import("std");
const core = @import("core.zig");
const types = @import("types.zig");
const wire = @import("wire.zig");
const protolite = @import("protolite.zig");

pub const VirtualClock = struct {
    now_ms: u64 = 0,

    pub fn advance(self: *VirtualClock, delta_ms: u64) void {
        self.now_ms +|= delta_ms;
    }
};

pub const NetworkMutation = union(enum) {
    pass,
    drop,
    duplicate,
    delay_ms: u64,
};

pub const QueuedPacket = struct {
    available_at_ms: u64,
    payload: []u8,
};

pub const ScriptedLink = struct {
    const Self = @This();

    allocator: std.mem.Allocator,
    clock: *VirtualClock,
    script: std.ArrayList(NetworkMutation) = .empty,
    queue: std.ArrayList(QueuedPacket) = .empty,

    pub fn init(allocator: std.mem.Allocator, clock: *VirtualClock) Self {
        return .{ .allocator = allocator, .clock = clock };
    }

    pub fn deinit(self: *Self) void {
        for (self.queue.items) |packet| self.allocator.free(packet.payload);
        self.queue.deinit(self.allocator);
        self.script.deinit(self.allocator);
        self.* = undefined;
    }

    pub fn pushMutation(self: *Self, mutation: NetworkMutation) !void {
        try self.script.append(self.allocator, mutation);
    }

    pub fn send(self: *Self, payload: []const u8) !void {
        const mutation: NetworkMutation = if (self.script.items.len == 0)
            .pass
        else
            self.script.orderedRemove(0);

        switch (mutation) {
            .pass => try self.enqueue(payload, self.clock.now_ms),
            .drop => {},
            .duplicate => {
                try self.enqueue(payload, self.clock.now_ms);
                try self.enqueue(payload, self.clock.now_ms);
            },
            .delay_ms => |delay_ms| try self.enqueue(payload, self.clock.now_ms + delay_ms),
        }
    }

    pub fn receive(self: *Self, out_buf: []u8) !?usize {
        if (self.queue.items.len == 0) return null;

        if (self.queue.items[0].available_at_ms > self.clock.now_ms) {
            return null;
        }

        const packet = self.queue.orderedRemove(0);
        defer self.allocator.free(packet.payload);

        if (packet.payload.len > out_buf.len) return error.OutputBufferTooSmall;

        @memcpy(out_buf[0..packet.payload.len], packet.payload);
        return packet.payload.len;
    }

    fn enqueue(self: *Self, payload: []const u8, available_at_ms: u64) !void {
        const stored_payload = try self.allocator.dupe(u8, payload);
        const packet: QueuedPacket = .{ .available_at_ms = available_at_ms, .payload = stored_payload };
        try self.queue.append(self.allocator, packet);
        self.sortQueueByDeadline();
    }

    fn sortQueueByDeadline(self: *Self) void {
        std.mem.sort(QueuedPacket, self.queue.items, {}, struct {
            fn lessThan(_: void, lhs: QueuedPacket, rhs: QueuedPacket) bool {
                return lhs.available_at_ms < rhs.available_at_ms;
            }
        }.lessThan);
    }
};

pub const TraceStep = union(enum) {
    advance_ms: u64,
    send: []const u8,
    expect_receive: []const u8,
    expect_empty,
};

pub fn replayScriptedLinkTrace(
    allocator: std.mem.Allocator,
    clock: *VirtualClock,
    link: *ScriptedLink,
    steps: []const TraceStep,
) !void {
    _ = allocator;

    var scratch: [256]u8 = undefined;
    for (steps) |step| {
        switch (step) {
            .advance_ms => |delta_ms| clock.advance(delta_ms),
            .send => |payload| try link.send(payload),
            .expect_receive => |expected| {
                const got = try link.receive(&scratch);
                if (got == null) return error.ExpectedPacket;
                try std.testing.expectEqualStrings(expected, scratch[0..got.?]);
            },
            .expect_empty => {
                const got = try link.receive(&scratch);
                try std.testing.expect(got == null);
            },
        }
    }
}

test "scripted link drop and delayed delivery" {
    const allocator = std.testing.allocator;

    var clock: VirtualClock = .{};
    var link = ScriptedLink.init(allocator, &clock);
    defer link.deinit();

    try link.pushMutation(.drop);
    try link.pushMutation(.{ .delay_ms = 25 });

    try link.send("first");
    try link.send("second");

    var out: [32]u8 = undefined;
    const immediate = try link.receive(&out);
    try std.testing.expect(immediate == null);

    clock.advance(24);
    const still_waiting = try link.receive(&out);
    try std.testing.expect(still_waiting == null);

    clock.advance(1);
    const ready = try link.receive(&out);
    try std.testing.expectEqual(@as(usize, 6), ready.?);
    try std.testing.expectEqualStrings("second", out[0..ready.?]);
}

test "trace replay helper executes deterministic script" {
    const allocator = std.testing.allocator;

    var clock: VirtualClock = .{};
    var link = ScriptedLink.init(allocator, &clock);
    defer link.deinit();

    try link.pushMutation(.{ .delay_ms = 5 });
    try link.pushMutation(.duplicate);

    const trace: []const TraceStep = &.{
        .{ .send = "hello" },
        .expect_empty,
        .{ .advance_ms = 5 },
        .{ .expect_receive = "hello" },
        .{ .send = "dup" },
        .{ .expect_receive = "dup" },
        .{ .expect_receive = "dup" },
        .expect_empty,
    };

    try replayScriptedLinkTrace(allocator, &clock, &link, trace);
}

test "client + scripted link drop and retransmit convergence" {
    const allocator = std.testing.allocator;

    var clock: VirtualClock = .{};
    var to_server = ScriptedLink.init(allocator, &clock);
    defer to_server.deinit();
    var to_client = ScriptedLink.init(allocator, &clock);
    defer to_client.deinit();

    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };
    var client = try core.Client.init(allocator, endpoint, .{
        .use_network_crypto = false,
        .initial_rto_ms = 50,
        .max_rto_ms = 50,
        .max_retransmit_count = 5,
        .ack_delay_ms = 5,
    });
    defer client.deinit();
    try client.start(0);

    // Drop first packet, delay second by 20ms
    try to_server.pushMutation(.drop);
    try to_server.pushMutation(.{ .delay_ms = 20 });

    // Client sends input
    try client.sendInput("x", 0);

    // Pull initial datagram from client
    var client_buf: [1024]u8 = undefined;
    const dg1 = try client.pollNetworkDatagram(&client_buf);
    try std.testing.expect(dg1 != null);
    try to_server.send(client_buf[0..dg1.?]);

    // No packet should arrive yet (first dropped)
    var server_buf: [1024]u8 = undefined;
    const immediate = try to_server.receive(&server_buf);
    try std.testing.expect(immediate == null);

    // Advance to trigger retransmit
    clock.advance(50);
    try client.maintenanceTick(clock.now_ms);

    const dg2 = try client.pollNetworkDatagram(&client_buf);
    try std.testing.expect(dg2 != null);
    try to_server.send(client_buf[0..dg2.?]);

    // Still not ready because of 20ms delay
    const still_delayed = try to_server.receive(&server_buf);
    try std.testing.expect(still_delayed == null);

    // Advance past delay
    clock.advance(20);
    const received = try to_server.receive(&server_buf);
    try std.testing.expect(received != null);

    // Build a mock server response (ack-only with no diff, just to satisfy client)
    // Actually, let's build a real response with the received envelope echoed back
    const response_ack: protolite.TransportInstruction = .{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .ack_num = 1,
        .throwaway_num = 0,
        .diff = null,
        .chaff = &.{},
    };
    const ack_encoded = try response_ack.encode(allocator);
    defer allocator.free(ack_encoded);

    var envelope: wire.PacketEnvelope = .{ .timestamp = wire.timestamp16(clock.now_ms), .timestamp_reply = wire.timestamp16(0) };
    var envelope_bytes: [wire.PacketEnvelope.bytes]u8 = undefined;
    envelope.encode(&envelope_bytes);

    // Wrap the encoded TransportInstruction in a single-fragment header to match
    // the real mosh wire format expected by the (post-fix) inbound parser.
    const frag_header: wire.FragmentHeader = .{ .message_id = 1, .fragment_num = 0, .final = true };
    var frag_header_bytes: [wire.FragmentHeader.bytes]u8 = undefined;
    frag_header.encode(&frag_header_bytes);

    var response_payload = try allocator.alloc(
        u8,
        wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes + ack_encoded.len,
    );
    defer allocator.free(response_payload);
    @memcpy(response_payload[0..wire.PacketEnvelope.bytes], &envelope_bytes);
    @memcpy(
        response_payload[wire.PacketEnvelope.bytes .. wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes],
        &frag_header_bytes,
    );
    @memcpy(response_payload[wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes ..], ack_encoded);

    try to_client.send(response_payload);
    const client_received = try to_client.receive(&client_buf);
    try std.testing.expect(client_received != null);

    try client.processInboundPayload(client_buf[0..client_received.?], clock.now_ms);

    // Client should have acked and removed pending outbound
    try std.testing.expectEqual(@as(usize, 0), client.pending_outbound.count());
}
