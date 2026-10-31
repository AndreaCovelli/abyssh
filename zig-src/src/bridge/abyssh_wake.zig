//! JNI bridge for the eventfd that wakes a session thread blocked waiting for output,
//! and the wait that the SSH and local shell bridges share.
const c = @import("c");

/// Results of `waitReadable`, returned to Kotlin as they are.
pub const wait_cannot = -1;
pub const wait_timeout = 0;
pub const wait_ready = 1;
pub const wait_woken = 2;

/// Waits up to `timeout_ms` for `fd` to become readable or for `wake_fd` to be signalled,
/// and returns one of the `wait_*` values. A hung-up or failed `fd` returns
/// `wait_cannot`, so the caller falls back to polling and its next read reports the
/// cause instead of the wait returning at once forever.
pub fn waitReadable(fd: c_int, wake_fd: c_int, timeout_ms: c_int) c_int {
    if (fd < 0 or wake_fd < 0) return wait_cannot;
    var poll_fds: [2]c.struct_pollfd = .{
        .{ .fd = fd, .events = c.POLLIN, .revents = 0 },
        .{ .fd = wake_fd, .events = c.POLLIN, .revents = 0 },
    };
    const rc = c.poll(&poll_fds, poll_fds.len, @max(timeout_ms, 0));
    if (rc < 0) return if (c.__errno().* == c.EINTR) wait_timeout else wait_cannot;
    if (rc == 0) return wait_timeout;

    const woken = (poll_fds[1].revents & c.POLLIN) != 0;
    if (woken) drain(wake_fd);
    if ((poll_fds[0].revents & c.POLLIN) != 0) return wait_ready;
    if ((poll_fds[0].revents & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) return wait_cannot;
    if ((poll_fds[1].revents & (c.POLLERR | c.POLLHUP | c.POLLNVAL)) != 0) return wait_cannot;
    return if (woken) wait_woken else wait_timeout;
}

/// Resets the eventfd's counter, so the next wait blocks until the next signal.
fn drain(wake_fd: c_int) void {
    var value: u64 = 0;
    _ = c.read(wake_fd, &value, @sizeOf(u64));
}

/// A non-blocking eventfd, or -1 when the kernel won't make one.
export fn Java_io_github_andreacovelli_abyssh_service_terminal_NativeWakeBridge_nativeCreate(env: *c.JNIEnv, thiz: c.jobject) callconv(.c) c.jint {
    _ = env;
    _ = thiz;
    // EFD_NONBLOCK and EFD_CLOEXEC are defined as O_NONBLOCK and O_CLOEXEC.
    return c.eventfd(0, c.O_NONBLOCK | c.O_CLOEXEC);
}

export fn Java_io_github_andreacovelli_abyssh_service_terminal_NativeWakeBridge_nativeSignal(env: *c.JNIEnv, thiz: c.jobject, fd: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    if (fd < 0) return;
    const one: u64 = 1;
    // EAGAIN means the counter is already non-zero, so the waiter wakes anyway.
    _ = c.write(fd, &one, @sizeOf(u64));
}

export fn Java_io_github_andreacovelli_abyssh_service_terminal_NativeWakeBridge_nativeClose(env: *c.JNIEnv, thiz: c.jobject, fd: c.jint) callconv(.c) void {
    _ = env;
    _ = thiz;
    if (fd >= 0) _ = c.close(fd);
}
