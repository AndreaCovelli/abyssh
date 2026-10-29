#ifndef MOSH_FFI_H
#define MOSH_FFI_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
  MOSH_OK = 0,
  MOSH_ERR_INVALID_ARGUMENT = 1,
  MOSH_ERR_BAD_HANDLE = 2,
  MOSH_ERR_ALLOC = 3,
  MOSH_ERR_NOT_STARTED = 4,
  MOSH_ERR_ALREADY_STARTED = 5,
  MOSH_ERR_OUTPUT_BUFFER_TOO_SMALL = 6,
  MOSH_ERR_INTERNAL = 7,
  MOSH_ERR_NOT_IMPLEMENTED = 8,
} mosh_error_code;

typedef enum {
  MOSH_EVENT_NONE = 0,
  MOSH_EVENT_HOST_BYTES = 1,
  MOSH_EVENT_RESIZE = 2,
  MOSH_EVENT_ECHO_ACK = 3,
  MOSH_EVENT_STATE_CHANGED = 4,
  MOSH_EVENT_DIAGNOSTIC = 5,
} mosh_output_event_type;

typedef struct {
  int32_t state;
  int32_t last_failure_code;
  uint64_t last_state_num_sent;
  uint64_t last_state_num_received;
  uint32_t pending_outbound;
  uint32_t pending_host_ops;
  uint32_t current_rto_ms;
} mosh_runtime_state;

uint32_t mosh_client_abi_version(void);
int32_t mosh_client_create(const char* config_json, void** out_handle);
int32_t mosh_client_start(void* handle);
int32_t mosh_client_send_input(void* handle, const uint8_t* ptr, size_t len);
int32_t mosh_client_resize(void* handle, int32_t cols, int32_t rows);
int32_t mosh_client_poll_output(
    void* handle,
    uint8_t* out_buf,
    size_t cap,
    size_t* out_written,
    int32_t* out_event_type,
    int32_t* out_cols,
    int32_t* out_rows,
    uint64_t* out_echo_ack);
int32_t mosh_client_poll_state(void* handle, mosh_runtime_state* out_state);
int32_t mosh_client_pump_network(void* handle);
int32_t mosh_client_stop(void* handle);
int32_t mosh_client_destroy(void* handle);

#ifdef __cplusplus
}
#endif

#endif
