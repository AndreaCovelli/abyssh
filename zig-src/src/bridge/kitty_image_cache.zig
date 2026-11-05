//! Brings back Kitty images the terminal dropped to stay within its storage limit.
//!
//! Ghostty evicts the oldest images when the limit is reached, and clearing the screen
//! deletes the images nothing shows. A program that placed an image can't tell: Kitty
//! clients silence replies, so it sends the placement again and gets nothing on screen.
//! This keeps the compressed PNG of each stored image, which is a small fraction of the
//! decoded size. When a placement names an image that is gone, it decodes the PNG again
//! and stores the image before Ghostty runs the placement, so the program sees what it
//! would have seen had the terminal kept the image.
//!
//! Only PNGs are kept, since the decoder hook is where the compressed bytes are visible,
//! and animated images are left out, since their frames aren't in the PNG. Those fail as
//! they always have, and so does a placement of an image the program deleted.

const std = @import("std");
const ghostty = @import("ghostty-vt");

const Allocator = std.mem.Allocator;
const Action = ghostty.TerminalStream.Action;
const Handler = ghostty.TerminalStream.Handler;
const Terminal = ghostty.Terminal;
const graphics = ghostty.kitty.graphics;
const Command = graphics.Command;
const Image = graphics.Image;

/// The most PNG bytes one terminal keeps, across its screens.
const default_max_bytes = 32 * 1024 * 1024;

/// The most images one terminal keeps PNGs for. Each command that changes the images
/// walks the entries, and small images could otherwise add up to many thousands.
const max_entries = 256;

/// The longest control data read from a command, which real commands stay far below.
/// A longer one still reaches Ghostty, but restores and forgets nothing.
const max_header_bytes = 512;

// The decoder hook takes no terminal, and sessions decode on different threads, so this
// says which cache the PNG being decoded on this thread belongs to. It is set only while
// a command runs, so terminals that share a thread, as tmux panes can, stay apart.
threadlocal var capturing: ?*ImageCache = null;

/// Hands a PNG about to be decoded to the cache of the command running on this thread.
/// Call it from `ghostty.sys.decode_png`. It does nothing outside a command.
pub fn capturePng(data: []const u8) void {
    if (capturing) |cache| cache.capture(data);
}

pub const ImageCache = struct {
    alloc: Allocator,
    max_bytes: usize = default_max_bytes,
    entries: std.AutoHashMapUnmanaged(Key, Entry) = .empty,
    total_bytes: usize = 0,
    // Ordered stamps for finding the entry used longest ago.
    last_use: u64 = 0,
    header: [max_header_bytes]u8 = undefined,
    header_len: usize = 0,
    header_state: enum { reading, complete, too_long } = .reading,
    // The PNG the running command decoded, until the command ends.
    pending_png: ?[]u8 = null,

    /// Images belong to a screen, and a removed screen's images are gone with it. Its
    /// next generation has different keys, so a stale entry can never match.
    const Key = struct {
        screen: ghostty.ScreenSet.Key,
        screen_generation: usize,
        image_id: u32,
    };

    const Entry = struct {
        png: []u8,
        // The stored image without its pixels. `transmission` tells it apart from a
        // later image with the same ID.
        image: Image,
        used: u64,
        // Whether the image was stored when the running delete command began.
        present: bool = false,
    };

    /// `alloc` must be the terminal's allocator, since restored images are its to free.
    pub fn init(alloc: Allocator) ImageCache {
        return .{ .alloc = alloc };
    }

    pub fn deinit(self: *ImageCache) void {
        self.clear();
        self.entries.deinit(self.alloc);
    }

    /// Passes a stream action to `inner`, restoring and recording images around it.
    pub fn vt(
        self: *ImageCache,
        terminal: *Terminal,
        inner: *Handler,
        comptime action: Action.Tag,
        value: Action.Value(action),
    ) void {
        switch (action) {
            .apc_start => self.startHeader(),
            .apc_put => self.feedHeader(&.{value}),
            .apc_put_slice => self.feedHeader(value.bytes),
            // An aborted command doesn't run, so there is nothing to do around it.
            .apc_end => if (value.terminated) {
                self.runCommand(terminal, inner, value);
                return;
            },
            else => {},
        }
        inner.vt(action, value);
        if (action == .full_reset) self.clear();
    }

    fn runCommand(self: *ImageCache, terminal: *Terminal, inner: *Handler, value: Action.Value(.apc_end)) void {
        var command = self.parseHeader();
        defer if (command) |*cmd| cmd.deinit(self.alloc);
        if (command) |*cmd| self.beforeCommand(terminal, cmd);

        const generation = terminal.screens.active.kitty_images.generation;
        capturing = self;
        inner.vt(.apc_end, value);
        capturing = null;

        self.recordPending(terminal, generation);
        if (command) |*cmd| self.forgetNamedImages(terminal, cmd);
        if (terminal.screens.active.kitty_images.generation != generation) self.dropOutdated(terminal);
    }

    fn startHeader(self: *ImageCache) void {
        self.header_len = 0;
        self.header_state = .reading;
    }

    fn feedHeader(self: *ImageCache, bytes: []const u8) void {
        if (self.header_state != .reading) return;
        const end = std.mem.indexOfScalar(u8, bytes, ';') orelse bytes.len;
        if (self.header_len + end > self.header.len) {
            self.header_state = .too_long;
            return;
        }
        @memcpy(self.header[self.header_len..][0..end], bytes[0..end]);
        self.header_len += end;
        if (end < bytes.len) self.header_state = .complete;
    }

    /// The command being ended, without its payload, or null when it isn't a valid Kitty
    /// graphics command. Those start with G.
    fn parseHeader(self: *ImageCache) ?Command {
        if (self.header_state == .too_long or self.header_len == 0 or self.header[0] != 'G') return null;
        return graphics.CommandParser.parseString(self.alloc, self.header[1..self.header_len]) catch null;
    }

    fn beforeCommand(self: *ImageCache, terminal: *Terminal, command: *const Command) void {
        switch (command.control) {
            .display => |display| self.restoreIfMissing(terminal, display.image_id, display.image_number),
            .transmit => |transmission| self.forgetReplaced(terminal, transmission.image_id),
            .transmit_and_display => |both| self.forgetReplaced(terminal, both.transmission.image_id),
            .delete => {
                const storage = &terminal.screens.active.kitty_images;
                var it = self.activeEntries(terminal);
                while (it.next()) |kv| kv.value_ptr.present = storage.images.contains(kv.key_ptr.image_id);
            },
            else => {},
        }
    }

    /// Ghostty deletes the image with this ID when a new transmission begins, whether or
    /// not the new one succeeds. Later chunks of a transmission don't delete anything.
    fn forgetReplaced(self: *ImageCache, terminal: *Terminal, image_id: u32) void {
        if (image_id == 0 or terminal.screens.active.kitty_images.loading != null) return;
        self.remove(activeKey(terminal, image_id));
    }

    fn restoreIfMissing(self: *ImageCache, terminal: *Terminal, image_id: u32, image_number: u32) void {
        // Ghostty rejects a command with both, and one with neither names nothing.
        if ((image_id == 0) == (image_number == 0)) return;
        const storage = &terminal.screens.active.kitty_images;
        const present = if (image_id != 0) storage.imageById(image_id) else storage.imageByNumber(image_number);
        const entry_id = if (present) |image| image.id else if (image_id != 0) image_id else self.newestWithNumber(terminal, image_number) orelse return;

        const key = activeKey(terminal, entry_id);
        if (present == null) self.restore(terminal, key);
        if (self.entries.getPtr(key)) |entry| entry.used = self.nextUse();
    }

    /// The image the number would have meant had the terminal kept its images.
    fn newestWithNumber(self: *ImageCache, terminal: *Terminal, image_number: u32) ?u32 {
        var newest: ?Entry = null;
        var it = self.activeEntries(terminal);
        while (it.next()) |kv| {
            const image = kv.value_ptr.image;
            if (image.number != image_number) continue;
            if (newest == null or image.transmission > newest.?.image.transmission) newest = kv.value_ptr.*;
        }
        return if (newest) |entry| entry.image.id else null;
    }

    fn restore(self: *ImageCache, terminal: *Terminal, key: Key) void {
        const entry = self.entries.getPtr(key) orelse return;
        const decode = ghostty.sys.decode_png orelse return;
        const decoded = decode(self.alloc, entry.png) catch return;

        // The same bytes decoded to this size before, but this stores what comes back.
        const image_bytes = @as(usize, entry.image.width) * entry.image.height * 4;
        if (decoded.width != entry.image.width or decoded.height != entry.image.height or decoded.data.len != image_bytes) {
            self.alloc.free(decoded.data);
            self.remove(key);
            return;
        }

        var image = entry.image;
        image.data = .{ .complete = decoded.data };
        const storage = &terminal.screens.active.kitty_images;
        storage.addImage(terminal.io(), self.alloc, terminal.screens.active, image) catch {
            self.alloc.free(decoded.data);
            return;
        };
        entry.image.transmission = storage.imageById(key.image_id).?.transmission;
    }

    fn capture(self: *ImageCache, data: []const u8) void {
        self.freePending();
        if (data.len > self.max_bytes) return;
        self.pending_png = self.alloc.dupe(u8, data) catch null;
    }

    fn freePending(self: *ImageCache) void {
        if (self.pending_png) |png| self.alloc.free(png);
        self.pending_png = null;
    }

    /// Files the PNG the command decoded under the image the command stored. A query
    /// decodes a PNG and stores nothing, and an animation frame goes into an image that
    /// already exists, so neither has a new transmission and neither gets filed.
    fn recordPending(self: *ImageCache, terminal: *Terminal, generation: u64) void {
        const png = self.pending_png orelse return;
        self.pending_png = null;

        var stored: ?Image = null;
        var it = terminal.screens.active.kitty_images.images.valueIterator();
        while (it.next()) |image| {
            if (image.transmission > generation) stored = image.*;
        }
        // An image sent without an ID or number can't be placed again.
        if (stored == null or stored.?.metadata.implicit_id) {
            self.alloc.free(png);
            return;
        }
        const image = stored.?;

        const key = activeKey(terminal, image.id);
        self.remove(key);
        self.makeRoom(png.len);
        var kept = image.withoutData();
        kept.animation = null;
        self.entries.put(self.alloc, key, .{ .png = png, .image = kept, .used = self.nextUse() }) catch {
            self.alloc.free(png);
            return;
        };
        self.total_bytes += png.len;
    }

    fn makeRoom(self: *ImageCache, bytes: usize) void {
        while (self.total_bytes + bytes > self.max_bytes or self.entries.count() >= max_entries) {
            var oldest: ?Key = null;
            var oldest_used: u64 = std.math.maxInt(u64);
            var it = self.entries.iterator();
            while (it.next()) |kv| {
                if (kv.value_ptr.used < oldest_used) {
                    oldest = kv.key_ptr.*;
                    oldest_used = kv.value_ptr.used;
                }
            }
            self.remove(oldest orelse return);
        }
    }

    /// Forgets what the program itself gave up on: images a delete command removed or
    /// named, including ones already evicted, and images a failed animation command
    /// named, which the program now believes have frames a restored image wouldn't.
    fn forgetNamedImages(self: *ImageCache, terminal: *Terminal, command: *const Command) void {
        switch (command.control) {
            .delete, .transmit_animation_frame, .control_animation, .compose_animation => {},
            else => return,
        }
        const storage = &terminal.screens.active.kitty_images;
        const ids = command.control.identifiers();
        var it = self.activeEntries(terminal);
        while (it.next()) |kv| {
            const id = kv.key_ptr.image_id;
            if (storage.images.contains(id)) continue;
            const entry = kv.value_ptr;
            const forget = switch (command.control) {
                .delete => |delete| entry.present or deleteNames(delete.action, entry.image),
                else => ids.image_id == id or (ids.image_number != 0 and ids.image_number == entry.image.number),
            };
            if (forget) self.remove(kv.key_ptr.*);
        }
    }

    fn deleteNames(action: @FieldType(@FieldType(Command, "control"), "delete").Action, image: Image) bool {
        return switch (action) {
            .all => |delete_images| delete_images,
            .id => |v| v.delete and v.image_id == image.id,
            .newest => |v| v.delete and image.number != 0 and v.image_number == image.number,
            .range => |v| v.delete and v.first <= image.id and image.id <= v.last,
            // These pick images by what is on screen, and an evicted image isn't.
            else => false,
        };
    }

    /// Drops entries that no longer describe a stored image: the screen they belonged to
    /// is gone, the ID now holds another image, or the image became animated.
    fn dropOutdated(self: *ImageCache, terminal: *Terminal) void {
        const storage = &terminal.screens.active.kitty_images;
        var it = self.entries.iterator();
        while (it.next()) |kv| {
            const key = kv.key_ptr.*;
            const outdated = if (key.screen_generation != terminal.screens.generation(key.screen))
                true
            else if (key.screen != terminal.screens.active_key)
                false
            else if (storage.imageById(key.image_id)) |image|
                image.transmission != kv.value_ptr.image.transmission or image.animation != null
            else
                false;
            if (outdated) self.remove(key);
        }
    }

    fn clear(self: *ImageCache) void {
        self.freePending();
        var it = self.entries.valueIterator();
        while (it.next()) |entry| self.alloc.free(entry.png);
        self.entries.clearRetainingCapacity();
        self.total_bytes = 0;
    }

    fn remove(self: *ImageCache, key: Key) void {
        const removed = self.entries.fetchRemove(key) orelse return;
        self.total_bytes -= removed.value.png.len;
        self.alloc.free(removed.value.png);
    }

    fn nextUse(self: *ImageCache) u64 {
        self.last_use += 1;
        return self.last_use;
    }

    fn activeKey(terminal: *const Terminal, image_id: u32) Key {
        const screen = terminal.screens.active_key;
        return .{
            .screen = screen,
            .screen_generation = terminal.screens.generation(screen),
            .image_id = image_id,
        };
    }

    /// Walks the entries of the active screen. Removing the current entry is allowed.
    const ActiveEntries = struct {
        inner: std.AutoHashMapUnmanaged(Key, Entry).Iterator,
        screen: ghostty.ScreenSet.Key,
        screen_generation: usize,

        fn next(self: *ActiveEntries) ?std.AutoHashMapUnmanaged(Key, Entry).Entry {
            while (self.inner.next()) |kv| {
                if (kv.key_ptr.screen == self.screen and kv.key_ptr.screen_generation == self.screen_generation) return kv;
            }
            return null;
        }
    };

    fn activeEntries(self: *ImageCache, terminal: *const Terminal) ActiveEntries {
        const screen = terminal.screens.active_key;
        return .{
            .inner = self.entries.iterator(),
            .screen = screen,
            .screen_generation = terminal.screens.generation(screen),
        };
    }
};

const testing = std.testing;
const test_alloc = testing.allocator;

// The same zigimg calls as the app's decoder, which logs through Android.
fn testDecode(alloc: Allocator, data: []const u8) ghostty.sys.DecodeError!ghostty.sys.Image {
    const zigimg = @import("zigimg");
    capturePng(data);
    var stream = zigimg.io.ReadStream.initMemory(data);
    var image = zigimg.formats.png.PNG.readImage(alloc, &stream) catch return error.InvalidData;
    defer image.deinit(alloc);
    image.convert(alloc, .rgba32) catch return error.InvalidData;
    return .{
        .width = @intCast(image.width),
        .height = @intCast(image.height),
        .data = try alloc.dupe(u8, image.rawBytes()),
    };
}

/// A real PNG of one color, its pixels in stored (uncompressed) deflate blocks.
fn makePng(alloc: Allocator, width: u32, height: u32, value: u8) ![]u8 {
    const row_bytes = @as(usize, width) * 4 + 1;
    const raw = try alloc.alloc(u8, row_bytes * height);
    defer alloc.free(raw);
    @memset(raw, value);
    for (0..height) |row| raw[row * row_bytes] = 0;

    var idat: std.ArrayList(u8) = .empty;
    defer idat.deinit(alloc);
    try idat.appendSlice(alloc, "\x78\x01");
    var rest = raw;
    while (true) {
        const len: u16 = @intCast(@min(rest.len, 0xffff));
        try idat.append(alloc, if (rest.len == len) 1 else 0);
        try idat.appendSlice(alloc, &std.mem.toBytes(std.mem.nativeToLittle(u16, len)));
        try idat.appendSlice(alloc, &std.mem.toBytes(std.mem.nativeToLittle(u16, ~len)));
        try idat.appendSlice(alloc, rest[0..len]);
        rest = rest[len..];
        if (rest.len == 0) break;
    }
    try idat.appendSlice(alloc, &std.mem.toBytes(std.mem.nativeToBig(u32, std.hash.Adler32.hash(raw))));

    var ihdr: [13]u8 = undefined;
    std.mem.writeInt(u32, ihdr[0..4], width, .big);
    std.mem.writeInt(u32, ihdr[4..8], height, .big);
    @memcpy(ihdr[8..13], &[_]u8{ 8, 6, 0, 0, 0 });

    var png: std.ArrayList(u8) = .empty;
    errdefer png.deinit(alloc);
    try png.appendSlice(alloc, "\x89PNG\r\n\x1a\n");
    try appendChunk(alloc, &png, "IHDR", &ihdr);
    try appendChunk(alloc, &png, "IDAT", idat.items);
    try appendChunk(alloc, &png, "IEND", "");
    return png.toOwnedSlice(alloc);
}

fn appendChunk(alloc: Allocator, png: *std.ArrayList(u8), kind: *const [4]u8, data: []const u8) !void {
    try png.appendSlice(alloc, &std.mem.toBytes(std.mem.nativeToBig(u32, @intCast(data.len))));
    try png.appendSlice(alloc, kind);
    try png.appendSlice(alloc, data);
    var crc: std.hash.Crc32 = .init();
    crc.update(kind);
    crc.update(data);
    try png.appendSlice(alloc, &std.mem.toBytes(std.mem.nativeToBig(u32, crc.final())));
}

/// A terminal wired like `AbysshStreamHandler` in abyssh_snapshot.zig.
const TestTerminal = struct {
    const TestStream = ghostty.Stream(*Wrapper);

    const Wrapper = struct {
        owner: *TestTerminal,
        inner: Handler,

        pub fn deinit(self: *Wrapper) void {
            self.inner.deinit();
        }

        pub fn vt(
            self: *Wrapper,
            comptime action: TestStream.Action.Tag,
            value: TestStream.Action.Value(action),
        ) void {
            if (self.owner.restores) {
                self.owner.cache.vt(&self.owner.terminal, &self.inner, action, value);
            } else {
                self.inner.vt(action, value);
            }
        }
    };

    alloc: Allocator,
    terminal: Terminal,
    cache: ImageCache,
    restores: bool,
    replies: std.ArrayList(u8) = .empty,
    handler: Wrapper = undefined,
    stream: TestStream = undefined,

    fn create(alloc: Allocator, limit: usize, restores: bool) !*TestTerminal {
        ghostty.sys.decode_png = testDecode;
        // These tests send commands Ghostty warns about on purpose.
        testing.log_level = .err;
        const self = try alloc.create(TestTerminal);
        errdefer alloc.destroy(self);
        self.* = .{
            .alloc = alloc,
            .cache = .init(alloc),
            .restores = restores,
            .terminal = try .init(ghostty.TinyIo.init.io(), alloc, .{
                .cols = 80,
                .rows = 24,
                .max_scrollback_bytes = 10_000,
                .kitty_image_storage_limit = limit,
                .kitty_image_loading_limits = .direct,
            }),
        };
        var handler = self.terminal.vtHandler();
        handler.effects.write_pty = writePty;
        self.handler = .{ .owner = self, .inner = handler };
        self.stream = TestStream.init(.{ .handler = &self.handler, .allocator = alloc });
        return self;
    }

    fn destroy(self: *TestTerminal) void {
        const alloc = self.alloc;
        self.stream.deinit();
        self.cache.deinit();
        self.replies.deinit(alloc);
        self.terminal.deinit(alloc);
        alloc.destroy(self);
    }

    fn writePty(handler: *Handler, data: []const u8) void {
        const wrapper: *Wrapper = @fieldParentPtr("inner", handler);
        const self = wrapper.owner;
        self.replies.appendSlice(self.alloc, data) catch {};
    }

    fn send(self: *TestTerminal, control: []const u8, payload: []const u8) !void {
        const encoder = std.base64.standard.Encoder;
        const encoded = try self.alloc.alloc(u8, encoder.calcSize(payload.len));
        defer self.alloc.free(encoded);
        _ = encoder.encode(encoded, payload);
        const sequence = if (payload.len > 0)
            try std.fmt.allocPrint(self.alloc, "\x1b_G{s};{s}\x1b\\", .{ control, encoded })
        else
            try std.fmt.allocPrint(self.alloc, "\x1b_G{s}\x1b\\", .{control});
        defer self.alloc.free(sequence);
        self.stream.nextSlice(sequence);
    }

    /// Sends a 20x10 PNG, 800 bytes decoded, of which every byte but alpha is `value`.
    fn sendPng(self: *TestTerminal, control: []const u8, value: u8) !void {
        const png = try makePng(self.alloc, 20, 10, value);
        defer self.alloc.free(png);
        try self.send(control, png);
    }

    fn storage(self: *TestTerminal) *graphics.ImageStorage {
        return &self.terminal.screens.active.kitty_images;
    }

    /// The first byte of the image's pixels, or null when it isn't stored.
    fn pixel(self: *TestTerminal, id: u32) ?u8 {
        const image = self.storage().imageById(id) orelse return null;
        return (image.renderData().bytes() orelse return null)[0];
    }

    fn placeAndExpectNotFound(self: *TestTerminal, id_control: []const u8) !void {
        self.replies.clearRetainingCapacity();
        try self.send(id_control, "");
        try testing.expect(std.mem.indexOf(u8, self.replies.items, "ENOENT") != null);
    }
};

// Fits one 800-byte image, as 64 MiB fits one large image and not a second.
const one_image_limit = 1000;

test "without the cache, placing an evicted image finds nothing" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, false);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.send("a=p,U=1,i=1,p=1,c=10,r=5", "");
    try t.sendPng("t=d,i=2,f=100", 66);
    try testing.expect(t.storage().imageById(1) == null);
    try t.placeAndExpectNotFound("a=p,U=1,i=1,p=2,c=10,r=5");
    try testing.expectEqual(0, t.storage().placements.count());
}

test "going back and forth between two images restores each" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("q=2,t=d,i=1,f=100", 65);
    try t.send("q=2,a=p,U=1,i=1,p=1,C=1,c=10,r=5", "");
    try t.sendPng("q=2,t=d,i=2,f=100", 66);
    try t.send("q=2,a=p,U=1,i=2,p=2,C=1,c=10,r=5", "");
    try testing.expect(t.storage().imageById(1) == null);

    try t.send("a=p,U=1,i=1,p=3,C=1,c=10,r=5", "");
    try testing.expect(std.mem.indexOf(u8, t.replies.items, "ENOENT") == null);
    try testing.expectEqual(65, t.pixel(1));
    try testing.expect(t.storage().imageById(2) == null);
    try testing.expectEqual(1, t.storage().placements.count());

    try t.send("a=p,U=1,i=2,p=4,C=1,c=10,r=5", "");
    try testing.expectEqual(66, t.pixel(2));
    try testing.expect(t.storage().imageById(1) == null);
    try testing.expectEqual(1, t.storage().placements.count());
    try testing.expect(t.storage().total_bytes <= one_image_limit);
}

test "a chunked PNG is kept whole" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    const png = try makePng(test_alloc, 20, 10, 65);
    defer test_alloc.free(png);
    // Each chunk is a multiple of 3 bytes, so it encodes to base64 without padding.
    const split = (png.len / 2) / 3 * 3;
    try t.send("q=2,t=d,i=1,f=100,m=1", png[0..split]);
    try t.send("m=0", png[split..]);
    try testing.expectEqualSlices(u8, png, t.cache.entries.get(ImageCache.activeKey(&t.terminal, 1)).?.png);

    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("a=p,i=1,p=1", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "a restored image has a new generation, so the app's bitmap cache fetches it" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    const first = t.storage().imageById(1).?.generation;
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("a=p,i=1,p=1", "");
    try testing.expect(t.storage().imageById(1).?.generation > first);
}

test "an image placed by number is restored" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,I=7,f=100", 65);
    try t.sendPng("t=d,i=2,f=100", 66);
    try testing.expect(t.storage().imageByNumber(7) == null);
    try t.send("a=p,I=7,p=1", "");
    try testing.expectEqual(65, t.storage().imageByNumber(7).?.renderData().bytes().?[0]);
    try testing.expectEqual(1, t.storage().placements.count());
}

test "an image that clearing the screen deleted is restored" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    t.stream.nextSlice("\x1b[2J");
    try testing.expect(t.storage().imageById(1) == null);
    try t.send("a=p,i=1,p=1", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "an image the program deleted stays deleted" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.send("a=d,d=I,i=1", "");
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
    try testing.expectEqual(0, t.cache.total_bytes);
}

test "an evicted image the program then deleted stays deleted" {
    const cases = [_]struct { send: []const u8, delete: []const u8, place: []const u8 }{
        .{ .send = "t=d,i=1,f=100", .delete = "a=d,d=I,i=1", .place = "a=p,i=1,p=1" },
        .{ .send = "t=d,i=1,f=100", .delete = "a=d,d=A", .place = "a=p,i=1,p=1" },
        .{ .send = "t=d,i=1,f=100", .delete = "a=d,d=R,x=1,y=5", .place = "a=p,i=1,p=1" },
        .{ .send = "t=d,I=7,f=100", .delete = "a=d,d=N,I=7", .place = "a=p,I=7,p=1" },
    };
    for (cases) |case| {
        const t = try TestTerminal.create(test_alloc, one_image_limit, true);
        defer t.destroy();
        try t.sendPng(case.send, 65);
        try t.sendPng("t=d,i=2,f=100", 66);
        // The first image is evicted and still restorable until the program deletes it.
        try testing.expectEqual(1, t.storage().images.count());
        try testing.expectEqual(2, t.cache.entries.count());
        try t.send(case.delete, "");
        try t.placeAndExpectNotFound(case.place);
    }
}

test "deleting only placements, as snacks.nvim does, keeps the image restorable" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.send("a=p,i=1,p=1", "");
    try t.send("a=d,d=i,i=1", "");
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("a=p,i=1,p=2", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "an ID replaced with raw pixels never brings the old PNG back" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    const raw: [16]u8 = @splat(7);
    try t.send("t=d,i=1,f=32,s=2,v=2", &raw);
    try testing.expectEqual(0, t.cache.entries.count());
    try testing.expectEqual(0, t.cache.total_bytes);
    try testing.expectEqual(7, t.pixel(1));
}

test "a new PNG for an ID replaces the old one" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.sendPng("t=d,i=1,f=100", 67);
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("a=p,i=1,p=1", "");
    try testing.expectEqual(67, t.pixel(1));
}

test "an image sent and displayed in one command is kept" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("a=T,i=1,p=1,f=100", 65);
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("a=p,i=1,p=2", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "a later chunk naming another ID doesn't forget that image" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    const png = try makePng(test_alloc, 20, 10, 65);
    defer test_alloc.free(png);
    const split = (png.len / 2) / 3 * 3;
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.send("t=d,i=1,f=100,m=1", png[0..split]);
    try t.send("i=2,m=0", png[split..]);
    try testing.expectEqual(65, t.pixel(1));
    try testing.expectEqual(66, t.pixel(2));
    try testing.expectEqual(2, t.cache.entries.count());
}

test "a query decodes a PNG and keeps nothing" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("a=q,t=d,i=9,f=100", 65);
    try testing.expectEqual(0, t.cache.entries.count());
    try testing.expect(t.cache.pending_png == null);
}

test "an image sent without an ID is left out, since nothing can place it again" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("a=t,f=100", 65);
    try testing.expectEqual(1, t.storage().images.count());
    try testing.expectEqual(0, t.cache.entries.count());
}

test "an animated image is left out, since its frames aren't in the PNG" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try testing.expectEqual(1, t.cache.entries.count());
    try t.sendPng("a=f,i=1,f=100,s=20,v=10", 9);
    try testing.expect(t.storage().imageById(1).?.animation != null);
    try testing.expectEqual(0, t.cache.entries.count());
}

test "an animation command for an evicted image forgets it" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.sendPng("t=d,i=2,f=100", 66);
    try t.sendPng("a=f,i=1,f=100,s=20,v=10", 9);
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
}

test "images belong to their screen" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);

    // A program on the alternate screen, as nvim runs, never sent this image.
    t.stream.nextSlice("\x1b[?1049h");
    try t.placeAndExpectNotFound("a=p,i=1,p=1");

    // It can use the same ID for its own image, which restores as itself.
    try t.sendPng("t=d,i=1,f=100", 66);
    try t.sendPng("t=d,i=2,f=100", 67);
    try t.send("a=p,i=1,p=1", "");
    try testing.expectEqual(66, t.pixel(1));

    // The primary screen's image is untouched by any of that.
    t.stream.nextSlice("\x1b[?1049l");
    try testing.expectEqual(65, t.pixel(1));
}

test "evicted images come back on the screen that sent them, after switching away" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    t.stream.nextSlice("\x1b[?1049h");
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.sendPng("t=d,i=2,f=100", 66);
    t.stream.nextSlice("\x1b[?1049l");
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
    t.stream.nextSlice("\x1b[?1049h");
    try t.send("a=p,i=1,p=2", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "a removed alternate screen takes its entries with it" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    t.stream.nextSlice("\x1b[?1049h");
    try t.sendPng("t=d,i=1,f=100", 65);
    t.stream.nextSlice("\x1b[?1049l");
    t.terminal.screens.remove(test_alloc, .alternate);

    t.stream.nextSlice("\x1b[?1049h");
    try t.placeAndExpectNotFound("a=p,i=1,p=1");

    // The next change to the images frees the entry too.
    try t.sendPng("t=d,i=2,f=100", 66);
    try testing.expectEqual(1, t.cache.entries.count());
}

test "a full reset forgets everything" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    t.stream.nextSlice("\x1bc");
    try testing.expectEqual(0, t.cache.entries.count());
    try testing.expectEqual(0, t.cache.total_bytes);
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
}

test "the oldest PNGs go first when the cache is full, and a placement counts as use" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    const png = try makePng(test_alloc, 20, 10, 65);
    defer test_alloc.free(png);
    t.cache.max_bytes = png.len * 2;

    try t.send("t=d,i=1,f=100", png);
    try t.send("t=d,i=2,f=100", png);
    try t.send("a=p,i=1,p=1", "");
    try t.send("t=d,i=3,f=100", png);

    try testing.expectEqual(2, t.cache.entries.count());
    try testing.expectEqual(png.len * 2, t.cache.total_bytes);
    try testing.expect(t.cache.entries.contains(ImageCache.activeKey(&t.terminal, 1)));
    try testing.expect(!t.cache.entries.contains(ImageCache.activeKey(&t.terminal, 2)));
}

test "a PNG larger than the whole cache is shown but not kept" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    t.cache.max_bytes = 10;
    try t.sendPng("t=d,i=1,f=100", 65);
    try testing.expectEqual(65, t.pixel(1));
    try testing.expectEqual(0, t.cache.entries.count());
    try testing.expectEqual(0, t.cache.total_bytes);
}

test "the number of entries is bounded" {
    const t = try TestTerminal.create(test_alloc, 10_000_000, true);
    defer t.destroy();
    const png = try makePng(test_alloc, 1, 1, 65);
    defer test_alloc.free(png);
    for (1..max_entries + 10) |id| {
        var buf: [32]u8 = undefined;
        try t.send(try std.fmt.bufPrint(&buf, "t=d,i={d},f=100", .{id}), png);
    }
    try testing.expectEqual(max_entries, t.cache.entries.count());
    try testing.expectEqual(png.len * max_entries, t.cache.total_bytes);
}

test "other APC sequences and malformed commands pass through" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    t.stream.nextSlice("\x1b_notkitty\x1b\\");
    t.stream.nextSlice("\x1b_G;\x1b\\");
    t.stream.nextSlice("\x1b_Ga=\x1b\\");
    const long_header: [2 * max_header_bytes]u8 = @splat('a');
    try t.send(&long_header, "");
    try t.sendPng("t=d,i=1,f=100", 65);
    try testing.expectEqual(1, t.cache.entries.count());
}

test "an aborted command changes nothing" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.sendPng("t=d,i=2,f=100", 66);
    t.stream.nextSlice("\x1b_Ga=p,i=1,p=1\x18");
    try testing.expect(t.storage().imageById(1) == null);
    try testing.expectEqual(2, t.cache.entries.count());
    try t.send("a=p,i=1,p=1", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "a PNG that doesn't decode leaves the ID empty, as Ghostty does" {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    try t.send("t=d,i=1,f=100", "not a png");
    try testing.expect(t.cache.pending_png == null);
    try testing.expectEqual(0, t.cache.entries.count());
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
}

test "an image that no longer fits fails like a missing one, and can be tried again" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    t.stream.nextSlice("\x1b[2J");
    t.storage().setLimit(t.terminal.io(), test_alloc, t.terminal.screens.active, 100);
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
    try testing.expectEqual(1, t.cache.entries.count());

    t.storage().setLimit(t.terminal.io(), test_alloc, t.terminal.screens.active, 100_000);
    try t.send("a=p,i=1,p=2", "");
    try testing.expectEqual(65, t.pixel(1));
}

test "a PNG that decodes to another size is dropped rather than stored" {
    const t = try TestTerminal.create(test_alloc, 100_000, true);
    defer t.destroy();
    try t.sendPng("t=d,i=1,f=100", 65);
    t.stream.nextSlice("\x1b[2J");
    t.cache.entries.getPtr(ImageCache.activeKey(&t.terminal, 1)).?.image.width = 5;
    try t.placeAndExpectNotFound("a=p,i=1,p=1");
    try testing.expectEqual(0, t.cache.entries.count());
    try testing.expectEqual(0, t.cache.total_bytes);
}

fn runSessionThread(value: u8, failed: *std.atomic.Value(bool)) void {
    runSession(value) catch failed.store(true, .seq_cst);
}

fn runSession(value: u8) !void {
    const t = try TestTerminal.create(test_alloc, one_image_limit, true);
    defer t.destroy();
    for (0..20) |_| {
        try t.sendPng("t=d,i=1,f=100", value);
        try t.sendPng("t=d,i=2,f=100", value + 1);
        try t.send("a=p,i=1,p=1", "");
        if (t.pixel(1) != value) return error.WrongSessionsPixels;
    }
}

test "sessions on different threads keep their own PNGs" {
    ghostty.sys.decode_png = testDecode;
    var failed: std.atomic.Value(bool) = .init(false);
    const first = try std.Thread.spawn(.{}, runSessionThread, .{ 10, &failed });
    const second = try std.Thread.spawn(.{}, runSessionThread, .{ 100, &failed });
    first.join();
    second.join();
    try testing.expect(!failed.load(.seq_cst));
}
