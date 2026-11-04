/*
 * Additions to libssh2, built as part of it against its private headers so they see
 * the same structures as the rest of the library.
 */
#include "libssh2_priv.h"

/*
 * Frees the replies to global requests that libssh2 queues and never takes off the
 * queue, such as the one the server sends for every keepalive that asks for a reply.
 * Each would otherwise stay queued until the session closes. Only for a session where
 * nothing waits for such a reply, as libssh2_channel_forward_listen_ex does. Returns
 * how many it freed.
 */
int ssh2ext_discard_global_replies(LIBSSH2_SESSION *session)
{
    LIBSSH2_PACKET *packet;
    int freed = 0;

    if(!session)
        return 0;

    packet = _libssh2_list_first(&session->packets);
    while(packet) {
        LIBSSH2_PACKET *next = _libssh2_list_next(&packet->node);

        if(packet->data_len == 1 &&
           (packet->data[0] == SSH_MSG_REQUEST_SUCCESS ||
            packet->data[0] == SSH_MSG_REQUEST_FAILURE)) {
            _libssh2_list_remove(&packet->node);
            LIBSSH2_FREE(session, packet->data);
            LIBSSH2_FREE(session, packet);
            freed++;
        }
        packet = next;
    }
    return freed;
}
