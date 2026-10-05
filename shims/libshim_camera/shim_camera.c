/* Project Hyppie: C port of LineageOS device-common/shims/libcamera (Apache-2.0).
 * Samsung's libexynoscamera3 calls the removed GraphicBufferMapper::unlock(handle);
 * forward it to unlock(handle, unique_fd*) and wait on the release fence. */
#include <unistd.h>

typedef int status_t;
extern status_t _ZN7android19GraphicBufferMapper6unlockEPK13native_handlePNS_4base14unique_fd_implINS4_13DefaultCloserEEE(
        void *mapper, const void *handle, int *out_fence);   /* unique_fd is a single int */
extern int sync_wait(int fd, int timeout);

status_t _ZN7android19GraphicBufferMapper6unlockEPK13native_handle(void *mapper, const void *handle) {
    int fence = -1;
    status_t status = _ZN7android19GraphicBufferMapper6unlockEPK13native_handlePNS_4base14unique_fd_implINS4_13DefaultCloserEEE(
            mapper, handle, &fence);
    if (status == 0 && fence >= 0) sync_wait(fence, -1);
    if (fence >= 0) close(fence);
    return status;
}
