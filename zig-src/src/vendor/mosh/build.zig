const std = @import("std");
const Translator = @import("translate_c").Translator;

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const libc_file = b.option(
        std.Build.LazyPath,
        "libc_file",
        "libc paths file for cross builds, such as one for an Android NDK sysroot",
    );

    // Zig 0.17 removed @cImport, so zlib's declarations come from translate-c.
    const zlib_c: Translator = .init(b.dependency("translate_c", .{}), .{
        .c_source_file = b.path("src/zlib_c.h"),
        .target = target,
        .optimize = optimize,
        .libc_file = libc_file,
    });
    if (target.result.abi.isAndroid()) {
        // Bionic declares a second, overloaded ioctl for clang, which
        // translate-c rejects as a redefinition. This macro drops the overload.
        zlib_c.defineCMacro("BIONIC_IOCTL_NO_SIGNEDNESS_OVERLOAD", "1");
    }

    const mosh = b.addModule("mosh", .{
        .root_source_file = b.path("src/root.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    mosh.addImport("zlib_c", zlib_c.mod);
}
