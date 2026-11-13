/*
 * Additions to libssh2, built as part of it against its private headers so they see
 * the same structures as the rest of the library.
 */
#include "libssh2_priv.h"
#include "channel.h"
#include "transport.h"

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

/*
 * Sends an "env" channel request that asks for no reply, as OpenSSH's client does, so
 * setting a variable costs no round trip. A server that doesn't accept the variable
 * ignores it. Returns 0 once the request is sent, LIBSSH2_ERROR_EAGAIN when the socket
 * can't take it yet, in which case call it again with the same arguments, or another
 * libssh2 error. Uses the channel's setenv state, so it must not run while a
 * libssh2_channel_setenv_ex on the same channel is unfinished.
 */
int ssh2ext_channel_setenv_noreply(LIBSSH2_CHANNEL *channel,
                                   const char *varname, unsigned int varname_len,
                                   const char *value, unsigned int value_len)
{
    LIBSSH2_SESSION *session;
    unsigned char *s;
    int rc;

    if(!channel)
        return LIBSSH2_ERROR_BAD_USE;
    session = channel->session;

    if(channel->setenv_state == libssh2_NB_state_idle) {
        /* 21 = packet_type(1) + channel_id(4) + request_len(4) +
         * request(3)"env" + want_reply(1) + varname_len(4) + value_len(4) */
        channel->setenv_packet_len = varname_len + value_len + 21;
        s = channel->setenv_packet =
            LIBSSH2_ALLOC(session, channel->setenv_packet_len);
        if(!channel->setenv_packet)
            return _libssh2_error(session, LIBSSH2_ERROR_ALLOC,
                                  "Unable to allocate memory for setenv packet");

        *(s++) = SSH_MSG_CHANNEL_REQUEST;
        _libssh2_store_u32(&s, channel->remote.id);
        _libssh2_store_str(&s, "env", sizeof("env") - 1);
        *(s++) = 0x00;
        _libssh2_store_str(&s, varname, varname_len);
        _libssh2_store_str(&s, value, value_len);

        channel->setenv_state = libssh2_NB_state_created;
    }

    rc = _libssh2_transport_send(session, channel->setenv_packet,
                                 channel->setenv_packet_len, NULL, 0);
    if(rc == LIBSSH2_ERROR_EAGAIN)
        return rc;

    LIBSSH2_FREE(session, channel->setenv_packet);
    channel->setenv_packet = NULL;
    channel->setenv_state = libssh2_NB_state_idle;
    if(rc)
        return _libssh2_error(session, LIBSSH2_ERROR_SOCKET_SEND,
                              "Unable to send setenv request");
    return 0;
}

/*
 * Whether the server has sent EOF or closed the channel. Unlike libssh2_channel_eof,
 * true while data from before the EOF is still queued.
 */
int ssh2ext_channel_remote_finished(LIBSSH2_CHANNEL *channel)
{
    if(!channel)
        return 1;
    return (channel->remote.eof || channel->remote.close) ? 1 : 0;
}

/*
 * Processes what the socket holds, as libssh2_channel_read does, without growing the
 * channel's receive window: libssh2 widens it only when a read takes data off the
 * queue, so once the queue fills the window the server has no credit left and stops
 * sending on this channel. Key exchange, keepalives and their replies, the server's
 * own global requests, the window the server grants for our writes, and its EOF and
 * close are all still handled, and the data that arrives within the window stays
 * queued for a later read.
 *
 * A window adjust a read left half-sent is finished first, since libssh2 can send
 * nothing else on the session until it is. Returns 1 once the server has sent EOF or
 * closed the channel, 0 otherwise, including when the socket has nothing more yet, or
 * a negative libssh2 error when the connection fails.
 */
int ssh2ext_channel_pump(LIBSSH2_CHANNEL *channel)
{
    LIBSSH2_SESSION *session;
    int rc;

    if(!channel)
        return LIBSSH2_ERROR_BAD_USE;
    session = channel->session;

    if(channel->adjust_state != libssh2_NB_state_idle) {
        /* The held packet carries its own size; pass the same one so the window
         * libssh2 counts grows by what the server is told. */
        uint32_t held = _libssh2_ntohu32(&channel->adjust_adjust[5]);
        rc = _libssh2_channel_receive_window_adjust(channel, held, 1, NULL);
        if(rc == 0)
            channel->read_state = libssh2_NB_state_idle;
        else if(rc != LIBSSH2_ERROR_EAGAIN)
            return rc;
    }

    do {
        rc = _libssh2_transport_read(session);
    } while(rc > 0);

    if(rc < 0 && rc != LIBSSH2_ERROR_EAGAIN)
        return _libssh2_error(session, rc, "transport read");

    return ssh2ext_channel_remote_finished(channel);
}
