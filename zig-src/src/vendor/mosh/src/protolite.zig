//! Lightweight protobuf-compatible encoding/decoding used by mosh messages.

const std = @import("std");

pub const ProtoLiteError = error{
    Truncated,
    MalformedVarint,
    UnsupportedWireType,
    InvalidFieldNumber,
    InvalidLength,
    MissingRequiredField,
};

const WireType = enum(u64) {
    varint = 0,
    fixed64 = 1,
    length_delimited = 2,
    fixed32 = 5,
};

pub const TransportInstruction = struct {
    protocol_version: ?u32 = null,
    old_num: ?u64 = null,
    new_num: ?u64 = null,
    ack_num: ?u64 = null,
    throwaway_num: ?u64 = null,
    diff: ?[]const u8 = null,
    chaff: ?[]const u8 = null,

    pub fn encode(self: TransportInstruction, allocator: std.mem.Allocator) ![]u8 {
        var out: std.ArrayList(u8) = .empty;
        defer out.deinit(allocator);

        if (self.protocol_version) |value| {
            try writeVarintField(&out, allocator, 1, value);
        }
        if (self.old_num) |value| {
            try writeVarintField(&out, allocator, 2, value);
        }
        if (self.new_num) |value| {
            try writeVarintField(&out, allocator, 3, value);
        }
        if (self.ack_num) |value| {
            try writeVarintField(&out, allocator, 4, value);
        }
        if (self.throwaway_num) |value| {
            try writeVarintField(&out, allocator, 5, value);
        }
        if (self.diff) |value| {
            try writeBytesField(&out, allocator, 6, value);
        }
        if (self.chaff) |value| {
            try writeBytesField(&out, allocator, 7, value);
        }

        return out.toOwnedSlice(allocator);
    }

    pub fn decode(data: []const u8) ProtoLiteError!TransportInstruction {
        var cursor: usize = 0;
        var instruction: TransportInstruction = .{};

        while (cursor < data.len) {
            const header = try readFieldHeader(data, &cursor);
            switch (header.field_number) {
                1 => {
                    if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                    const value = try readVarint(data, &cursor);
                    instruction.protocol_version = @truncate(value);
                },
                2 => {
                    if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                    instruction.old_num = try readVarint(data, &cursor);
                },
                3 => {
                    if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                    instruction.new_num = try readVarint(data, &cursor);
                },
                4 => {
                    if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                    instruction.ack_num = try readVarint(data, &cursor);
                },
                5 => {
                    if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                    instruction.throwaway_num = try readVarint(data, &cursor);
                },
                6 => {
                    if (header.wire_type != .length_delimited) return ProtoLiteError.UnsupportedWireType;
                    instruction.diff = try readLengthDelimited(data, &cursor);
                },
                7 => {
                    if (header.wire_type != .length_delimited) return ProtoLiteError.UnsupportedWireType;
                    instruction.chaff = try readLengthDelimited(data, &cursor);
                },
                else => try skipByWireType(data, &cursor, header.wire_type),
            }
        }

        return instruction;
    }
};

pub const HostInstruction = union(enum) {
    host_bytes: []const u8,
    resize: struct { width: i32, height: i32 },
    echo_ack: u64,
};

pub const DecodedHostInstruction = union(enum) {
    host_bytes: []u8,
    resize: struct { width: i32, height: i32 },
    echo_ack: u64,

    pub fn deinit(self: *DecodedHostInstruction, allocator: std.mem.Allocator) void {
        switch (self.*) {
            .host_bytes => |bytes| allocator.free(bytes),
            else => {},
        }
    }
};

pub const UserInstruction = union(enum) {
    keystroke: []const u8,
    resize: struct { width: i32, height: i32 },
};

pub fn encodeHostMessage(allocator: std.mem.Allocator, instructions: []const HostInstruction) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(allocator);

    for (instructions) |instruction| {
        const encoded_instruction = try encodeHostInstruction(allocator, instruction);
        defer allocator.free(encoded_instruction);
        try writeBytesField(&out, allocator, 1, encoded_instruction);
    }

    return out.toOwnedSlice(allocator);
}

pub fn decodeHostMessage(allocator: std.mem.Allocator, data: []const u8) ![]DecodedHostInstruction {
    var cursor: usize = 0;
    var decoded: std.ArrayList(DecodedHostInstruction) = .empty;
    errdefer {
        for (decoded.items) |*instruction| instruction.deinit(allocator);
        decoded.deinit(allocator);
    }

    while (cursor < data.len) {
        const header = try readFieldHeader(data, &cursor);
        if (header.field_number == 1 and header.wire_type == .length_delimited) {
            const payload = try readLengthDelimited(data, &cursor);
            const instruction = try decodeHostInstruction(allocator, payload);
            try decoded.append(allocator, instruction);
            continue;
        }

        try skipByWireType(data, &cursor, header.wire_type);
    }

    return decoded.toOwnedSlice(allocator);
}

pub fn freeHostInstructions(allocator: std.mem.Allocator, instructions: []DecodedHostInstruction) void {
    for (instructions) |*instruction| instruction.deinit(allocator);
    allocator.free(instructions);
}

pub fn encodeUserMessage(allocator: std.mem.Allocator, instructions: []const UserInstruction) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(allocator);

    for (instructions) |instruction| {
        const encoded_instruction = try encodeUserInstruction(allocator, instruction);
        defer allocator.free(encoded_instruction);
        try writeBytesField(&out, allocator, 1, encoded_instruction);
    }

    return out.toOwnedSlice(allocator);
}

fn encodeHostInstruction(allocator: std.mem.Allocator, instruction: HostInstruction) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(allocator);

    switch (instruction) {
        .host_bytes => |bytes| {
            var host_bytes_payload: std.ArrayList(u8) = .empty;
            defer host_bytes_payload.deinit(allocator);
            try writeBytesField(&host_bytes_payload, allocator, 4, bytes);
            try writeBytesField(&out, allocator, 2, host_bytes_payload.items);
        },
        .resize => |resize| {
            var resize_payload: std.ArrayList(u8) = .empty;
            defer resize_payload.deinit(allocator);
            try writeVarintField(&resize_payload, allocator, 5, signedI32ToVarint(resize.width));
            try writeVarintField(&resize_payload, allocator, 6, signedI32ToVarint(resize.height));
            try writeBytesField(&out, allocator, 3, resize_payload.items);
        },
        .echo_ack => |ack| {
            var echo_payload: std.ArrayList(u8) = .empty;
            defer echo_payload.deinit(allocator);
            try writeVarintField(&echo_payload, allocator, 8, ack);
            try writeBytesField(&out, allocator, 7, echo_payload.items);
        },
    }

    return out.toOwnedSlice(allocator);
}

fn decodeHostInstruction(allocator: std.mem.Allocator, data: []const u8) !DecodedHostInstruction {
    var cursor: usize = 0;
    var result: ?DecodedHostInstruction = null;

    while (cursor < data.len) {
        const header = try readFieldHeader(data, &cursor);
        switch (header.field_number) {
            2 => {
                if (header.wire_type != .length_delimited) return ProtoLiteError.UnsupportedWireType;
                const payload = try readLengthDelimited(data, &cursor);
                result = try decodeHostBytesInstruction(allocator, payload);
            },
            3 => {
                if (header.wire_type != .length_delimited) return ProtoLiteError.UnsupportedWireType;
                const payload = try readLengthDelimited(data, &cursor);
                result = try decodeResizeInstruction(payload);
            },
            7 => {
                if (header.wire_type != .length_delimited) return ProtoLiteError.UnsupportedWireType;
                const payload = try readLengthDelimited(data, &cursor);
                result = try decodeEchoAckInstruction(payload);
            },
            else => try skipByWireType(data, &cursor, header.wire_type),
        }
    }

    return result orelse ProtoLiteError.MissingRequiredField;
}

fn decodeHostBytesInstruction(allocator: std.mem.Allocator, data: []const u8) !DecodedHostInstruction {
    var cursor: usize = 0;
    var payload: []const u8 = &.{};

    while (cursor < data.len) {
        const header = try readFieldHeader(data, &cursor);
        if (header.field_number == 4 and header.wire_type == .length_delimited) {
            payload = try readLengthDelimited(data, &cursor);
            continue;
        }

        try skipByWireType(data, &cursor, header.wire_type);
    }

    return .{ .host_bytes = try allocator.dupe(u8, payload) };
}

fn decodeResizeInstruction(data: []const u8) !DecodedHostInstruction {
    var cursor: usize = 0;
    var width: i32 = 0;
    var height: i32 = 0;

    while (cursor < data.len) {
        const header = try readFieldHeader(data, &cursor);
        switch (header.field_number) {
            5 => {
                if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                width = varintToSignedI32(try readVarint(data, &cursor));
            },
            6 => {
                if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
                height = varintToSignedI32(try readVarint(data, &cursor));
            },
            else => try skipByWireType(data, &cursor, header.wire_type),
        }
    }

    return .{ .resize = .{ .width = width, .height = height } };
}

fn decodeEchoAckInstruction(data: []const u8) !DecodedHostInstruction {
    var cursor: usize = 0;
    var ack: u64 = 0;

    while (cursor < data.len) {
        const header = try readFieldHeader(data, &cursor);
        if (header.field_number == 8) {
            if (header.wire_type != .varint) return ProtoLiteError.UnsupportedWireType;
            ack = try readVarint(data, &cursor);
            continue;
        }

        try skipByWireType(data, &cursor, header.wire_type);
    }

    return .{ .echo_ack = ack };
}

fn encodeUserInstruction(allocator: std.mem.Allocator, instruction: UserInstruction) ![]u8 {
    var out: std.ArrayList(u8) = .empty;
    defer out.deinit(allocator);

    switch (instruction) {
        .keystroke => |bytes| {
            var keystroke_payload: std.ArrayList(u8) = .empty;
            defer keystroke_payload.deinit(allocator);
            try writeBytesField(&keystroke_payload, allocator, 4, bytes);
            try writeBytesField(&out, allocator, 2, keystroke_payload.items);
        },
        .resize => |resize| {
            var resize_payload: std.ArrayList(u8) = .empty;
            defer resize_payload.deinit(allocator);
            try writeVarintField(&resize_payload, allocator, 5, signedI32ToVarint(resize.width));
            try writeVarintField(&resize_payload, allocator, 6, signedI32ToVarint(resize.height));
            try writeBytesField(&out, allocator, 3, resize_payload.items);
        },
    }

    return out.toOwnedSlice(allocator);
}

fn signedI32ToVarint(value: i32) u64 {
    const signed: i64 = value;
    return @bitCast(signed);
}

fn varintToSignedI32(value: u64) i32 {
    const narrow: u32 = @truncate(value);
    return @bitCast(narrow);
}

fn readFieldHeader(data: []const u8, cursor: *usize) ProtoLiteError!struct { field_number: u64, wire_type: WireType } {
    const key = try readVarint(data, cursor);
    const field_number = key >> 3;
    if (field_number == 0) return ProtoLiteError.InvalidFieldNumber;

    const wire_value = key & 0x07;
    const wire_type = std.enums.fromInt(WireType, wire_value) orelse return ProtoLiteError.UnsupportedWireType;
    return .{ .field_number = field_number, .wire_type = wire_type };
}

fn readVarint(data: []const u8, cursor: *usize) ProtoLiteError!u64 {
    var shift: u6 = 0;
    var result: u64 = 0;

    while (shift < 64) : (shift += 7) {
        if (cursor.* >= data.len) return ProtoLiteError.Truncated;

        const byte = data[cursor.*];
        cursor.* += 1;
        result |= @as(u64, byte & 0x7F) << shift;

        if ((byte & 0x80) == 0) return result;
    }

    return ProtoLiteError.MalformedVarint;
}

fn readLengthDelimited(data: []const u8, cursor: *usize) ProtoLiteError![]const u8 {
    const raw_length = try readVarint(data, cursor);
    const length = std.math.cast(usize, raw_length) orelse return ProtoLiteError.InvalidLength;

    if (cursor.* + length > data.len) return ProtoLiteError.Truncated;
    const out = data[cursor.* .. cursor.* + length];
    cursor.* += length;
    return out;
}

fn skipByWireType(data: []const u8, cursor: *usize, wire_type: WireType) ProtoLiteError!void {
    switch (wire_type) {
        .varint => _ = try readVarint(data, cursor),
        .fixed64 => try skipBytes(data, cursor, 8),
        .length_delimited => {
            const raw_length = try readVarint(data, cursor);
            const length = std.math.cast(usize, raw_length) orelse return ProtoLiteError.InvalidLength;
            try skipBytes(data, cursor, length);
        },
        .fixed32 => try skipBytes(data, cursor, 4),
    }
}

fn skipBytes(data: []const u8, cursor: *usize, count: usize) ProtoLiteError!void {
    if (cursor.* + count > data.len) return ProtoLiteError.Truncated;
    cursor.* += count;
}

fn writeTag(out: *std.ArrayList(u8), allocator: std.mem.Allocator, field_number: u64, wire_type: WireType) !void {
    try writeVarint(out, allocator, (field_number << 3) | @backingInt(wire_type));
}

fn writeVarintField(out: *std.ArrayList(u8), allocator: std.mem.Allocator, field_number: u64, value: u64) !void {
    try writeTag(out, allocator, field_number, .varint);
    try writeVarint(out, allocator, value);
}

fn writeBytesField(out: *std.ArrayList(u8), allocator: std.mem.Allocator, field_number: u64, value: []const u8) !void {
    try writeTag(out, allocator, field_number, .length_delimited);
    try writeVarint(out, allocator, value.len);
    try out.appendSlice(allocator, value);
}

fn writeVarint(out: *std.ArrayList(u8), allocator: std.mem.Allocator, value: u64) !void {
    var remaining = value;
    while (remaining >= 0x80) {
        try out.append(allocator, @intCast((remaining & 0x7F) | 0x80));
        remaining >>= 7;
    }
    try out.append(allocator, @intCast(remaining));
}

test "transport instruction roundtrip and unknown field skipping" {
    const allocator = std.testing.allocator;

    const original: TransportInstruction = .{
        .protocol_version = 2,
        .old_num = 10,
        .new_num = 11,
        .ack_num = 9,
        .throwaway_num = 4,
        .diff = "AA",
        .chaff = "Z",
    };

    const encoded = try original.encode(allocator);
    defer allocator.free(encoded);

    const decoded = try TransportInstruction.decode(encoded);
    try std.testing.expectEqual(@as(?u32, 2), decoded.protocol_version);
    try std.testing.expectEqual(@as(?u64, 10), decoded.old_num);
    try std.testing.expectEqual(@as(?u64, 11), decoded.new_num);
    try std.testing.expectEqual(@as(?u64, 9), decoded.ack_num);
    try std.testing.expectEqual(@as(?u64, 4), decoded.throwaway_num);
    try std.testing.expectEqualStrings("AA", decoded.diff.?);
    try std.testing.expectEqualStrings("Z", decoded.chaff.?);

    var with_unknown: std.ArrayList(u8) = .empty;
    defer with_unknown.deinit(allocator);
    try with_unknown.appendSlice(allocator, &.{ 0xD0, 0x05, 0x01 }); // field 90 varint
    try with_unknown.appendSlice(allocator, encoded);

    const decoded_unknown = try TransportInstruction.decode(with_unknown.items);
    try std.testing.expectEqual(@as(?u64, 10), decoded_unknown.old_num);
}

test "host and user message encode/decode" {
    const allocator = std.testing.allocator;

    const host_instructions: []const HostInstruction = &.{
        .{ .host_bytes = "abc" },
        .{ .resize = .{ .width = 80, .height = 24 } },
        .{ .echo_ack = 42 },
    };

    const host_encoded = try encodeHostMessage(allocator, host_instructions);
    defer allocator.free(host_encoded);

    const host_decoded = try decodeHostMessage(allocator, host_encoded);
    defer freeHostInstructions(allocator, host_decoded);

    try std.testing.expectEqual(@as(usize, 3), host_decoded.len);
    try std.testing.expectEqualStrings("abc", host_decoded[0].host_bytes);
    try std.testing.expectEqual(@as(i32, 80), host_decoded[1].resize.width);
    try std.testing.expectEqual(@as(i32, 24), host_decoded[1].resize.height);
    try std.testing.expectEqual(@as(u64, 42), host_decoded[2].echo_ack);

    const user_instructions: []const UserInstruction = &.{
        .{ .keystroke = "ls\n" },
        .{ .resize = .{ .width = 120, .height = 50 } },
    };
    const user_encoded = try encodeUserMessage(allocator, user_instructions);
    defer allocator.free(user_encoded);
    try std.testing.expect(user_encoded.len > 0);
}
