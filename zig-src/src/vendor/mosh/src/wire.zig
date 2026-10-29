//! Wire primitives shared by transport and protocol layers.

const std = @import("std");

pub const Direction = enum(u8) {
    to_server = 0,
    to_client = 1,
};

pub const protocol_version: u32 = 2;
pub const packet_header_bytes: usize = 8 + 2 + 2;
pub const fragment_header_bytes: usize = 8 + 2;

pub fn timestamp16(now_ms: u64) u16 {
    var value: u16 = @intCast(now_ms % 65_536);
    if (value == std.math.maxInt(u16)) value -%= 1;
    return value;
}

pub fn timestampDiff(new: u16, old: u16) u16 {
    const diff: i32 = @as(i32, new) - @as(i32, old);
    if (diff >= 0) return @intCast(diff);
    return @intCast(diff + 65_536);
}

pub fn directionalSequence(sequence: u64, direction: Direction) u64 {
    const direction_bit: u64 = @as(u64, @backingInt(direction)) << 63;
    const sequence_mask: u64 = std.math.maxInt(u64) ^ (@as(u64, 1) << 63);
    return direction_bit | (sequence & sequence_mask);
}

pub fn splitDirectionalSequence(value: u64) struct { sequence: u64, direction: Direction } {
    const direction: Direction = if (((value >> 63) & 1) == 1) .to_client else .to_server;
    const sequence_mask: u64 = std.math.maxInt(u64) ^ (@as(u64, 1) << 63);
    return .{
        .sequence = value & sequence_mask,
        .direction = direction,
    };
}

/// Packet envelope: 2-byte timestamp + 2-byte timestamp_reply, both big-endian.
/// Prepended to plaintext before encryption.
pub const PacketEnvelope = struct {
    timestamp: u16,
    timestamp_reply: u16,

    pub const bytes: usize = 4;

    pub fn encode(self: PacketEnvelope, out: *[bytes]u8) void {
        std.mem.writeInt(u16, out[0..2], self.timestamp, .big);
        std.mem.writeInt(u16, out[2..4], self.timestamp_reply, .big);
    }

    pub fn decode(data: *const [bytes]u8) PacketEnvelope {
        return .{
            .timestamp = std.mem.readInt(u16, data[0..2], .big),
            .timestamp_reply = std.mem.readInt(u16, data[2..4], .big),
        };
    }
};

/// Fragment header: 8-byte message_id + 2-byte fragment_num (high bit = final).
pub const FragmentHeader = struct {
    message_id: u64,
    fragment_num: u16,
    final: bool,

    pub const bytes: usize = 10;

    pub fn encode(self: FragmentHeader, out: *[bytes]u8) void {
        std.mem.writeInt(u64, out[0..8], self.message_id, .big);
        var encoded: u16 = self.fragment_num & 0x7FFF;
        if (self.final) encoded |= 0x8000;
        std.mem.writeInt(u16, out[8..10], encoded, .big);
    }

    pub fn decode(data: *const [bytes]u8) FragmentHeader {
        const encoded = std.mem.readInt(u16, data[8..10], .big);
        return .{
            .message_id = std.mem.readInt(u64, data[0..8], .big),
            .fragment_num = encoded & 0x7FFF,
            .final = (encoded & 0x8000) != 0,
        };
    }
};

/// Split `payload` into fragments of at most `max_chunk_size` bytes each.
/// Returns an ArrayList of owned byte slices. Caller must free each slice
/// and the ArrayList itself.
pub fn fragmentPayload(
    allocator: std.mem.Allocator,
    message_id: u64,
    payload: []const u8,
    max_chunk_size: usize,
) !std.ArrayList([]u8) {
    var fragments: std.ArrayList([]u8) = .empty;
    errdefer {
        for (fragments.items) |f| allocator.free(f);
        fragments.deinit(allocator);
    }

    if (payload.len == 0) return fragments;

    const num_fragments = (payload.len + max_chunk_size - 1) / max_chunk_size;
    var offset: usize = 0;
    var fragment_num: u16 = 0;

    while (offset < payload.len) : (fragment_num += 1) {
        const chunk_end = @min(offset + max_chunk_size, payload.len);
        const chunk = payload[offset..chunk_end];
        const is_final = fragment_num == num_fragments - 1;

        const header: FragmentHeader = .{
            .message_id = message_id,
            .fragment_num = fragment_num,
            .final = is_final,
        };

        var header_bytes: [FragmentHeader.bytes]u8 = undefined;
        header.encode(&header_bytes);

        const fragment = try allocator.alloc(u8, FragmentHeader.bytes + chunk.len);
        @memcpy(fragment[0..FragmentHeader.bytes], &header_bytes);
        @memcpy(fragment[FragmentHeader.bytes..], chunk);

        try fragments.append(allocator, fragment);
        offset = chunk_end;
    }

    return fragments;
}

/// Reassemble fragments into a single message. Returns true if complete.
/// `out_message_id` receives the message_id on success.
pub fn reassembleFragments(
    allocator: std.mem.Allocator,
    fragments: *std.ArrayList([]const u8),
    out_message_id: *u64,
) !?[]u8 {
    if (fragments.items.len == 0) return null;

    // Sort by fragment_num
    std.mem.sort([]const u8, fragments.items, {}, struct {
        fn lessThan(_: void, lhs: []const u8, rhs: []const u8) bool {
            const lhs_header = FragmentHeader.decode(lhs[0..FragmentHeader.bytes]);
            const rhs_header = FragmentHeader.decode(rhs[0..FragmentHeader.bytes]);
            return lhs_header.fragment_num < rhs_header.fragment_num;
        }
    }.lessThan);

    const first_header = FragmentHeader.decode(fragments.items[0][0..FragmentHeader.bytes]);
    const message_id = first_header.message_id;

    // Verify all fragments belong to same message_id and are contiguous
    var total_len: usize = 0;
    var expected_num: u16 = 0;
    var has_final = false;
    for (fragments.items) |f| {
        const header = FragmentHeader.decode(f[0..FragmentHeader.bytes]);
        if (header.message_id != message_id) return error.FragmentMismatch;
        if (header.fragment_num != expected_num) return error.FragmentMissing;
        if (header.final) has_final = true;
        total_len += f.len - FragmentHeader.bytes;
        expected_num += 1;
    }

    if (!has_final) return null;

    // Assemble
    var out = try allocator.alloc(u8, total_len);
    errdefer allocator.free(out);

    var offset: usize = 0;
    for (fragments.items) |f| {
        const chunk = f[FragmentHeader.bytes..];
        @memcpy(out[offset..][0..chunk.len], chunk);
        offset += chunk.len;
    }

    out_message_id.* = message_id;
    return out;
}

/// Detect whether `data` starts with a fragment header rather than a
/// protobuf payload.
///
/// A valid protobuf field key is always >= 0x08 (field numbers start at 1).
/// A fragment header starts with the big-endian message_id. For practical
/// message IDs (< 2^59) the first byte is 0x00, which is an invalid protobuf
/// key. This heuristic is essentially foolproof for the first ~10^18 packets.
pub fn isFragmentPayload(data: []const u8) bool {
    return data.len >= FragmentHeader.bytes and data[0] < 8;
}

test "timestamp and directional sequence behavior" {
    try std.testing.expectEqual(@as(u16, 65_534), timestamp16(65_535));
    try std.testing.expectEqual(@as(u16, 0), timestamp16(65_536));

    try std.testing.expectEqual(@as(u16, 8), timestampDiff(10, 2));
    try std.testing.expectEqual(@as(u16, 65_528), timestampDiff(2, 10));

    const directional = directionalSequence(std.math.maxInt(u64), .to_client);
    const split = splitDirectionalSequence(directional);
    try std.testing.expectEqual(Direction.to_client, split.direction);
    try std.testing.expectEqual(std.math.maxInt(u64) ^ (@as(u64, 1) << 63), split.sequence);
}

test "packet envelope roundtrip" {
    const original: PacketEnvelope = .{ .timestamp = 1234, .timestamp_reply = 5678 };
    var buf: [4]u8 = undefined;
    original.encode(&buf);
    const decoded = PacketEnvelope.decode(&buf);
    try std.testing.expectEqual(original.timestamp, decoded.timestamp);
    try std.testing.expectEqual(original.timestamp_reply, decoded.timestamp_reply);
}

test "fragment header roundtrip" {
    const original: FragmentHeader = .{ .message_id = 0x123456789ABCDEF0, .fragment_num = 42, .final = true };
    var buf: [10]u8 = undefined;
    original.encode(&buf);
    const decoded = FragmentHeader.decode(&buf);
    try std.testing.expectEqual(original.message_id, decoded.message_id);
    try std.testing.expectEqual(original.fragment_num, decoded.fragment_num);
    try std.testing.expectEqual(original.final, decoded.final);
}

test "fragment payload and reassemble" {
    const allocator = std.testing.allocator;
    const payload = "ABCDEFGHIJ";
    var fragments = try fragmentPayload(allocator, 7, payload, 3);
    defer {
        for (fragments.items) |f| allocator.free(f);
        fragments.deinit(allocator);
    }

    try std.testing.expectEqual(@as(usize, 4), fragments.items.len);

    var message_id: u64 = undefined;
    var reassemble_list: std.ArrayList([]const u8) = .empty;
    defer reassemble_list.deinit(allocator);
    for (fragments.items) |f| try reassemble_list.append(allocator, f);

    const assembled = try reassembleFragments(allocator, &reassemble_list, &message_id);
    defer if (assembled) |a| allocator.free(a);

    try std.testing.expect(assembled != null);
    try std.testing.expectEqual(@as(u64, 7), message_id);
    try std.testing.expectEqualStrings(payload, assembled.?);
}

test "isFragmentPayload heuristic" {
    // Fragment header for message_id=1, fragment_num=0, final=true
    var fragment: [12]u8 = undefined;
    const header: FragmentHeader = .{ .message_id = 1, .fragment_num = 0, .final = true };
    header.encode(fragment[0..10]);
    fragment[10] = 'a';
    fragment[11] = 'b';
    try std.testing.expect(isFragmentPayload(&fragment));

    // Valid protobuf start: field 1 varint = 0x08
    const protobuf = &[_]u8{ 0x08, 0x02, 0x10, 0x00 };
    try std.testing.expect(!isFragmentPayload(protobuf));

    // Too short to be a fragment
    try std.testing.expect(!isFragmentPayload(&[_]u8{ 0x00, 0x00 }));

    // Empty
    try std.testing.expect(!isFragmentPayload(&[_]u8{}));

    // Edge: first byte 0x07 (invalid protobuf, could be fragment with very large message_id)
    const edge = &[_]u8{ 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
    try std.testing.expect(isFragmentPayload(edge));
}
