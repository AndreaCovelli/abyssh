//! Shared public types for the scaffolded Zig mosh client implementation.

const std = @import("std");

pub const ABI_VERSION: u32 = 1;
pub const PROTOCOL_VERSION: u32 = 2;

pub const ErrorCode = enum(c_int) {
    ok = 0,
    invalid_argument = 1,
    bad_handle = 2,
    alloc_failed = 3,
    not_started = 4,
    already_started = 5,
    output_buffer_too_small = 6,
    internal = 7,
    not_implemented = 8,
};

pub const SessionState = enum(c_int) {
    idle = 0,
    starting = 1,
    running = 2,
    stopping = 3,
    stopped = 4,
    failed = 5,
};

pub const FailureCode = enum(c_int) {
    none = 0,
    timeout = 1,
    protocol_violation = 2,
    authentication_failure = 3,
    retry_limit_exceeded = 4,
    circuit_breaker_tripped = 5,
    transport_failure = 6,
};

pub const OutputEventType = enum(c_int) {
    none = 0,
    host_bytes = 1,
    resize = 2,
    echo_ack = 3,
    state_changed = 4,
    diagnostic = 5,
};

pub const MoshEndpoint = struct {
    host: []const u8,
    port: u16,
    key_base64_22: []const u8,
};

pub const CompressionAlgorithm = enum(c_int) {
    auto = 0,
    zlib = 1,
    lz4 = 2,
    lzfse = 3,
    raw = 4,
};

pub const MoshClientConfig = struct {
    send_min_delay_ms: u32 = 8,
    max_receive_states: u32 = 1024,
    ack_interval_ms: u32 = 3000,
    ack_delay_ms: u32 = 100,
    network_timeout_ms: u32 = 30_000,
    max_retransmit_count: u32 = 32,
    initial_rto_ms: u32 = 600,
    max_rto_ms: u32 = 4000,
    heartbeat_interval_ms: u32 = 3000,
    mtu: u32 = 1200,
    local_port: ?u16 = null,
    use_network_crypto: bool = true,
    compression_algorithm: CompressionAlgorithm = .zlib,

    pub fn normalized(self: MoshClientConfig) MoshClientConfig {
        var out: MoshClientConfig = self;
        if (out.max_receive_states == 0) out.max_receive_states = 1;
        if (out.network_timeout_ms < 500) out.network_timeout_ms = 500;
        if (out.max_retransmit_count == 0) out.max_retransmit_count = 1;
        if (out.initial_rto_ms < 50) out.initial_rto_ms = 50;
        if (out.max_rto_ms < out.initial_rto_ms) out.max_rto_ms = out.initial_rto_ms;
        if (out.heartbeat_interval_ms < 250) out.heartbeat_interval_ms = 250;
        if (out.mtu < 128) out.mtu = 128;
        return out;
    }
};

pub const RuntimeState = extern struct {
    state: c_int,
    last_failure_code: c_int,
    last_state_num_sent: u64,
    last_state_num_received: u64,
    pending_outbound: u32,
    pending_host_ops: u32,
    current_rto_ms: u32,
};

pub fn toRuntimeState(
    state: SessionState,
    failure: FailureCode,
    last_state_num_sent: u64,
    last_state_num_received: u64,
    pending_outbound: u32,
    pending_host_ops: u32,
    current_rto_ms: u32,
) RuntimeState {
    return .{
        .state = @backingInt(state),
        .last_failure_code = @backingInt(failure),
        .last_state_num_sent = last_state_num_sent,
        .last_state_num_received = last_state_num_received,
        .pending_outbound = pending_outbound,
        .pending_host_ops = pending_host_ops,
        .current_rto_ms = current_rto_ms,
    };
}

test "config normalization enforces safety floors" {
    const cfg: MoshClientConfig = .{
        .max_receive_states = 0,
        .network_timeout_ms = 1,
        .max_retransmit_count = 0,
        .initial_rto_ms = 1,
        .max_rto_ms = 2,
        .heartbeat_interval_ms = 1,
        .mtu = 1,
    };
    const normalized = cfg.normalized();
    try std.testing.expectEqual(@as(u32, 1), normalized.max_receive_states);
    try std.testing.expectEqual(@as(u32, 500), normalized.network_timeout_ms);
    try std.testing.expectEqual(@as(u32, 1), normalized.max_retransmit_count);
    try std.testing.expectEqual(@as(u32, 50), normalized.initial_rto_ms);
    try std.testing.expectEqual(@as(u32, 50), normalized.max_rto_ms);
    try std.testing.expectEqual(@as(u32, 250), normalized.heartbeat_interval_ms);
    try std.testing.expectEqual(@as(u32, 128), normalized.mtu);
}
