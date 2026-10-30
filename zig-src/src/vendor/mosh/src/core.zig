//! Platform-agnostic mosh core with datagram wiring, zlib compression,
//! packet timestamps, RTT smoothing, network timeout, and circuit breaker.

const std = @import("std");
const compress = @import("compress.zig");
const crypto = @import("crypto.zig");
const protolite = @import("protolite.zig");
const types = @import("types.zig");
const wire = @import("wire.zig");
const socket = @import("socket.zig");

const log = std.log.scoped(.mosh);

/// The state number Mosh reserves for "end the session". A peer that receives
/// it acknowledges it and exits, which is how mosh-server learns to stop.
pub const shutdown_state_num: u64 = std.math.maxInt(u64);

/// How often to resend the shutdown request until the server acknowledges it.
const shutdown_resend_interval_ms: u64 = 200;

pub const OutputEvent = struct {
    event_type: types.OutputEventType,
    payload: []u8,
    cols: i32,
    rows: i32,
    echo_ack: u64,

    pub fn deinit(self: *OutputEvent, allocator: std.mem.Allocator) void {
        allocator.free(self.payload);
        self.* = .{
            .event_type = .none,
            .payload = &.{},
            .cols = 0,
            .rows = 0,
            .echo_ack = 0,
        };
    }
};

const PendingOutboundInstruction = struct {
    state_num: u64,
    payload: []u8,
    retry_count: u32,
    last_sent_at_ms: u64,
    next_retry_at_ms: u64,
    sent_timestamp: u16,

    fn deinit(self: *PendingOutboundInstruction, allocator: std.mem.Allocator) void {
        allocator.free(self.payload);
    }
};

const PendingInboundInstruction = struct {
    old_num: u64,
    new_num: u64,
    host_payload: []u8,

    fn deinit(self: *PendingInboundInstruction, allocator: std.mem.Allocator) void {
        allocator.free(self.host_payload);
    }
};

pub const InboundInstruction = struct {
    protocol_version: ?u32 = null,
    old_num: ?u64 = null,
    new_num: ?u64 = null,
    ack_num: ?u64 = null,
    throwaway_num: ?u64 = null,
    host_payload: []const u8 = &.{},
};

pub const PollOutputResult = struct {
    event_type: types.OutputEventType,
    written: usize,
    cols: i32,
    rows: i32,
    echo_ack: u64,
};

pub const Client = struct {
    const Self = @This();

    allocator: std.mem.Allocator,
    endpoint_host: []u8,
    endpoint_key: []u8,
    endpoint_port: u16,
    config: types.MoshClientConfig,

    state: types.SessionState = .idle,
    last_failure: types.FailureCode = .none,

    output_queue: std.ArrayList(OutputEvent) = .empty,
    network_datagrams: std.ArrayList([]u8) = .empty,
    pending_outbound: std.AutoHashMap(u64, PendingOutboundInstruction),
    pending_outbound_order: std.ArrayList(u64) = .empty,
    pending_inbound: std.ArrayList(PendingInboundInstruction) = .empty,
    applied_remote_state_nums: std.AutoHashMap(u64, void),

    last_state_num_sent: u64 = 0,
    latest_received_state_num: u64 = 0,
    last_ack_reported_num: u64 = 0,
    next_echo_ack: u64 = 1,

    current_rto_ms: u32,
    transport_cipher: ?crypto.OCBTransportCipher = null,
    next_outgoing_sequence: u64 = 0,
    last_outbound_at_ms: u64 = 0,
    last_ack_sent_at_ms: u64 = 0,
    ack_dirty_at_ms: ?u64 = null,

    // RTT smoothing (RFC6298-like). srtt_ms == -1 means no sample yet.
    srtt_ms: i64 = -1,
    rttvar_ms: u32 = 0,
    last_sent_timestamp: u16 = 0,
    last_received_timestamp: u16 = std.math.maxInt(u16),

    // Network timeout tracking
    last_received_at_ms: u64 = 0,

    // Circuit breaker
    consecutive_recv_failures: u32 = 0,

    // Shutdown handshake: set once `startShutdown` sends the reserved final state.
    shutdown_started_at_ms: ?u64 = null,
    shutdown_last_sent_at_ms: u64 = 0,
    shutdown_acknowledged: bool = false,
    // Set when the server sends the reserved final state because its shell exited.
    server_shutdown_received: bool = false,

    // Fragment assembly
    inbound_fragments: std.AutoHashMap(u64, std.ArrayList([]const u8)),
    next_message_id: u64 = 1,

    // Real UDP socket (optional; simulation can skip it)
    udp_socket: socket.MoshSocket = .{},

    pub fn init(
        allocator: std.mem.Allocator,
        endpoint: types.MoshEndpoint,
        config: types.MoshClientConfig,
    ) !Self {
        if (endpoint.host.len == 0) return error.InvalidEndpoint;
        if (endpoint.port == 0) return error.InvalidEndpoint;
        if (endpoint.key_base64_22.len != 22) return error.InvalidEndpoint;

        const host_copy = try allocator.dupe(u8, endpoint.host);
        errdefer allocator.free(host_copy);

        const key_copy = try allocator.dupe(u8, endpoint.key_base64_22);
        errdefer allocator.free(key_copy);

        const normalized_config = config.normalized();
        var transport_cipher: ?crypto.OCBTransportCipher = null;
        if (normalized_config.use_network_crypto) {
            const key = try crypto.MoshBase64Key.parsePrintable(endpoint.key_base64_22);
            transport_cipher = .{ .key = key.raw };
        }

        return .{
            .allocator = allocator,
            .endpoint_host = host_copy,
            .endpoint_key = key_copy,
            .endpoint_port = endpoint.port,
            .config = normalized_config,
            .pending_outbound = std.AutoHashMap(u64, PendingOutboundInstruction).init(allocator),
            .applied_remote_state_nums = std.AutoHashMap(u64, void).init(allocator),
            .inbound_fragments = std.AutoHashMap(u64, std.ArrayList([]const u8)).init(allocator),
            .current_rto_ms = normalized_config.initial_rto_ms,
            .transport_cipher = transport_cipher,
        };
    }

    pub fn deinit(self: *Self) void {
        for (self.output_queue.items) |*event| event.deinit(self.allocator);
        self.output_queue.deinit(self.allocator);

        for (self.network_datagrams.items) |datagram| self.allocator.free(datagram);
        self.network_datagrams.deinit(self.allocator);

        var pending_iter = self.pending_outbound.valueIterator();
        while (pending_iter.next()) |pending| pending.deinit(self.allocator);
        self.pending_outbound.deinit();
        self.pending_outbound_order.deinit(self.allocator);

        for (self.pending_inbound.items) |*pending| pending.deinit(self.allocator);
        self.pending_inbound.deinit(self.allocator);
        self.applied_remote_state_nums.deinit();

        var fragment_iter = self.inbound_fragments.valueIterator();
        while (fragment_iter.next()) |list| {
            for (list.items) |f| self.allocator.free(@constCast(f));
            list.deinit(self.allocator);
        }
        self.inbound_fragments.deinit();

        self.udp_socket.deinit();
        self.allocator.free(self.endpoint_host);
        self.allocator.free(self.endpoint_key);
        self.* = undefined;
    }

    pub fn start(self: *Self, now_ms: u64) !void {
        switch (self.state) {
            .running, .starting => return error.AlreadyStarted,
            .failed => return error.NotStarted,
            else => {},
        }
        log.info("start: host={s} port={d} local_port={?d} crypto={}", .{ self.endpoint_host, self.endpoint_port, self.config.local_port, self.config.use_network_crypto });

        self.state = .starting;
        self.last_failure = .none;
        self.last_ack_sent_at_ms = now_ms;
        self.last_outbound_at_ms = now_ms;
        self.last_received_at_ms = now_ms;
        self.ack_dirty_at_ms = null;
        self.current_rto_ms = self.config.initial_rto_ms;
        self.srtt_ms = -1;
        self.rttvar_ms = 0;
        self.last_received_timestamp = std.math.maxInt(u16);
        self.consecutive_recv_failures = 0;

        // The remote address picks the socket's family, so set it first.
        self.udp_socket.setRemote(self.endpoint_host, self.endpoint_port) catch |err| {
            log.err("udp_socket.setRemote failed: {s}", .{@errorName(err)});
            self.failSession(.transport_failure);
            return err;
        };
        self.udp_socket.open(self.config.local_port orelse 0) catch |err| {
            log.err("udp_socket.open failed: {s}", .{@errorName(err)});
            self.failSession(.transport_failure);
            return err;
        };

        try self.enqueueDiagnostic("mosh core started");
        self.state = .running;
        try self.enqueueStringEvent(.state_changed, "running");
        log.info("start: running", .{});
    }

    pub fn stop(self: *Self) !void {
        if (self.state == .stopped or self.state == .idle) {
            self.state = .stopped;
            return;
        }
        if (self.state == .running) self.state = .stopping;

        self.udp_socket.close();
        try self.enqueueStringEvent(.state_changed, "stopped");
        self.state = .stopped;
    }

    /// Starts Mosh's shutdown handshake: tells the server to end the session,
    /// so mosh-server and its shell exit instead of waiting out their network
    /// timeout. Keep calling `maintenanceTick` and `pumpNetwork` until
    /// `isShutdownAcknowledged` or a deadline; input and resizes are refused.
    pub fn startShutdown(self: *Self, now_ms: u64) !void {
        if (self.state != .running) return error.NotStarted;
        if (self.shutdown_started_at_ms != null) return;
        log.info("shutdown: requesting server exit", .{});
        self.shutdown_started_at_ms = now_ms;
        try self.sendShutdown(now_ms);
    }

    pub fn isShutdownAcknowledged(self: *const Self) bool {
        return self.shutdown_acknowledged;
    }

    /// Whether the server ended the session, as when its shell exits. The
    /// client is stopped by then.
    pub fn isServerShutdown(self: *const Self) bool {
        return self.server_shutdown_received;
    }

    pub fn sendInput(self: *Self, bytes: []const u8, now_ms: u64) !void {
        if (self.state != .running) {
            log.warn("sendInput: not running (state={s})", .{@tagName(self.state)});
            return error.NotStarted;
        }
        if (self.shutdown_started_at_ms != null) return error.ShuttingDown;
        const user_message = try protolite.encodeUserMessage(self.allocator, &.{.{ .keystroke = bytes }});
        defer self.allocator.free(user_message);

        try self.enqueueOutboundState(user_message, now_ms);
    }

    pub fn resize(self: *Self, cols: i32, rows: i32, now_ms: u64) !void {
        if (self.state != .running) {
            log.warn("resize: not running (state={s})", .{@tagName(self.state)});
            return error.NotStarted;
        }
        if (self.shutdown_started_at_ms != null) return error.ShuttingDown;
        if (cols <= 0 or rows <= 0) return error.InvalidResize;
        log.info("resize: {d}x{d}", .{ cols, rows });

        const user_message = try protolite.encodeUserMessage(self.allocator, &.{.{ .resize = .{ .width = cols, .height = rows } }});
        defer self.allocator.free(user_message);

        try self.enqueueOutboundState(user_message, now_ms);
        try self.enqueueEvent(.{
            .event_type = .resize,
            .payload = try self.allocator.dupe(u8, &.{}),
            .cols = cols,
            .rows = rows,
            .echo_ack = 0,
        });
    }

    pub fn processInboundPayload(self: *Self, payload: []const u8, now_ms: u64) !void {
        self.consecutive_recv_failures = 0;
        self.last_received_at_ms = now_ms;
        var plaintext_to_free: ?[]u8 = null;
        defer if (plaintext_to_free) |p| self.allocator.free(p);

        const plaintext: []const u8 = blk: {
            if (self.transport_cipher) |transport_cipher| {
                const opened = transport_cipher.open(self.allocator, payload) catch |err| {
                    log.err("crypto open failed: {s}", .{@errorName(err)});
                    self.consecutive_recv_failures += 1;
                    if (self.consecutive_recv_failures >= 8) {
                        self.failSession(.circuit_breaker_tripped);
                    }
                    return err;
                };
                plaintext_to_free = opened.plaintext;
                const split = wire.splitDirectionalSequence(opened.directional_sequence);
                if (split.direction != .to_client) {
                    log.err("wrong direction: {s}", .{@tagName(split.direction)});
                    self.consecutive_recv_failures += 1;
                    return error.ProtocolViolation;
                }
                break :blk opened.plaintext;
            }
            break :blk payload;
        };

        if (plaintext.len < wire.PacketEnvelope.bytes) {
            log.err("plaintext too short: {d} < {d}", .{ plaintext.len, wire.PacketEnvelope.bytes });
            return error.ProtocolViolation;
        }
        const envelope = wire.PacketEnvelope.decode(plaintext[0..wire.PacketEnvelope.bytes]);
        const protobuf_payload = plaintext[wire.PacketEnvelope.bytes..];
        self.last_received_timestamp = envelope.timestamp;

        // Update RTT from timestamp_reply
        if (envelope.timestamp_reply != std.math.maxInt(u16)) {
            const now16 = wire.timestamp16(now_ms);
            const rtt = wire.timestampDiff(now16, envelope.timestamp_reply);
            self.updateRto(rtt);
        }

        // Real mosh-server (and the swift reference) ALWAYS wraps payloads
        // in a fragment header, even for single-fragment messages. Treat the
        // payload as a fragment first; fall back to raw protobuf only if the
        // bytes don't look like a fragment header (e.g. older simulation
        // peers that emit raw TransportInstructions).
        if (protobuf_payload.len >= wire.fragment_header_bytes) {
            self.processInboundFragment(protobuf_payload, now_ms, envelope) catch |err| {
                log.info("fragment path failed ({s}), falling back to raw protobuf", .{@errorName(err)});
                try self.processInboundProtobuf(protobuf_payload, now_ms, envelope);
                return;
            };
            return;
        }

        try self.processInboundProtobuf(protobuf_payload, now_ms, envelope);
    }

    fn processInboundProtobuf(
        self: *Self,
        protobuf_payload: []const u8,
        now_ms: u64,
        envelope: wire.PacketEnvelope,
    ) !void {
        _ = envelope;
        // Try decompressing the entire payload first (real mosh-server sends
        // zlib-compressed TransportInstructions), then fall back to raw.
        var decompressed_owned: ?[]u8 = null;
        defer if (decompressed_owned) |d| self.allocator.free(d);

        const instruction_bytes = blk: {
            if (compress.decompressAuto(self.allocator, protobuf_payload)) |result| {
                decompressed_owned = result.data;
                break :blk result.data;
            } else |_| {
                break :blk protobuf_payload;
            }
        };

        const decoded = protolite.TransportInstruction.decode(instruction_bytes) catch |err| {
            log.err("TransportInstruction decode failed: {s} bytes_len={d}", .{ @errorName(err), instruction_bytes.len });
            self.consecutive_recv_failures += 1;
            if (self.consecutive_recv_failures >= 8) {
                self.failSession(.circuit_breaker_tripped);
            }
            return err;
        };
        // The diff field is raw HostMessage bytes (uncompressed).
        const host_payload = decoded.diff orelse &[_]u8{};

        try self.processInboundInstruction(.{
            .protocol_version = decoded.protocol_version,
            .old_num = decoded.old_num,
            .new_num = decoded.new_num,
            .ack_num = decoded.ack_num,
            .throwaway_num = decoded.throwaway_num,
            .host_payload = host_payload,
        }, now_ms);
    }

    fn processInboundFragment(
        self: *Self,
        payload: []const u8,
        now_ms: u64,
        envelope: wire.PacketEnvelope,
    ) !void {
        const header = wire.FragmentHeader.decode(payload[0..wire.FragmentHeader.bytes]);
        const chunk = payload[wire.FragmentHeader.bytes..];

        // Single-fragment optimization: fragment_num=0 && final means the whole
        // message fits in one fragment. Strip header and process directly.
        if (header.fragment_num == 0 and header.final) {
            try self.processInboundProtobuf(chunk, now_ms, envelope);
            return;
        }

        // Multi-fragment accumulation
        const entry = try self.inbound_fragments.getOrPut(header.message_id);
        if (!entry.found_existing) {
            entry.value_ptr.* = std.ArrayList([]const u8).empty;
        }

        // Duplicate detection
        for (entry.value_ptr.items) |existing| {
            const existing_header = wire.FragmentHeader.decode(existing[0..wire.FragmentHeader.bytes]);
            if (existing_header.fragment_num == header.fragment_num) return;
        }

        const stored = try self.allocator.dupe(u8, payload);
        try entry.value_ptr.append(self.allocator, stored);

        var out_message_id: u64 = undefined;
        const assembled = wire.reassembleFragments(self.allocator, entry.value_ptr, &out_message_id) catch {
            // Missing fragments or malformed data; wait for more.
            return;
        };

        if (assembled) |a| {
            defer self.allocator.free(a);
            // Free all stored fragments and remove entry
            for (entry.value_ptr.items) |f| {
                self.allocator.free(@constCast(f));
            }
            entry.value_ptr.deinit(self.allocator);
            _ = self.inbound_fragments.remove(header.message_id);
            try self.processInboundProtobuf(a, now_ms, envelope);
        }
    }

    pub fn processInboundInstruction(self: *Self, instruction: InboundInstruction, now_ms: u64) !void {
        if (self.state != .running) {
            log.warn("processInboundInstruction: not running (state={s})", .{@tagName(self.state)});
            return error.NotStarted;
        }
        if (instruction.protocol_version) |protocol_version| {
            if (protocol_version != types.PROTOCOL_VERSION) {
                log.err("protocol version mismatch: got {d} expected {d}", .{ protocol_version, types.PROTOCOL_VERSION });
                self.failSession(.protocol_violation);
                return error.ProtocolViolation;
            }
        }

        if (instruction.ack_num) |ack_num| {
            self.acknowledgePendingOutbound(ack_num);
            if (ack_num == shutdown_state_num and self.shutdown_started_at_ms != null and !self.shutdown_acknowledged) {
                self.shutdown_acknowledged = true;
                log.info("shutdown: acknowledged by server", .{});
                try self.enqueueStringEvent(.state_changed, "shutdown_acknowledged");
            }
        }
        if (instruction.throwaway_num) |throwaway_num| self.pruneAppliedRemoteStates(throwaway_num);

        if (instruction.new_num) |new_num| {
            if (self.applied_remote_state_nums.contains(new_num)) {
                return;
            }

            if (instruction.old_num) |old_num| {
                if (old_num < self.latest_received_state_num) {
                    return;
                }
                if (old_num > self.latest_received_state_num) {
                    try self.bufferFutureInbound(old_num, new_num, instruction.host_payload);
                    return;
                }
            }

            try self.applyInboundState(new_num, instruction.host_payload, now_ms);
            try self.applyBufferedInbound(now_ms);
            return;
        }

        try self.enqueueHostPayload(instruction.host_payload);
    }

    pub fn maintenanceTick(self: *Self, now_ms: u64) !void {
        if (self.state != .running) return;

        // Network timeout check
        if (now_ms >= self.last_received_at_ms and
            now_ms - self.last_received_at_ms >= self.config.network_timeout_ms)
        {
            self.failSession(.timeout);
            return error.NetworkTimeout;
        }

        var index: usize = 0;
        while (index < self.pending_outbound_order.items.len) : (index += 1) {
            const state_num = self.pending_outbound_order.items[index];
            var pending = self.pending_outbound.get(state_num) orelse continue;

            if (now_ms < pending.next_retry_at_ms) continue;

            if (pending.retry_count >= self.config.max_retransmit_count) {
                self.failSession(.retry_limit_exceeded);
                return error.RetryLimitExceeded;
            }

            pending.retry_count +|= 1;
            pending.last_sent_at_ms = now_ms;
            pending.next_retry_at_ms = now_ms + self.current_rto_ms;
            try self.pending_outbound.put(state_num, pending);

            // Re-fragment + re-encrypt with fresh sequence numbers, matching
            // the swift reference. `pending.payload` holds the compressed
            // transport-instruction bytes (pre-fragment, pre-envelope).
            try self.transmitPayload(pending.payload, now_ms);

            var line = std.ArrayList(u8).empty;
            defer line.deinit(self.allocator);
            try line.print(self.allocator, "retransmit state={d} retry={d}", .{ state_num, pending.retry_count });
            try self.enqueueEvent(.{
                .event_type = .diagnostic,
                .payload = try self.allocator.dupe(u8, line.items),
                .cols = 0,
                .rows = 0,
                .echo_ack = 0,
            });
        }

        if (self.latest_received_state_num > self.last_ack_reported_num) {
            if (self.ack_dirty_at_ms) |ack_dirty_at_ms| {
                const delay_elapsed = now_ms >= ack_dirty_at_ms and
                    now_ms - ack_dirty_at_ms >= self.config.ack_delay_ms;
                const interval_elapsed = now_ms >= self.last_ack_sent_at_ms and
                    now_ms - self.last_ack_sent_at_ms >= self.config.ack_interval_ms;

                if (delay_elapsed or interval_elapsed) {
                    try self.sendAckOnly(now_ms);
                }
            } else {
                const interval_elapsed = now_ms >= self.last_ack_sent_at_ms and
                    now_ms - self.last_ack_sent_at_ms >= self.config.ack_interval_ms;
                if (interval_elapsed) {
                    try self.sendAckOnly(now_ms);
                }
            }
        }

        if (now_ms >= self.last_outbound_at_ms and
            now_ms - self.last_outbound_at_ms >= self.config.heartbeat_interval_ms)
        {
            try self.sendAckOnly(now_ms);
            self.last_outbound_at_ms = now_ms;
        }

        if (self.shutdown_started_at_ms != null and !self.shutdown_acknowledged and
            now_ms >= self.shutdown_last_sent_at_ms and
            now_ms - self.shutdown_last_sent_at_ms >= shutdown_resend_interval_ms)
        {
            try self.sendShutdown(now_ms);
        }
    }

    pub fn pollNetworkDatagram(self: *Self, out_buf: []u8) !?usize {
        if (self.network_datagrams.items.len == 0) return null;

        const datagram = self.network_datagrams.orderedRemove(0);
        defer self.allocator.free(datagram);

        if (datagram.len > out_buf.len) {
            try self.network_datagrams.insert(self.allocator, 0, try self.allocator.dupe(u8, datagram));
            return error.OutputBufferTooSmall;
        }

        @memcpy(out_buf[0..datagram.len], datagram);
        return datagram.len;
    }

    /// Drain queued outbound datagrams to the real UDP socket and read any
    /// inbound datagrams into the core state machine. Call this regularly
    /// from the app's run loop.
    pub fn pumpNetwork(self: *Self, now_ms: u64) !void {
        if (self.state != .running) {
            log.warn("pumpNetwork: not running (state={s})", .{@tagName(self.state)});
            return;
        }
        try self.sendQueuedDatagrams();

        // Receive inbound datagrams (best-effort, non-blocking)
        var recv_buf: [2048]u8 = undefined;
        while (true) {
            const received = self.udp_socket.receive(&recv_buf) catch {
                self.consecutive_recv_failures += 1;
                if (self.consecutive_recv_failures >= 8) {
                    self.failSession(.circuit_breaker_tripped);
                    return error.CircuitBreakerTripped;
                }
                break;
            };
            const payload = received orelse break;
            self.processInboundPayload(recv_buf[0..payload], now_ms) catch |err| {
                log.err("processInboundPayload error: {s}", .{@errorName(err)});
                self.consecutive_recv_failures += 1;
                if (self.consecutive_recv_failures >= 8) {
                    self.failSession(.circuit_breaker_tripped);
                    return error.CircuitBreakerTripped;
                }
                // Don't propagate decode errors; just keep receiving
                if (err == error.RetryLimitExceeded or err == error.NetworkTimeout) return err;
            };
        }

        if (self.server_shutdown_received) try self.finishServerShutdown(now_ms);
    }

    /// Acknowledges the server's shutdown and stops. Like Mosh's own client,
    /// this sends the ack once; a server that misses it gives up on its own.
    fn finishServerShutdown(self: *Self, now_ms: u64) !void {
        try self.sendAckOnly(now_ms);
        try self.sendQueuedDatagrams();
        self.state = .stopped;
        log.info("server shutdown: acknowledged, session stopped", .{});
        try self.enqueueStringEvent(.state_changed, "server_shutdown");
    }

    fn sendQueuedDatagrams(self: *Self) !void {
        while (self.network_datagrams.items.len > 0) {
            const datagram = self.network_datagrams.orderedRemove(0);
            defer self.allocator.free(datagram);
            self.udp_socket.send(datagram) catch |err| {
                // On WouldBlock re-queue at front to retry later
                if (err == socket.SocketError.WouldBlock) {
                    try self.network_datagrams.insert(self.allocator, 0, try self.allocator.dupe(u8, datagram));
                    break;
                }
                // Losing the network is what Mosh outlasts: drop the datagram and
                // let retransmission and heartbeats resend once a route is back.
                // The network timeout, not the circuit breaker, bounds the outage.
                if (err == socket.SocketError.NetworkUnavailable) {
                    log.debug("send dropped: network unavailable", .{});
                    continue;
                }
                log.err("send error: {s}", .{@errorName(err)});
                // Other errors count against circuit breaker
                self.consecutive_recv_failures += 1;
                if (self.consecutive_recv_failures >= 8) {
                    self.failSession(.circuit_breaker_tripped);
                    return error.CircuitBreakerTripped;
                }
            };
        }
    }

    pub fn pollOutput(self: *Self, out_buf: []u8) !PollOutputResult {
        if (self.output_queue.items.len == 0) {
            return .{
                .event_type = .none,
                .written = 0,
                .cols = 0,
                .rows = 0,
                .echo_ack = 0,
            };
        }

        var event = self.output_queue.orderedRemove(0);
        defer event.deinit(self.allocator);
        if (event.payload.len > out_buf.len) {
            log.warn("pollOutput: buffer too small ({d} < {d}), re-queueing", .{ out_buf.len, event.payload.len });
            const replacement: OutputEvent = .{
                .event_type = event.event_type,
                .payload = try self.allocator.dupe(u8, event.payload),
                .cols = event.cols,
                .rows = event.rows,
                .echo_ack = event.echo_ack,
            };
            try self.output_queue.insert(self.allocator, 0, replacement);
            return error.OutputBufferTooSmall;
        }

        if (event.payload.len > 0) {
            @memcpy(out_buf[0..event.payload.len], event.payload);
        }

        // NOTE: Do NOT acknowledge pending outbound here. echo_ack events from
        // sendAckOnly carry the *server's* state number (latest_received_state_num),
        // which is unrelated to the client's pending state numbers. Mixing the two
        // counter spaces caused phantom acknowledgements that masked real protocol
        // bugs. Real acks of our outbound states arrive via processInboundInstruction.

        return .{
            .event_type = event.event_type,
            .written = event.payload.len,
            .cols = event.cols,
            .rows = event.rows,
            .echo_ack = event.echo_ack,
        };
    }

    pub fn localPort(self: *const Self) ?u16 {
        if (self.udp_socket.fd == null) return null;
        return self.udp_socket.local_port;
    }

    pub fn runtimeState(self: *const Self) types.RuntimeState {
        const pending_host_ops: u32 = @intCast(@min(self.output_queue.items.len, std.math.maxInt(u32)));
        const pending_outbound_count: u32 = @intCast(@min(self.pending_outbound.count(), std.math.maxInt(u32)));
        return types.toRuntimeState(
            self.state,
            self.last_failure,
            self.last_state_num_sent,
            self.latest_received_state_num,
            pending_outbound_count,
            pending_host_ops,
            self.current_rto_ms,
        );
    }

    fn buildDatagram(self: *Self, fragment_bytes: []const u8, now_ms: u64) ![]u8 {
        const timestamp = wire.timestamp16(now_ms);
        const envelope: wire.PacketEnvelope = .{
            .timestamp = timestamp,
            .timestamp_reply = self.last_received_timestamp,
        };
        var envelope_bytes: [wire.PacketEnvelope.bytes]u8 = undefined;
        envelope.encode(&envelope_bytes);

        const full_plaintext = try self.allocator.alloc(u8, wire.PacketEnvelope.bytes + fragment_bytes.len);
        errdefer self.allocator.free(full_plaintext);
        @memcpy(full_plaintext[0..wire.PacketEnvelope.bytes], &envelope_bytes);
        @memcpy(full_plaintext[wire.PacketEnvelope.bytes..], fragment_bytes);

        const datagram = blk: {
            if (self.transport_cipher) |transport_cipher| {
                defer self.allocator.free(full_plaintext);
                const directional_sequence = wire.directionalSequence(self.next_outgoing_sequence, .to_server);
                self.next_outgoing_sequence +|= 1;
                break :blk try transport_cipher.seal(self.allocator, directional_sequence, full_plaintext);
            }
            break :blk full_plaintext;
        };
        return datagram;
    }

    /// Fragment the encoded+compressed transport instruction, wrap each
    /// fragment in the mosh wire-format header, prepend the packet envelope,
    /// seal (if crypto is enabled), and enqueue each fragment as its own
    /// outbound datagram. Mirrors the reference implementation in
    /// `swift-mosh/Sources/MoshTransport/UDPTransport.swift::sendPayload`.
    fn transmitPayload(self: *Self, payload: []const u8, now_ms: u64) !void {
        const message_id = self.next_message_id;
        self.next_message_id +|= 1;

        // Mosh fragments at MTU minus the packet header (8 directional sequence + 4 envelope).
        // Each fragment is then `fragment_header(10) + chunk`.
        const max_fragment_total: usize = if (self.config.mtu > wire.packet_header_bytes)
            self.config.mtu - wire.packet_header_bytes
        else
            wire.fragment_header_bytes + 1;
        const max_chunk_size: usize = if (max_fragment_total > wire.fragment_header_bytes)
            max_fragment_total - wire.fragment_header_bytes
        else
            1;

        // Empty payload: send a single empty fragment so the peer still observes
        // the wire format. (Real mosh always wraps a fragment header.)
        if (payload.len == 0) {
            const empty_header: wire.FragmentHeader = .{
                .message_id = message_id,
                .fragment_num = 0,
                .final = true,
            };
            var header_bytes: [wire.FragmentHeader.bytes]u8 = undefined;
            empty_header.encode(&header_bytes);
            const datagram = try self.buildDatagram(&header_bytes, now_ms);
            errdefer self.allocator.free(datagram);
            try self.network_datagrams.append(self.allocator, datagram);
            self.last_outbound_at_ms = now_ms;
            self.last_sent_timestamp = wire.timestamp16(now_ms);
            return;
        }

        var fragments = try wire.fragmentPayload(self.allocator, message_id, payload, max_chunk_size);
        defer {
            for (fragments.items) |f| self.allocator.free(f);
            fragments.deinit(self.allocator);
        }

        for (fragments.items) |fragment_bytes| {
            const datagram = try self.buildDatagram(fragment_bytes, now_ms);
            errdefer self.allocator.free(datagram);
            try self.network_datagrams.append(self.allocator, datagram);
        }
        self.last_outbound_at_ms = now_ms;
        self.last_sent_timestamp = wire.timestamp16(now_ms);
    }

    fn pushDatagram(self: *Self, datagram: []const u8) !void {
        const copy = try self.allocator.dupe(u8, datagram);
        errdefer self.allocator.free(copy);
        try self.network_datagrams.append(self.allocator, copy);
    }

    fn enqueueOutboundState(self: *Self, diff_payload: []const u8, now_ms: u64) !void {
        const old_state_num = self.last_state_num_sent;
        self.last_state_num_sent +|= 1;
        const state_num = self.last_state_num_sent;
        const instruction: protolite.TransportInstruction = .{
            .protocol_version = types.PROTOCOL_VERSION,
            .old_num = old_state_num,
            .new_num = state_num,
            .ack_num = self.latest_received_state_num,
            .throwaway_num = self.computeThrowawayNum(),
            .diff = diff_payload,
            .chaff = &.{},
        };
        const encoded = try instruction.encode(self.allocator);
        defer self.allocator.free(encoded);

        const compressed = try self.encodeAndCompress(encoded);
        // Persist the compressed transport-instruction bytes so retransmissions
        // can build fresh fragments with new sequence numbers (matching the
        // Swift reference's processRetransmitQueue flow).
        errdefer self.allocator.free(compressed);

        try self.transmitPayload(compressed, now_ms);

        const timestamp = wire.timestamp16(now_ms);

        // Track for reliability. We store the compressed instruction bytes,
        // not the wire datagram, so retransmissions reuse fresh fragmentation
        // and crypto sequence numbers.
        const pending: PendingOutboundInstruction = .{
            .state_num = state_num,
            .payload = compressed,
            .retry_count = 0,
            .last_sent_at_ms = now_ms,
            .next_retry_at_ms = now_ms + self.current_rto_ms,
            .sent_timestamp = timestamp,
        };
        try self.pending_outbound.put(state_num, pending);
        try self.pending_outbound_order.append(self.allocator, state_num);
    }

    fn acknowledgePendingOutbound(self: *Self, ack_num: u64) void {
        var read_index: usize = 0;
        var write_index: usize = 0;

        while (read_index < self.pending_outbound_order.items.len) : (read_index += 1) {
            const state_num = self.pending_outbound_order.items[read_index];
            if (state_num <= ack_num) {
                if (self.pending_outbound.fetchRemove(state_num)) |entry| {
                    var pending = entry.value;
                    pending.deinit(self.allocator);
                }
                continue;
            }

            self.pending_outbound_order.items[write_index] = state_num;
            write_index += 1;
        }

        self.pending_outbound_order.items.len = write_index;
    }

    fn bufferFutureInbound(self: *Self, old_num: u64, new_num: u64, host_payload: []const u8) !void {
        if (self.pending_inbound.items.len >= self.config.max_receive_states) {
            var dropped = self.pending_inbound.orderedRemove(0);
            dropped.deinit(self.allocator);
        }

        for (self.pending_inbound.items) |pending| {
            if (pending.new_num == new_num) return;
        }

        try self.pending_inbound.append(self.allocator, .{
            .old_num = old_num,
            .new_num = new_num,
            .host_payload = try self.allocator.dupe(u8, host_payload),
        });
    }

    fn applyInboundState(self: *Self, new_num: u64, host_payload: []const u8, now_ms: u64) !void {
        self.latest_received_state_num = @max(self.latest_received_state_num, new_num);
        try self.applied_remote_state_nums.put(new_num, {});
        if (new_num == shutdown_state_num and !self.server_shutdown_received) {
            log.info("server shutdown: received", .{});
            self.server_shutdown_received = true;
        }

        if (self.applied_remote_state_nums.count() > 4096) {
            const floor: u64 = if (self.latest_received_state_num > 2048)
                self.latest_received_state_num - 2048
            else
                0;
            try self.pruneAppliedBelow(floor);
        }

        self.ack_dirty_at_ms = now_ms;
        try self.enqueueHostPayload(host_payload);
    }

    fn applyBufferedInbound(self: *Self, now_ms: u64) !void {
        while (true) {
            var match_index: ?usize = null;
            for (self.pending_inbound.items, 0..) |pending, index| {
                if (pending.old_num == self.latest_received_state_num) {
                    match_index = index;
                    break;
                }
            }

            const index = match_index orelse return;
            var pending = self.pending_inbound.orderedRemove(index);
            defer pending.deinit(self.allocator);

            if (self.applied_remote_state_nums.contains(pending.new_num)) continue;
            try self.applyInboundState(pending.new_num, pending.host_payload, now_ms);
        }
    }

    fn enqueueHostPayload(self: *Self, host_payload: []const u8) !void {
        if (host_payload.len == 0) return;

        if (protolite.decodeHostMessage(self.allocator, host_payload)) |host_instructions| {
            defer protolite.freeHostInstructions(self.allocator, host_instructions);
            for (host_instructions) |host_instruction| {
                switch (host_instruction) {
                    .host_bytes => |bytes| {
                        try self.enqueueEvent(.{
                            .event_type = .host_bytes,
                            .payload = try self.allocator.dupe(u8, bytes),
                            .cols = 0,
                            .rows = 0,
                            .echo_ack = 0,
                        });
                    },
                    .resize => |size| {
                        try self.enqueueEvent(.{
                            .event_type = .resize,
                            .payload = try self.allocator.dupe(u8, &.{}),
                            .cols = size.width,
                            .rows = size.height,
                            .echo_ack = 0,
                        });
                    },
                    .echo_ack => {
                        try self.enqueueEvent(.{
                            .event_type = .echo_ack,
                            .payload = try self.allocator.dupe(u8, &.{}),
                            .cols = 0,
                            .rows = 0,
                            .echo_ack = 0,
                        });
                    },
                }
            }
        } else |_| {
            try self.enqueueEvent(.{
                .event_type = .host_bytes,
                .payload = try self.allocator.dupe(u8, host_payload),
                .cols = 0,
                .rows = 0,
                .echo_ack = 0,
            });
        }
    }

    fn pruneAppliedRemoteStates(self: *Self, throwaway_num: u64) void {
        if (throwaway_num == 0) return;
        self.pruneAppliedBelow(throwaway_num) catch {};
    }

    fn pruneAppliedBelow(self: *Self, floor: u64) !void {
        var retained = std.AutoHashMap(u64, void).init(self.allocator);
        errdefer retained.deinit();

        var iter = self.applied_remote_state_nums.keyIterator();
        while (iter.next()) |state_num| {
            if (state_num.* >= floor) {
                try retained.put(state_num.*, {});
            }
        }

        self.applied_remote_state_nums.deinit();
        self.applied_remote_state_nums = retained;
    }

    fn encodeAndCompress(self: *Self, encoded: []const u8) ![]u8 {
        const algo = if (self.config.compression_algorithm == .auto) .zlib else self.config.compression_algorithm;
        const compressed = compress.compress(self.allocator, algo, encoded) catch {
            // If compression fails, send raw
            return self.allocator.dupe(u8, encoded);
        };
        return compressed;
    }

    fn sendAckOnly(self: *Self, now_ms: u64) !void {
        const ack_num = self.latest_received_state_num;
        const ack_instruction: protolite.TransportInstruction = .{
            .protocol_version = types.PROTOCOL_VERSION,
            .old_num = self.last_state_num_sent,
            .new_num = self.last_state_num_sent,
            .ack_num = ack_num,
            .throwaway_num = self.computeThrowawayNum(),
            .diff = null,
            .chaff = &.{},
        };
        const encoded_ack = try ack_instruction.encode(self.allocator);
        defer self.allocator.free(encoded_ack);

        const compressed_ack = try self.encodeAndCompress(encoded_ack);
        defer self.allocator.free(compressed_ack);

        try self.transmitPayload(compressed_ack, now_ms);

        try self.enqueueEvent(.{
            .event_type = .echo_ack,
            .payload = try self.allocator.dupe(u8, &.{}),
            .cols = 0,
            .rows = 0,
            .echo_ack = ack_num,
        });
        self.last_ack_sent_at_ms = now_ms;
        self.last_ack_reported_num = self.latest_received_state_num;
        self.ack_dirty_at_ms = null;
    }

    /// Sends the reserved final state, diffed from the newest state sent. The
    /// server ignores it unless it holds that state, so unacknowledged states
    /// go out again first: the request can't wait on their retransmit timer.
    fn sendShutdown(self: *Self, now_ms: u64) !void {
        for (self.pending_outbound_order.items) |state_num| {
            const pending = self.pending_outbound.get(state_num) orelse continue;
            try self.transmitPayload(pending.payload, now_ms);
        }

        const instruction: protolite.TransportInstruction = .{
            .protocol_version = types.PROTOCOL_VERSION,
            .old_num = self.last_state_num_sent,
            .new_num = shutdown_state_num,
            .ack_num = self.latest_received_state_num,
            .throwaway_num = self.computeThrowawayNum(),
            .diff = null,
            .chaff = &.{},
        };
        const encoded = try instruction.encode(self.allocator);
        defer self.allocator.free(encoded);

        const compressed = try self.encodeAndCompress(encoded);
        defer self.allocator.free(compressed);

        try self.transmitPayload(compressed, now_ms);
        self.shutdown_last_sent_at_ms = now_ms;
    }

    fn computeThrowawayNum(self: *const Self) u64 {
        if (self.pending_outbound_order.items.len == 0) return 0;
        return self.pending_outbound_order.items[0];
    }

    fn updateRto(self: *Self, rtt_sample: u16) void {
        const rtt: i64 = rtt_sample;
        if (self.srtt_ms < 0) {
            self.srtt_ms = rtt;
            self.rttvar_ms = @intCast(@divTrunc(rtt, 2));
        } else {
            const srtt = @as(f64, @floatFromInt(self.srtt_ms));
            const rttvar = @as(f64, @floatFromInt(self.rttvar_ms));
            const sample = @as(f64, @floatFromInt(rtt));
            const new_rttvar = 0.75 * rttvar + 0.25 * @abs(srtt - sample);
            const new_srtt = 0.875 * srtt + 0.125 * sample;
            self.srtt_ms = @intFromFloat(new_srtt);
            self.rttvar_ms = @intFromFloat(new_rttvar);
        }
        const rto: u32 = @intCast(self.srtt_ms + @max(4 * @as(i64, self.rttvar_ms), 1));
        self.current_rto_ms = @max(self.config.initial_rto_ms, @min(rto, self.config.max_rto_ms));
    }

    fn enqueueStringEvent(self: *Self, event_type: types.OutputEventType, text: []const u8) !void {
        try self.enqueueEvent(.{
            .event_type = event_type,
            .payload = try self.allocator.dupe(u8, text),
            .cols = 0,
            .rows = 0,
            .echo_ack = 0,
        });
    }

    fn enqueueDiagnostic(self: *Self, message: []const u8) !void {
        try self.enqueueEvent(.{
            .event_type = .diagnostic,
            .payload = try self.allocator.dupe(u8, message),
            .cols = 0,
            .rows = 0,
            .echo_ack = 0,
        });
    }

    fn enqueueEvent(self: *Self, event: OutputEvent) !void {
        if (self.output_queue.items.len >= self.config.max_receive_states) {
            var dropped = self.output_queue.orderedRemove(0);
            dropped.deinit(self.allocator);
        }
        try self.output_queue.append(self.allocator, event);
    }

    fn failSession(self: *Self, failure: types.FailureCode) void {
        self.last_failure = failure;
        self.state = .failed;
    }
};

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test "skips stale inbound diffs" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{});
    defer client.deinit();

    try client.start(0);

    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .host_payload = "l",
    }, 10);

    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 2,
        .host_payload = "s",
    }, 11);

    var host_events: usize = 0;
    var scratch: [64]u8 = undefined;
    for (0..16) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes) host_events += 1;
    }

    try std.testing.expectEqual(@as(usize, 1), host_events);
    try std.testing.expectEqual(@as(u64, 1), client.latest_received_state_num);
}

test "buffers future inbound diffs until prior state arrives" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{});
    defer client.deinit();

    try client.start(0);

    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 1,
        .new_num = 2,
        .host_payload = "s",
    }, 10);

    try std.testing.expectEqual(@as(u64, 0), client.latest_received_state_num);
    try std.testing.expectEqual(@as(usize, 1), client.pending_inbound.items.len);

    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .host_payload = "l",
    }, 11);

    try std.testing.expectEqual(@as(u64, 2), client.latest_received_state_num);
    try std.testing.expectEqual(@as(usize, 0), client.pending_inbound.items.len);

    var scratch: [64]u8 = undefined;
    var host_payloads: usize = 0;
    for (0..8) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type != .host_bytes) continue;
        if (host_payloads == 0) try std.testing.expectEqualStrings("l", scratch[0..event.written]);
        if (host_payloads == 1) try std.testing.expectEqualStrings("s", scratch[0..event.written]);
        host_payloads += 1;
    }
    try std.testing.expectEqual(@as(usize, 2), host_payloads);
}

test "process inbound encoded transport payload with envelope" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);

    const host_message = try protolite.encodeHostMessage(allocator, &.{.{ .host_bytes = "prompt$ " }});
    defer allocator.free(host_message);

    const inbound: protolite.TransportInstruction = .{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .ack_num = 0,
        .throwaway_num = 0,
        .diff = host_message,
        .chaff = &.{},
    };
    const inbound_payload = try inbound.encode(allocator);
    defer allocator.free(inbound_payload);

    // Prepend packet envelope and wrap protobuf in single-fragment wire header,
    // matching real mosh-server framing.
    var envelope: wire.PacketEnvelope = .{ .timestamp = 100, .timestamp_reply = 0 };
    var envelope_buf: [wire.PacketEnvelope.bytes]u8 = undefined;
    envelope.encode(&envelope_buf);

    const header: wire.FragmentHeader = .{ .message_id = 1, .fragment_num = 0, .final = true };
    var header_buf: [wire.FragmentHeader.bytes]u8 = undefined;
    header.encode(&header_buf);

    var full_payload = try allocator.alloc(u8, wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes + inbound_payload.len);
    defer allocator.free(full_payload);
    @memcpy(full_payload[0..wire.PacketEnvelope.bytes], &envelope_buf);
    @memcpy(
        full_payload[wire.PacketEnvelope.bytes .. wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes],
        &header_buf,
    );
    @memcpy(full_payload[wire.PacketEnvelope.bytes + wire.FragmentHeader.bytes ..], inbound_payload);

    try client.processInboundPayload(full_payload, 200);

    var scratch: [64]u8 = undefined;
    var saw_prompt = false;
    for (0..16) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes and std.mem.eql(u8, scratch[0..event.written], "prompt$ ")) {
            saw_prompt = true;
            break;
        }
    }

    try std.testing.expect(saw_prompt);
}

test "maintenance tick retransmit and retry limit failure" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .max_retransmit_count = 1, .initial_rto_ms = 50, .max_rto_ms = 50 });
    defer client.deinit();

    try client.start(0);
    try client.sendInput("x", 0);

    // drain the initial datagram
    var dg_buf: [1024]u8 = undefined;
    _ = try client.pollNetworkDatagram(&dg_buf);

    try client.maintenanceTick(50);
    try std.testing.expectEqual(types.SessionState.running, client.state);

    // retransmission datagram should be queued
    const retransmit = try client.pollNetworkDatagram(&dg_buf);
    try std.testing.expect(retransmit != null);

    const retry_result = client.maintenanceTick(100);
    try std.testing.expectError(error.RetryLimitExceeded, retry_result);
    try std.testing.expectEqual(types.SessionState.failed, client.state);
    try std.testing.expectEqual(types.FailureCode.retry_limit_exceeded, client.last_failure);
}

test "ack delay emits echo_ack after threshold" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .ack_delay_ms = 5, .ack_interval_ms = 1000 });
    defer client.deinit();

    try client.start(0);
    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
    }, 100);

    try client.maintenanceTick(103);
    var scratch: [32]u8 = undefined;
    var saw_ack = false;

    for (0..8) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .echo_ack and event.echo_ack == 1) saw_ack = true;
    }

    try std.testing.expect(!saw_ack);

    try client.maintenanceTick(105);
    for (0..8) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .echo_ack and event.echo_ack == 1) saw_ack = true;
    }

    try std.testing.expect(saw_ack);
}

test "sendInput produces network datagram" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);

    try client.sendInput("hello", 10);

    var buf: [1024]u8 = undefined;
    const len = try client.pollNetworkDatagram(&buf);
    try std.testing.expect(len != null);
    try std.testing.expect(len.? > wire.PacketEnvelope.bytes);
}

test "shutdown resends pending state, then completes on the server's ack" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);
    try client.sendInput("x", 0);

    var buf: [1024]u8 = undefined;
    while (try client.pollNetworkDatagram(&buf)) |_| {}

    try client.startShutdown(10);
    // The unacknowledged keystroke state, then the shutdown request.
    try std.testing.expect((try client.pollNetworkDatagram(&buf)) != null);
    try std.testing.expect((try client.pollNetworkDatagram(&buf)) != null);
    try std.testing.expect((try client.pollNetworkDatagram(&buf)) == null);
    try std.testing.expectError(error.ShuttingDown, client.sendInput("y", 20));
    try std.testing.expectError(error.ShuttingDown, client.resize(80, 24, 20));

    // Unacknowledged, the request goes out again after the resend interval.
    try client.maintenanceTick(10 + shutdown_resend_interval_ms);
    try std.testing.expect((try client.pollNetworkDatagram(&buf)) != null);
    while (try client.pollNetworkDatagram(&buf)) |_| {}

    try std.testing.expect(!client.isShutdownAcknowledged());
    try client.processInboundInstruction(.{ .ack_num = shutdown_state_num }, 300);
    try std.testing.expect(client.isShutdownAcknowledged());
    try std.testing.expectEqual(@as(u32, 0), client.runtimeState().pending_outbound);

    try client.maintenanceTick(10 + 4 * shutdown_resend_interval_ms);
    try std.testing.expect((try client.pollNetworkDatagram(&buf)) == null);
}

test "shutdown needs a running session" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{});
    defer client.deinit();
    try std.testing.expectError(error.NotStarted, client.startShutdown(0));
}

test "server shutdown is acknowledged, then the session stops" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);

    // The server's shell exited, so it sends the reserved final state.
    try client.processInboundInstruction(.{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = shutdown_state_num,
    }, 10);
    try std.testing.expect(client.isServerShutdown());
    try std.testing.expectEqual(types.SessionState.running, client.state);

    try client.pumpNetwork(20);
    try std.testing.expectEqual(types.SessionState.stopped, client.state);
    try std.testing.expectEqual(shutdown_state_num, client.last_ack_reported_num);
    try std.testing.expectEqual(@as(usize, 0), client.network_datagrams.items.len);
    try std.testing.expectError(error.NotStarted, client.startShutdown(30));

    var saw_event = false;
    var scratch: [64]u8 = undefined;
    for (0..16) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .state_changed and
            std.mem.eql(u8, scratch[0..event.written], "server_shutdown")) saw_event = true;
    }
    try std.testing.expect(saw_event);
}

test "network timeout fails session" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .network_timeout_ms = 500 });
    defer client.deinit();
    try client.start(0);

    const result = client.maintenanceTick(501);
    try std.testing.expectError(error.NetworkTimeout, result);
    try std.testing.expectEqual(types.SessionState.failed, client.state);
    try std.testing.expectEqual(types.FailureCode.timeout, client.last_failure);
}

test "compressed diff roundtrip through core" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var sender = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer sender.deinit();
    try sender.start(0);

    var receiver = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer receiver.deinit();
    try receiver.start(0);

    try sender.sendInput("test", 0);

    var buf: [1024]u8 = undefined;
    const len = try sender.pollNetworkDatagram(&buf);
    try std.testing.expect(len != null);

    try receiver.processInboundPayload(buf[0..len.?], 10);

    var scratch: [64]u8 = undefined;
    var saw_host_bytes = false;
    for (0..8) |_| {
        const event = try receiver.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes) {
            saw_host_bytes = true;
            break;
        }
    }
    try std.testing.expect(saw_host_bytes);
}

test "two clients exchange keystrokes over real UDP socket" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    // Server "client" (listens on a known port)
    var server = try Client.init(allocator, endpoint, .{
        .use_network_crypto = false,
        .local_port = 60001,
        .ack_delay_ms = 5,
        .ack_interval_ms = 1000,
    });
    defer server.deinit();
    try server.start(0);

    // Actual client (binds ephemeral, sends to server)
    var client = try Client.init(allocator, endpoint, .{
        .use_network_crypto = false,
        .ack_delay_ms = 5,
        .ack_interval_ms = 1000,
    });
    defer client.deinit();
    try client.start(0);

    try client.sendInput("hello", 10);

    // Client pumps its datagram out to the wire
    try client.pumpNetwork(10);

    // Give the kernel a moment
    const pause: std.c.timespec = .{ .sec = 0, .nsec = 2_000_000 };
    _ = std.c.nanosleep(&pause, null);

    // Server receives and processes
    try server.pumpNetwork(12);

    // Server should have produced a host_bytes event
    var scratch: [64]u8 = undefined;
    var saw_hello = false;
    for (0..8) |_| {
        const event = try server.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes and std.mem.eql(u8, scratch[0..event.written], "hello")) {
            saw_hello = true;
            break;
        }
    }
    try std.testing.expect(saw_hello);
}

test "single-fragment message auto-detected and processed directly" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);

    const host_message = try protolite.encodeHostMessage(allocator, &.{.{ .host_bytes = "single-frag" }});
    defer allocator.free(host_message);

    const inbound: protolite.TransportInstruction = .{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .ack_num = 0,
        .throwaway_num = 0,
        .diff = host_message,
        .chaff = &.{},
    };
    const inbound_payload = try inbound.encode(allocator);
    defer allocator.free(inbound_payload);

    // Wrap with fragment header (single fragment)
    const header: wire.FragmentHeader = .{ .message_id = 42, .fragment_num = 0, .final = true };
    var header_bytes: [wire.FragmentHeader.bytes]u8 = undefined;
    header.encode(&header_bytes);

    var fragment_payload = try allocator.alloc(u8, wire.FragmentHeader.bytes + inbound_payload.len);
    defer allocator.free(fragment_payload);
    @memcpy(fragment_payload[0..wire.FragmentHeader.bytes], &header_bytes);
    @memcpy(fragment_payload[wire.FragmentHeader.bytes..], inbound_payload);

    var envelope: wire.PacketEnvelope = .{ .timestamp = 100, .timestamp_reply = 0 };
    var envelope_buf: [wire.PacketEnvelope.bytes]u8 = undefined;
    envelope.encode(&envelope_buf);

    var full_payload = try allocator.alloc(u8, wire.PacketEnvelope.bytes + fragment_payload.len);
    defer allocator.free(full_payload);
    @memcpy(full_payload[0..wire.PacketEnvelope.bytes], &envelope_buf);
    @memcpy(full_payload[wire.PacketEnvelope.bytes..], fragment_payload);

    try client.processInboundPayload(full_payload, 200);

    var scratch: [64]u8 = undefined;
    var saw_bytes = false;
    for (0..8) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes and std.mem.eql(u8, scratch[0..event.written], "single-frag")) {
            saw_bytes = true;
            break;
        }
    }
    try std.testing.expect(saw_bytes);
}

test "multi-fragment message reassembled out of order" {
    const allocator = std.testing.allocator;
    const endpoint: types.MoshEndpoint = .{
        .host = "127.0.0.1",
        .port = 60001,
        .key_base64_22 = "ABCDEFGHIJKLMNOPQRSTUV",
    };

    var client = try Client.init(allocator, endpoint, .{ .use_network_crypto = false });
    defer client.deinit();
    try client.start(0);

    const host_message = try protolite.encodeHostMessage(allocator, &.{.{ .host_bytes = "multi-fragment-msg" }});
    defer allocator.free(host_message);

    const inbound: protolite.TransportInstruction = .{
        .protocol_version = types.PROTOCOL_VERSION,
        .old_num = 0,
        .new_num = 1,
        .ack_num = 0,
        .throwaway_num = 0,
        .diff = host_message,
        .chaff = &.{},
    };
    const inbound_payload = try inbound.encode(allocator);
    defer allocator.free(inbound_payload);

    // Split payload into 3 fragments
    const chunk_size = inbound_payload.len / 3 + 1;
    var fragments = try wire.fragmentPayload(allocator, 99, inbound_payload, chunk_size);
    defer {
        for (fragments.items) |f| allocator.free(f);
        fragments.deinit(allocator);
    }
    try std.testing.expect(fragments.items.len > 1);

    var envelope: wire.PacketEnvelope = .{ .timestamp = 100, .timestamp_reply = 0 };
    var envelope_buf: [wire.PacketEnvelope.bytes]u8 = undefined;
    envelope.encode(&envelope_buf);

    // Send fragments out of order: last first, then first, then middle
    const order = &[_]usize{ fragments.items.len - 1, 0, 1 };
    for (order) |idx| {
        const frag = fragments.items[idx];
        var full = try allocator.alloc(u8, wire.PacketEnvelope.bytes + frag.len);
        defer allocator.free(full);
        @memcpy(full[0..wire.PacketEnvelope.bytes], &envelope_buf);
        @memcpy(full[wire.PacketEnvelope.bytes..], frag);
        try client.processInboundPayload(full, 200 + @as(u64, idx));
    }

    var scratch: [64]u8 = undefined;
    var saw_bytes = false;
    for (0..8) |_| {
        const event = try client.pollOutput(&scratch);
        if (event.event_type == .none) break;
        if (event.event_type == .host_bytes and std.mem.eql(u8, scratch[0..event.written], "multi-fragment-msg")) {
            saw_bytes = true;
            break;
        }
    }
    try std.testing.expect(saw_bytes);
    try std.testing.expectEqual(@as(usize, 0), client.inbound_fragments.count());
}
