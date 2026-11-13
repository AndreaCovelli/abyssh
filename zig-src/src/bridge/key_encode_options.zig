//! The options a key is encoded with. They follow the terminal's modes, except that
//! Backspace with Ctrl held keeps its legacy encoding under xterm's modifyOtherKeys
//! mode 2. tmux turns that mode on when `extended-keys` is set, and parses the CSI 27
//! form of Ctrl+Backspace, but can't pass it on to a pane that didn't ask for extended
//! keys: tmux 3.8 drops Ctrl+Backspace and sends only the Esc of Ctrl+Alt+Backspace.
//! The shell then waits on the Esc, and the next key finishes it, so the word that
//! Ctrl+Alt+Backspace deletes goes only when Backspace is pressed again. The legacy
//! `^H` and `Esc ^H` are what tmux would have sent the pane, and every program reads
//! them.
const ghostty = @import("ghostty-vt");

/// The options to encode [event] with, from [terminal], the terminal's own.
pub fn forEvent(terminal: ghostty.input.KeyEncodeOptions, event: ghostty.input.KeyEvent) ghostty.input.KeyEncodeOptions {
    var options = terminal;
    if (event.key == .backspace and event.mods.ctrl) options.modify_other_keys_state_2 = false;
    return options;
}

const std = @import("std");

fn expectEncoding(expected: []const u8, event: ghostty.input.KeyEvent, terminal: ghostty.input.KeyEncodeOptions) !void {
    var buf: [64]u8 = undefined;
    var writer: std.Io.Writer = .fixed(&buf);
    try ghostty.input.encodeKey(&writer, event, forEvent(terminal, event));
    try std.testing.expectEqualStrings(expected, writer.buffered());
}

const modify_other_keys: ghostty.input.KeyEncodeOptions = .{ .modify_other_keys_state_2 = true };

test "Ctrl+Alt+Backspace stays Esc ^H under modifyOtherKeys" {
    try expectEncoding("\x1b\x08", .{ .key = .backspace, .mods = .{ .ctrl = true, .alt = true } }, modify_other_keys);
}

test "Ctrl+Backspace stays ^H under modifyOtherKeys" {
    try expectEncoding("\x08", .{ .key = .backspace, .mods = .{ .ctrl = true } }, modify_other_keys);
    try expectEncoding("\x08", .{ .key = .backspace, .mods = .{ .ctrl = true, .shift = true } }, modify_other_keys);
}

test "Ctrl+Backspace follows the backarrow key mode" {
    var options = modify_other_keys;
    options.backarrow_key_mode = true;
    try expectEncoding("\x7f", .{ .key = .backspace, .mods = .{ .ctrl = true } }, options);
}

test "Alt+Backspace and Ctrl letters keep the CSI 27 form, which tmux passes on" {
    try expectEncoding("\x1b[27;3;127~", .{ .key = .backspace, .mods = .{ .alt = true } }, modify_other_keys);
    try expectEncoding(
        "\x1b[27;5;104~",
        .{ .key = .key_h, .mods = .{ .ctrl = true }, .utf8 = "h", .unshifted_codepoint = 'h' },
        modify_other_keys,
    );
}

test "the Kitty keyboard protocol still reports Ctrl+Alt+Backspace" {
    const kitty: ghostty.input.KeyEncodeOptions = .{ .modify_other_keys_state_2 = true, .kitty_flags = .{ .disambiguate = true } };
    try expectEncoding("\x1b[127;7u", .{ .key = .backspace, .mods = .{ .ctrl = true, .alt = true } }, kitty);
}
