// C headers used by the JNI bridges. build.zig translates this file into the
// "c" module, which replaces @cImport (removed in Zig 0.17).
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <libssh2.h>
#include <libssh2_sftp.h>

#include "abyssh_jni_internal.h"
