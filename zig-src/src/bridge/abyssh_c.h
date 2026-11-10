// C headers used by the JNI bridges. build.zig translates this file into the
// "c" module, which replaces @cImport (removed in Zig 0.17).
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <libssh2.h>
#include <libssh2_sftp.h>

// Built into libssh2 from src/vendor/libssh2/ssh2ext.c.
int ssh2ext_discard_global_replies(LIBSSH2_SESSION *session);

#include "abyssh_jni_internal.h"
