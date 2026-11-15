#include <android/log.h>
#include <unistd.h>

__attribute__((constructor)) static void lg_probe_ctor(void) {
    __android_log_print(ANDROID_LOG_INFO, "LG-PROBE", "CHANNEL-ALIVE pid=%d", getpid());
}
