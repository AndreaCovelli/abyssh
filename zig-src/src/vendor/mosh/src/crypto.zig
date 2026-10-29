//! Crypto helpers for mosh transport envelopes (AES-128 OCB).

const std = @import("std");

pub const CryptoError = error{
    InvalidPrintableKeyLength,
    InvalidPrintableKeyEncoding,
    InvalidDatagram,
    AuthenticationFailed,
};

pub const MoshBase64Key = struct {
    raw: [16]u8,

    pub fn parsePrintable(printable: []const u8) CryptoError!MoshBase64Key {
        if (printable.len != 22) return CryptoError.InvalidPrintableKeyLength;

        var out: [16]u8 = undefined;
        var out_index: usize = 0;
        var bit_buffer: u32 = 0;
        var bit_count: u5 = 0;

        for (printable) |ch| {
            const value = base64Value(ch) orelse return CryptoError.InvalidPrintableKeyEncoding;
            bit_buffer = (bit_buffer << 6) | value;
            bit_count += 6;

            while (bit_count >= 8 and out_index < out.len) {
                bit_count -= 8;
                out[out_index] = @intCast((bit_buffer >> bit_count) & 0xFF);
                out_index += 1;
            }
        }

        if (out_index != out.len) return CryptoError.InvalidPrintableKeyEncoding;
        return .{ .raw = out };
    }
};

fn base64Value(ch: u8) ?u32 {
    return switch (ch) {
        'A'...'Z' => ch - 'A',
        'a'...'z' => 26 + (ch - 'a'),
        '0'...'9' => 52 + (ch - '0'),
        '+', '-' => 62,
        '/', '_' => 63,
        else => null,
    };
}

pub fn nonceFrom(message_id: u32, sequence: u64) [12]u8 {
    var nonce: [12]u8 = undefined;
    std.mem.writeInt(u32, nonce[0..4], message_id, .big);
    std.mem.writeInt(u64, nonce[4..12], sequence, .big);
    return nonce;
}

pub const OpenedDatagram = struct {
    directional_sequence: u64,
    plaintext: []u8,
};

pub const OCBTransportCipher = struct {
    key: [16]u8,

    pub fn seal(self: OCBTransportCipher, allocator: std.mem.Allocator, directional_sequence: u64, plaintext: []const u8) ![]u8 {
        const nonce = nonceFrom(0, directional_sequence);
        var tag: [16]u8 = undefined;

        const ciphertext = try allocator.alloc(u8, plaintext.len);
        errdefer allocator.free(ciphertext);
        std.crypto.aead.aes_ocb.Aes128Ocb.encrypt(ciphertext, &tag, plaintext, &.{}, nonce, self.key);

        const out = try allocator.alloc(u8, 8 + ciphertext.len + tag.len);
        std.mem.writeInt(u64, out[0..8], directional_sequence, .big);
        @memcpy(out[8 .. 8 + ciphertext.len], ciphertext);
        @memcpy(out[8 + ciphertext.len ..], &tag);

        allocator.free(ciphertext);
        return out;
    }

    pub fn open(self: OCBTransportCipher, allocator: std.mem.Allocator, datagram: []const u8) CryptoError!OpenedDatagram {
        if (datagram.len < 8 + 16) return CryptoError.InvalidDatagram;

        const directional_sequence = std.mem.readInt(u64, datagram[0..8], .big);
        const ciphertext = datagram[8 .. datagram.len - 16];

        var tag: [16]u8 = undefined;
        @memcpy(&tag, datagram[datagram.len - 16 ..]);

        const nonce = nonceFrom(0, directional_sequence);
        const plaintext = allocator.alloc(u8, ciphertext.len) catch return CryptoError.InvalidDatagram;
        errdefer allocator.free(plaintext);

        std.crypto.aead.aes_ocb.Aes128Ocb.decrypt(plaintext, ciphertext, tag, &.{}, nonce, self.key) catch {
            return CryptoError.AuthenticationFailed;
        };

        return .{
            .directional_sequence = directional_sequence,
            .plaintext = plaintext,
        };
    }
};

test "base64 key parsing supports standard and url-safe chars" {
    const key = try MoshBase64Key.parsePrintable("AAECAwQFBgcICQoLDA0ODw");
    try std.testing.expectEqualSlices(u8, &[_]u8{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 }, &key.raw);

    try std.testing.expectError(CryptoError.InvalidPrintableKeyLength, MoshBase64Key.parsePrintable("short"));
}

test "nonce from message and sequence" {
    const nonce = nonceFrom(0x11223344, 0x5566778899AABBCC);
    try std.testing.expectEqualSlices(u8, &[_]u8{ 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC }, &nonce);
}

test "aes-ocb known vector empty plaintext" {
    const key: [16]u8 = .{ 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F };
    const nonce: [12]u8 = .{ 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B };

    var tag: [16]u8 = undefined;
    std.crypto.aead.aes_ocb.Aes128Ocb.encrypt(&.{}, &tag, &.{}, &.{}, nonce, key);

    try std.testing.expectEqualSlices(u8, &[_]u8{ 0x19, 0x7B, 0x9C, 0x3C, 0x44, 0x1D, 0x3C, 0x83, 0xEA, 0xFB, 0x2B, 0xEF, 0x63, 0x3B, 0x91, 0x82 }, &tag);
}

test "transport cipher seal/open and authentication failure" {
    const allocator = std.testing.allocator;
    const cipher: OCBTransportCipher = .{ .key = [_]u8{ 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42, 0x42 } };

    const sealed = try cipher.seal(allocator, 7, "hello");
    defer allocator.free(sealed);

    const opened = try cipher.open(allocator, sealed);
    defer allocator.free(opened.plaintext);

    try std.testing.expectEqual(@as(u64, 7), opened.directional_sequence);
    try std.testing.expectEqualStrings("hello", opened.plaintext);

    var tampered = try allocator.dupe(u8, sealed);
    defer allocator.free(tampered);
    tampered[tampered.len - 1] ^= 0xFF;

    try std.testing.expectError(CryptoError.AuthenticationFailed, cipher.open(allocator, tampered));
}
