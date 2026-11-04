//! JNI bridge for encrypted backup containers. The format is in backup_container.zig.

const std = @import("std");

const c = @import("c");
const container = @import("backup_container.zig");

const allocator = std.heap.c_allocator;

const STATUS_OK: u8 = 0;
const STATUS_FORMAT_ERROR: u8 = 1;
const STATUS_INVALID_PASSPHRASE: u8 = 2;
const STATUS_INTERNAL_ERROR: u8 = 3;

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

    const encrypted = container.encrypt(plaintext_bytes, passphrase_utf8) catch |err| {
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

    const plaintext = container.decrypt(ciphertext_bytes, passphrase_utf16be, passphrase_utf8) catch |err| {
        return statusFromError(env, err);
    };
    defer {
        std.crypto.secureZero(u8, plaintext);
        allocator.free(plaintext);
    }

    return statusResultBytes(env, STATUS_OK, plaintext);
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
