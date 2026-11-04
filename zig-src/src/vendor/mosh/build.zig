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
    const bundled_zlib = b.option(
        bool,
        "bundled_zlib",
        "Build zlib from source and link it, for hosts without zlib headers (tests)",
    ) orelse false;

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

    if (bundled_zlib) {
        if (b.lazyDependency("zlib", .{})) |zlib_dep| {
            // Zig strips the tarball's top-level zlib-1.3.1 directory.
            const zlib_src = zlib_dep.path(".");
            zlib_c.addIncludePath(zlib_src);
            mosh.linkLibrary(buildZlib(b, zlib_src, target, optimize));
        }
    }
}

fn buildZlib(
    b: *std.Build,
    src: std.Build.LazyPath,
    target: std.Build.ResolvedTarget,
    optimize: std.lang.Optimize,
) *std.Build.Step.Compile {
    const lib = b.addLibrary(.{
        .name = "z",
        .linkage = .static,
        .root_module = b.createModule(.{
            .target = target,
            .optimize = optimize,
            .link_libc = true,
        }),
    });
    lib.root_module.addIncludePath(src);
    lib.root_module.addCSourceFiles(.{
        .root = src,
        .files = &.{
            "adler32.c", "compress.c", "crc32.c",   "deflate.c",
            "gzclose.c", "gzlib.c",    "gzread.c",  "gzwrite.c",
            "infback.c", "inffast.c",  "inflate.c", "inftrees.c",
            "trees.c",   "uncompr.c",  "zutil.c",
        },
        .flags = &.{ "-DHAVE_SYS_TYPES_H", "-DHAVE_STDINT_H", "-DHAVE_STDDEF_H", "-DZ_HAVE_UNISTD_H" },
    });
    return lib;
}
