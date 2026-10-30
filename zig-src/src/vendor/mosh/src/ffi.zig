//! Stable C ABI wrappers around the scaffolded Zig mosh core.

const std = @import("std");
const core = @import("core.zig");
const types = @import("types.zig");

const allocator = std.heap.c_allocator;

const CreateConfigJson = struct {
    host: ?[]const u8 = null,
    port: ?u16 = null,
    keyBase64_22: ?[]const u8 = null,
    sendMinDelayMs: ?u32 = null,
    maxReceiveStates: ?u32 = null,
    ackIntervalMs: ?u32 = null,
    ackDelayMs: ?u32 = null,
    networkTimeoutMs: ?u32 = null,
    maxRetransmitCount: ?u32 = null,
    initialRtoMs: ?u32 = null,
    maxRtoMs: ?u32 = null,
    heartbeatIntervalMs: ?u32 = null,
    mtu: ?u32 = null,
    localPort: ?u16 = null,
    useNetworkCrypto: ?bool = null,
    compressionAlgorithm: ?[]const u8 = null,
};

fn nowMs() u64 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.REALTIME, &ts) != 0) return 0;
    const ms = @as(i64, ts.sec) * std.time.ms_per_s + @divTrunc(@as(i64, ts.nsec), std.time.ns_per_ms);
    return @intCast(@max(ms, 0));
}

pub export fn mosh_client_abi_version() callconv(.c) u32 {
    return types.ABI_VERSION;
}

pub export fn mosh_client_create(config_json: ?[*:0]const u8, out_handle: ?*?*anyopaque) callconv(.c) c_int {
    const out = out_handle orelse return @backingInt(types.ErrorCode.invalid_argument);
    out.* = null;

    var endpoint_host: []const u8 = "127.0.0.1";
    var endpoint_port: u16 = 60001;
    var endpoint_key: []const u8 = "ABCDEFGHIJKLMNOPQRSTUV";
    var config: types.MoshClientConfig = .{};

    if (config_json) |json_ptr| {
        const json_slice = std.mem.span(json_ptr);
        if (json_slice.len > 0) {
            const parsed = std.json.parseFromSlice(
                CreateConfigJson,
                allocator,
                json_slice,
                .{ .ignore_unknown_fields = true },
            ) catch return @backingInt(types.ErrorCode.invalid_argument);
            defer parsed.deinit();

            if (parsed.value.host) |host| endpoint_host = host;
            if (parsed.value.port) |port| endpoint_port = port;
            if (parsed.value.keyBase64_22) |key_base64_22| endpoint_key = key_base64_22;

            if (parsed.value.sendMinDelayMs) |value| config.send_min_delay_ms = value;
            if (parsed.value.maxReceiveStates) |value| config.max_receive_states = value;
            if (parsed.value.ackIntervalMs) |value| config.ack_interval_ms = value;
            if (parsed.value.ackDelayMs) |value| config.ack_delay_ms = value;
            if (parsed.value.networkTimeoutMs) |value| config.network_timeout_ms = value;
            if (parsed.value.maxRetransmitCount) |value| config.max_retransmit_count = value;
            if (parsed.value.initialRtoMs) |value| config.initial_rto_ms = value;
            if (parsed.value.maxRtoMs) |value| config.max_rto_ms = value;
            if (parsed.value.heartbeatIntervalMs) |value| config.heartbeat_interval_ms = value;
            if (parsed.value.mtu) |value| config.mtu = value;
            if (parsed.value.localPort) |value| config.local_port = value;
            if (parsed.value.useNetworkCrypto) |value| config.use_network_crypto = value;
            if (parsed.value.compressionAlgorithm) |value| {
                config.compression_algorithm = parseCompressionAlgorithm(value);
            }
        }
    }

    const endpoint: types.MoshEndpoint = .{
        .host = endpoint_host,
        .port = endpoint_port,
        .key_base64_22 = endpoint_key,
    };

    const client = allocator.create(core.Client) catch return @backingInt(types.ErrorCode.alloc_failed);
    client.* = core.Client.init(allocator, endpoint, config) catch |err| {
        allocator.destroy(client);
        return @backingInt(mapCoreError(err));
    };

    out.* = @ptrCast(client);
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_start(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.start(nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_send_input(handle: ?*anyopaque, ptr: ?[*]const u8, len: usize) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    if (len > 0 and ptr == null) return @backingInt(types.ErrorCode.invalid_argument);

    const bytes: []const u8 = if (len == 0)
        &.{}
    else
        ptr.?[0..len];

    client.sendInput(bytes, nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_resize(handle: ?*anyopaque, cols: i32, rows: i32) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.resize(cols, rows, nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_poll_output(
    handle: ?*anyopaque,
    out_buf: ?[*]u8,
    cap: usize,
    out_written: ?*usize,
    out_event_type: ?*c_int,
    out_cols: ?*i32,
    out_rows: ?*i32,
    out_echo_ack: ?*u64,
) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);

    if (out_written) |slot| slot.* = 0;
    if (out_event_type) |slot| slot.* = @backingInt(types.OutputEventType.none);
    if (out_cols) |slot| slot.* = 0;
    if (out_rows) |slot| slot.* = 0;
    if (out_echo_ack) |slot| slot.* = 0;

    if (cap > 0 and out_buf == null) return @backingInt(types.ErrorCode.invalid_argument);

    const out_slice: []u8 = if (cap == 0)
        &.{}
    else
        out_buf.?[0..cap];

    const result = client.pollOutput(out_slice) catch |err| return @backingInt(mapCoreError(err));

    if (out_written) |slot| slot.* = result.written;
    if (out_event_type) |slot| slot.* = @backingInt(result.event_type);
    if (out_cols) |slot| slot.* = result.cols;
    if (out_rows) |slot| slot.* = result.rows;
    if (out_echo_ack) |slot| slot.* = result.echo_ack;

    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_poll_state(handle: ?*anyopaque, out_state: ?*types.RuntimeState) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    const state_slot = out_state orelse return @backingInt(types.ErrorCode.invalid_argument);
    state_slot.* = client.runtimeState();
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_maintenance_tick(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.maintenanceTick(nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_pump_network(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.pumpNetwork(nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_start_shutdown(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.startShutdown(nowMs()) catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

/// Sets `out_acknowledged` to 1 once the server has acknowledged the shutdown.
pub export fn mosh_client_shutdown_acknowledged(handle: ?*anyopaque, out_acknowledged: ?*c_int) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    const slot = out_acknowledged orelse return @backingInt(types.ErrorCode.invalid_argument);
    slot.* = @intFromBool(client.isShutdownAcknowledged());
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_stop(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.stop() catch |err| return @backingInt(mapCoreError(err));
    return @backingInt(types.ErrorCode.ok);
}

pub export fn mosh_client_destroy(handle: ?*anyopaque) callconv(.c) c_int {
    const client = fromHandle(handle) orelse return @backingInt(types.ErrorCode.bad_handle);
    client.deinit();
    allocator.destroy(client);
    return @backingInt(types.ErrorCode.ok);
}

fn fromHandle(handle: ?*anyopaque) ?*core.Client {
    const raw = handle orelse return null;
    return @ptrCast(@alignCast(raw));
}

fn parseCompressionAlgorithm(value: []const u8) types.CompressionAlgorithm {
    if (std.mem.eql(u8, value, "auto")) return .auto;
    if (std.mem.eql(u8, value, "zlib")) return .zlib;
    if (std.mem.eql(u8, value, "lz4")) return .lz4;
    if (std.mem.eql(u8, value, "lzfse")) return .lzfse;
    if (std.mem.eql(u8, value, "raw")) return .raw;
    return .auto;
}

fn mapCoreError(err: anyerror) types.ErrorCode {
    return switch (err) {
        error.InvalidEndpoint,
        error.InvalidResize,
        error.InvalidPrintableKeyLength,
        error.InvalidPrintableKeyEncoding,
        => .invalid_argument,
        error.NotStarted, error.ShuttingDown => .not_started,
        error.AlreadyStarted => .already_started,
        error.OutputBufferTooSmall => .output_buffer_too_small,
        error.OutOfMemory => .alloc_failed,
        error.NetworkTimeout => .internal,
        error.CircuitBreakerTripped => .internal,
        error.FragmentMismatch,
        error.FragmentMissing,
        => .internal,
        else => .internal,
    };
}

test "ffi create/start/poll/destroy happy path" {
    var handle: ?*anyopaque = null;
    const create_rc = mosh_client_create(
        "{\"host\":\"127.0.0.1\",\"port\":60001,\"keyBase64_22\":\"ABCDEFGHIJKLMNOPQRSTUV\"}",
        &handle,
    );
    try std.testing.expectEqual(@as(c_int, 0), create_rc);
    defer _ = mosh_client_destroy(handle);

    try std.testing.expect(handle != null);
    try std.testing.expectEqual(@as(c_int, 0), mosh_client_start(handle));
    try std.testing.expectEqual(@as(c_int, 0), mosh_client_send_input(handle, "a".ptr, 1));

    var out_buf: [32]u8 = undefined;
    var out_written: usize = 0;
    var out_event_type: c_int = 0;
    var out_cols: i32 = 0;
    var out_rows: i32 = 0;
    var out_echo_ack: u64 = 0;
    _ = mosh_client_poll_output(
        handle,
        &out_buf,
        out_buf.len,
        &out_written,
        &out_event_type,
        &out_cols,
        &out_rows,
        &out_echo_ack,
    );
}
