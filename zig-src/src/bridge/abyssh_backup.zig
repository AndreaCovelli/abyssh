//! JNI bridge for encrypted backup container crypto.
//!
//! A container is a header, the length of what follows, then the AES-256-GCM
//! ciphertext and tag. The header names the format version, key derivation and
//! cipher, and holds the salt and IV; it is the GCM associated data, so none of it
//! can be changed. Version 2 derives the key with Argon2id from the UTF-8
//! passphrase, which the app NFC-normalizes first. Version 1 used PBKDF2-HMAC-SHA1
//! over the UTF-16BE passphrase (UTF-8 in the oldest files) and is only read.

const std = @import("std");

const c = @import("c");

const allocator = std.heap.c_allocator;

const FORMAT_V1: i32 = 1;
const FORMAT_V2: i32 = 2;
const KDF_ID_PBKDF2_HMAC_SHA1: i32 = 1;
const KDF_ID_ARGON2ID: i32 = 2;
const PBKDF2_ITERATIONS: i32 = 210_000;
// Argon2id with RFC 9106's second recommended setting: 3 passes over 64 MiB, one lane.
const ARGON2_T = 3;
const ARGON2_M_KIB = 64 * 1024;
const ARGON2_P = 1;
const CIPHER_ID_AES_256_GCM: i32 = 1;
const SALT_SIZE_BYTES: usize = 16;
const IV_SIZE_BYTES: usize = 12;
const MAX_BACKUP_SIZE_BYTES: usize = 32 * 1024 * 1024;
const CONTAINER_MAGIC: i32 = 0x4348424b; // CHBK
// Magic, version, KDF id, up to three KDF parameters, cipher id, then the salt and IV
// with their lengths.
const MAX_HEADER_SIZE: usize = 7 * 4 + 4 + SALT_SIZE_BYTES + 4 + IV_SIZE_BYTES;

const STATUS_OK: u8 = 0;
const STATUS_FORMAT_ERROR: u8 = 1;
const STATUS_INVALID_PASSPHRASE: u8 = 2;
const STATUS_INTERNAL_ERROR: u8 = 3;

const Aes256Gcm = std.crypto.aead.aes_gcm.Aes256Gcm;
const HmacSha1 = std.crypto.auth.hmac.HmacSha1;
const argon2 = std.crypto.pwhash.argon2;

const NativeError = error{
    InvalidInput,
    InvalidFormat,
    BackupTooLarge,
    InvalidPassphrase,
    CryptoFailure,
};

export fn Java_io_github_andreacovelli_abyssh_service_backup_NativeBackupBridge_nativeEncrypt(
    env: *c.JNIEnv,
    thiz: c.jobject,
    plaintext: c.jbyteArray,
    passphrase: c.jcharArray,
) callconv(.c) c.jbyteArray {
    _ = thiz;

    const plaintext_bytes = readJByteArray(env, plaintext) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid plaintext");
    };
    defer {
        std.crypto.secureZero(u8, plaintext_bytes);
        allocator.free(plaintext_bytes);
    }

    const passphrase_utf8 = readJCharArrayAsUtf8(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_utf8);
        allocator.free(passphrase_utf8);
    }

    const encrypted = encryptPayload(plaintext_bytes, passphrase_utf8) catch |err| {
        return statusFromError(env, err);
    };
    defer allocator.free(encrypted);

    return statusResultBytes(env, STATUS_OK, encrypted);
}

export fn Java_io_github_andreacovelli_abyssh_service_backup_NativeBackupBridge_nativeDecrypt(
    env: *c.JNIEnv,
    thiz: c.jobject,
    ciphertext: c.jbyteArray,
    passphrase: c.jcharArray,
) callconv(.c) c.jbyteArray {
    _ = thiz;

    const ciphertext_bytes = readJByteArray(env, ciphertext) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid ciphertext");
    };
    defer allocator.free(ciphertext_bytes);

    const passphrase_utf16be = readJCharArrayAsBigEndianBytes(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_utf16be);
        allocator.free(passphrase_utf16be);
    }

    const passphrase_utf8 = readJCharArrayAsUtf8(env, passphrase) catch {
        return statusResult(env, STATUS_FORMAT_ERROR, "Invalid passphrase");
    };
    defer {
        std.crypto.secureZero(u8, passphrase_utf8);
        allocator.free(passphrase_utf8);
    }

    const plaintext = decryptPayload(ciphertext_bytes, passphrase_utf16be, passphrase_utf8) catch |err| {
        return statusFromError(env, err);
    };
    defer {
        std.crypto.secureZero(u8, plaintext);
        allocator.free(plaintext);
    }

    return statusResultBytes(env, STATUS_OK, plaintext);
}

/// Encrypts [plaintext] into a version 2 container.
fn encryptPayload(plaintext: []const u8, passphrase_utf8: []const u8) NativeError![]u8 {
    var salt: [SALT_SIZE_BYTES]u8 = undefined;
    var iv: [IV_SIZE_BYTES]u8 = undefined;
    std.c.arc4random_buf(&salt, salt.len);
    std.c.arc4random_buf(&iv, iv.len);
    return seal(FORMAT_V2, plaintext, passphrase_utf8, salt, iv);
}

fn seal(
    version: i32,
    plaintext: []const u8,
    passphrase: []const u8,
    salt: [SALT_SIZE_BYTES]u8,
    iv: [IV_SIZE_BYTES]u8,
) NativeError![]u8 {
    if (plaintext.len > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;
    if (passphrase.len == 0) return error.InvalidInput;

    const header = buildHeader(version, salt, iv);
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);
    try deriveKey(version, passphrase, &salt, &key);

    const tag_len = Aes256Gcm.tag_length;
    const total_cipher_size = plaintext.len + tag_len;
    const total_size = header.len + 4 + total_cipher_size;
    if (total_size > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    const out = allocator.alloc(u8, total_size) catch return error.CryptoFailure;
    @memcpy(out[0..header.len], header.bytes());
    writeIntBe(out[header.len..][0..4], @intCast(total_cipher_size));
    const body = out[header.len + 4 ..];
    Aes256Gcm.encrypt(body[0..plaintext.len], body[plaintext.len..][0..tag_len], plaintext, header.bytes(), iv, key);
    return out;
}

/// Decrypts a container of either version. Version 1 is tried with the UTF-16BE
/// passphrase and then, for the oldest files, with UTF-8.
fn decryptPayload(container: []const u8, passphrase_utf16be: []const u8, passphrase_utf8: []const u8) NativeError![]u8 {
    if (container.len > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    var reader = Reader{ .bytes = container, .offset = 0 };
    const header = try readHeader(&reader);

    const ciphertext_size_i32 = reader.readInt() catch return error.InvalidFormat;
    if (ciphertext_size_i32 < 0) return error.InvalidFormat;
    const ciphertext_size: usize = @intCast(ciphertext_size_i32);
    if (ciphertext_size > MAX_BACKUP_SIZE_BYTES) return error.BackupTooLarge;

    const ciphertext_and_tag = reader.readSlice(ciphertext_size) catch return error.InvalidFormat;
    if (!reader.isEof()) return error.InvalidFormat;
    if (ciphertext_and_tag.len < Aes256Gcm.tag_length) return error.InvalidFormat;

    if (header.version == FORMAT_V1) {
        return open(&header, ciphertext_and_tag, passphrase_utf16be) catch |err| switch (err) {
            error.InvalidPassphrase => open(&header, ciphertext_and_tag, passphrase_utf8),
            else => err,
        };
    }
    return open(&header, ciphertext_and_tag, passphrase_utf8);
}

fn open(header: *const Header, ciphertext_and_tag: []const u8, passphrase: []const u8) NativeError![]u8 {
    if (passphrase.len == 0) return error.InvalidInput;

    const cipher_only_len = ciphertext_and_tag.len - Aes256Gcm.tag_length;
    const cipher_only = ciphertext_and_tag[0..cipher_only_len];
    var tag: [Aes256Gcm.tag_length]u8 = undefined;
    @memcpy(&tag, ciphertext_and_tag[cipher_only_len..]);

    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);
    try deriveKey(header.version, passphrase, &header.salt, &key);

    const plaintext = allocator.alloc(u8, cipher_only.len) catch return error.CryptoFailure;
    errdefer {
        std.crypto.secureZero(u8, plaintext);
        allocator.free(plaintext);
    }

    Aes256Gcm.decrypt(plaintext, cipher_only, tag, header.bytes(), header.iv, key) catch return error.InvalidPassphrase;

    return plaintext;
}

fn deriveKey(version: i32, passphrase: []const u8, salt: *const [SALT_SIZE_BYTES]u8, out_key: *[32]u8) NativeError!void {
    switch (version) {
        FORMAT_V1 => std.crypto.pwhash.pbkdf2(out_key, passphrase, salt, @intCast(PBKDF2_ITERATIONS), HmacSha1) catch
            return error.CryptoFailure,
        // One lane runs on the calling thread, so Argon2 never uses the Io.
        FORMAT_V2 => argon2.kdf(
            allocator,
            out_key,
            passphrase,
            salt,
            .{ .t = ARGON2_T, .m = ARGON2_M_KIB, .p = ARGON2_P },
            .argon2id,
            std.Io.failing,
        ) catch return error.CryptoFailure,
        else => return error.InvalidFormat,
    }
}

/// A container header, kept in its encoded form for use as associated data.
const Header = struct {
    version: i32,
    salt: [SALT_SIZE_BYTES]u8,
    iv: [IV_SIZE_BYTES]u8,
    encoded: [MAX_HEADER_SIZE]u8 = undefined,
    len: usize = 0,

    fn bytes(self: *const Header) []const u8 {
        return self.encoded[0..self.len];
    }

    fn putInt(self: *Header, value: i32) void {
        writeIntBe(self.encoded[self.len..][0..4], value);
        self.len += 4;
    }

    fn putBytes(self: *Header, value: []const u8) void {
        @memcpy(self.encoded[self.len..][0..value.len], value);
        self.len += value.len;
    }
};

fn buildHeader(version: i32, salt: [SALT_SIZE_BYTES]u8, iv: [IV_SIZE_BYTES]u8) Header {
    var header: Header = .{ .version = version, .salt = salt, .iv = iv };
    header.putInt(CONTAINER_MAGIC);
    header.putInt(version);
    switch (version) {
        FORMAT_V1 => {
            header.putInt(KDF_ID_PBKDF2_HMAC_SHA1);
            header.putInt(PBKDF2_ITERATIONS);
        },
        FORMAT_V2 => {
            header.putInt(KDF_ID_ARGON2ID);
            header.putInt(ARGON2_T);
            header.putInt(ARGON2_M_KIB);
            header.putInt(ARGON2_P);
        },
        else => unreachable,
    }
    header.putInt(CIPHER_ID_AES_256_GCM);
    header.putInt(SALT_SIZE_BYTES);
    header.putBytes(&salt);
    header.putInt(IV_SIZE_BYTES);
    header.putBytes(&iv);
    return header;
}

/// Reads a header and accepts it only if it matches, byte for byte, the header this
/// code writes for that version, salt and IV. So a file can't ask for other KDF
/// parameters, such as an Argon2 memory size that exhausts the device.
fn readHeader(reader: *Reader) NativeError!Header {
    const start = reader.offset;
    const magic = reader.readInt() catch return error.InvalidFormat;
    if (magic != CONTAINER_MAGIC) return error.InvalidFormat;
    const version = reader.readInt() catch return error.InvalidFormat;
    const kdf_fields: usize = switch (version) {
        FORMAT_V1 => 2,
        FORMAT_V2 => 4,
        else => return error.InvalidFormat,
    };
    // The KDF fields, the cipher id and the salt length.
    _ = reader.readSlice((kdf_fields + 2) * 4) catch return error.InvalidFormat;
    const salt = reader.readSlice(SALT_SIZE_BYTES) catch return error.InvalidFormat;
    _ = reader.readSlice(4) catch return error.InvalidFormat;
    const iv = reader.readSlice(IV_SIZE_BYTES) catch return error.InvalidFormat;

    const header = buildHeader(version, salt[0..SALT_SIZE_BYTES].*, iv[0..IV_SIZE_BYTES].*);
    if (!std.mem.eql(u8, header.bytes(), reader.bytes[start..reader.offset])) return error.InvalidFormat;
    return header;
}

const Reader = struct {
    bytes: []const u8,
    offset: usize,

    fn readInt(self: *Reader) !i32 {
        const slice = try self.readSlice(4);
        return readIntBe(slice);
    }

    fn readSlice(self: *Reader, size: usize) ![]const u8 {
        if (size > self.bytes.len - self.offset) return error.InvalidFormat;
        const out = self.bytes[self.offset .. self.offset + size];
        self.offset += size;
        return out;
    }

    fn isEof(self: *const Reader) bool {
        return self.offset == self.bytes.len;
    }
};

fn writeIntBe(dst: []u8, value: i32) void {
    const bits: u32 = @bitCast(value);
    dst[0] = @intCast((bits >> 24) & 0xff);
    dst[1] = @intCast((bits >> 16) & 0xff);
    dst[2] = @intCast((bits >> 8) & 0xff);
    dst[3] = @intCast(bits & 0xff);
}

fn readIntBe(src: []const u8) i32 {
    const value: u32 =
        (@as(u32, src[0]) << 24) |
        (@as(u32, src[1]) << 16) |
        (@as(u32, src[2]) << 8) |
        @as(u32, src[3]);
    return @bitCast(value);
}

fn readJByteArray(env: *c.JNIEnv, array: c.jbyteArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const size: usize = @intCast(len);
    const out = try allocator.alloc(u8, size);
    if (size == 0) return out;

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetByteArrayElements.?(env, array, &is_copy) orelse {
        allocator.free(out);
        return error.InvalidInput;
    };
    defer env.*.*.ReleaseByteArrayElements.?(env, array, ptr, c.JNI_ABORT);

    const src: [*]u8 = @ptrCast(ptr);
    @memcpy(out, src[0..size]);
    return out;
}

fn readJCharArrayAsBigEndianBytes(env: *c.JNIEnv, array: c.jcharArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const char_count: usize = @intCast(len);

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetCharArrayElements.?(env, array, &is_copy) orelse return error.InvalidInput;
    defer env.*.*.ReleaseCharArrayElements.?(env, array, ptr, c.JNI_ABORT);

    const utf16: [*]u16 = @ptrCast(ptr);
    const out = try allocator.alloc(u8, char_count * 2);
    var i: usize = 0;
    while (i < char_count) : (i += 1) {
        const unit = utf16[i];
        out[(i * 2)] = @intCast((unit >> 8) & 0xff);
        out[(i * 2) + 1] = @intCast(unit & 0xff);
    }
    return out;
}

fn readJCharArrayAsUtf8(env: *c.JNIEnv, array: c.jcharArray) ![]u8 {
    if (array == null) return error.InvalidInput;
    const len = env.*.*.GetArrayLength.?(env, array);
    if (len < 0) return error.InvalidInput;
    const char_count: usize = @intCast(len);

    var is_copy: c.jboolean = c.JNI_FALSE;
    const ptr = env.*.*.GetCharArrayElements.?(env, array, &is_copy) orelse return error.InvalidInput;
    defer env.*.*.ReleaseCharArrayElements.?(env, array, ptr, c.JNI_ABORT);

    // Convert into a worst-case buffer rather than a growing list, which would leave
    // copies of the passphrase behind each time it reallocated.
    const utf16: [*]u16 = @ptrCast(ptr);
    const scratch = try allocator.alloc(u8, char_count * 3);
    defer {
        std.crypto.secureZero(u8, scratch);
        allocator.free(scratch);
    }
    const len_utf8 = std.unicode.utf16LeToUtf8(scratch, utf16[0..char_count]) catch return error.InvalidInput;
    return allocator.dupe(u8, scratch[0..len_utf8]);
}

fn statusFromError(env: *c.JNIEnv, err: anyerror) c.jbyteArray {
    return switch (err) {
        error.InvalidPassphrase => statusResult(env, STATUS_INVALID_PASSPHRASE, "Invalid backup passphrase"),
        error.InvalidFormat => statusResult(env, STATUS_FORMAT_ERROR, "Invalid backup file"),
        error.BackupTooLarge => statusResult(env, STATUS_FORMAT_ERROR, "Backup file is too large"),
        else => statusResult(env, STATUS_INTERNAL_ERROR, "Native backup failure"),
    };
}

fn statusResult(env: *c.JNIEnv, status: u8, message: []const u8) c.jbyteArray {
    return statusResultBytes(env, status, message);
}

/// Copies the payload straight into the Java array, so decrypted data leaves no
/// unwiped copy in native memory.
fn statusResultBytes(env: *c.JNIEnv, status: u8, payload: []const u8) c.jbyteArray {
    const total = 1 + payload.len;
    const out = env.*.*.NewByteArray.?(env, @intCast(total)) orelse return null;
    const status_byte: [1]u8 = .{status};
    env.*.*.SetByteArrayRegion.?(env, out, 0, 1, @ptrCast(&status_byte));
    if (payload.len > 0) {
        env.*.*.SetByteArrayRegion.?(env, out, 1, @intCast(payload.len), @ptrCast(payload.ptr));
    }
    return out;
}

const test_salt = [SALT_SIZE_BYTES]u8{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
const test_iv = [IV_SIZE_BYTES]u8{ 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27 };

test "version 1 key matches the JVM-compatible UTF-16BE PBKDF2 vector, ascii" {
    const passphrase = "\x00p\x00a\x00s\x00s\x00w\x00o\x00r\x00d";
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);

    try deriveKey(FORMAT_V1, passphrase, &test_salt, &key);
    const expected = [_]u8{ 0xde, 0x7c, 0x12, 0x36, 0x4e, 0xea, 0xd1, 0x9a, 0xc7, 0x06, 0xb0, 0xa4, 0xb9, 0x88, 0xf0, 0x60, 0x11, 0xa4, 0x82, 0x7d, 0x23, 0x66, 0x98, 0xfc, 0xd6, 0x5e, 0x57, 0x89, 0xdb, 0x5a, 0x74, 0xc7 };
    try std.testing.expectEqualSlices(u8, &expected, &key);
}

test "version 1 key matches the JVM-compatible UTF-16BE PBKDF2 vector, unicode" {
    const passphrase = "\x00p\x00\xe1\x00s\x00s\x00w\xd8=\xdd\x10\x00r\x00d";
    var key: [32]u8 = undefined;
    defer std.crypto.secureZero(u8, &key);

    try deriveKey(FORMAT_V1, passphrase, &test_salt, &key);
    const expected = [_]u8{ 0x7e, 0x67, 0xf2, 0xf5, 0x11, 0x6a, 0xba, 0x42, 0xe3, 0x47, 0xcf, 0x7e, 0x08, 0x95, 0x21, 0x46, 0x86, 0xb4, 0xe2, 0x12, 0xc0, 0xe8, 0xf1, 0x02, 0x16, 0xc1, 0x5c, 0x81, 0xcf, 0x97, 0x32, 0x22 };
    try std.testing.expectEqualSlices(u8, &expected, &key);
}

test "version 1 decrypts with the legacy UTF-8 and the UTF-16BE passphrase bytes" {
    const plaintext = "compatibility-check";
    const passphrase_utf8 = "awdsefrr";
    const passphrase_utf16be = "\x00a\x00w\x00d\x00s\x00e\x00f\x00r\x00r";

    const legacy_container = try seal(FORMAT_V1, plaintext, passphrase_utf8, test_salt, test_iv);
    defer allocator.free(legacy_container);
    const new_container = try seal(FORMAT_V1, plaintext, passphrase_utf16be, test_salt, test_iv);
    defer allocator.free(new_container);

    const legacy_dec = try decryptPayload(legacy_container, passphrase_utf16be, passphrase_utf8);
    defer allocator.free(legacy_dec);
    try std.testing.expectEqualStrings(plaintext, legacy_dec);

    const new_dec = try decryptPayload(new_container, passphrase_utf16be, passphrase_utf8);
    defer allocator.free(new_dec);
    try std.testing.expectEqualStrings(plaintext, new_dec);
}

test "version 2 round-trips with the UTF-8 passphrase and rejects other KDF parameters" {
    const plaintext = "argon2id-check";
    const container = try seal(FORMAT_V2, plaintext, "awdsefrr", test_salt, test_iv);
    defer allocator.free(container);

    const dec = try decryptPayload(container, "\x00a\x00w\x00d\x00s\x00e\x00f\x00r\x00r", "awdsefrr");
    defer allocator.free(dec);
    try std.testing.expectEqualStrings(plaintext, dec);

    try std.testing.expectError(error.InvalidPassphrase, decryptPayload(container, "x", "wrong-pass"));

    const tampered = try allocator.dupe(u8, container);
    defer allocator.free(tampered);
    tampered[19] +%= 1; // the Argon2 memory size
    try std.testing.expectError(error.InvalidFormat, decryptPayload(tampered, "x", "awdsefrr"));
}
