// melonDS-android 2.0.1, GPL-3.0-or-later. Modified by Calamansi 2026-10-12: atomically deliver event header and payload.
#include <jni.h>
#include <unistd.h>
#include <fcntl.h>
#include <Platform.h>
#include <cerrno>
#include <cstring>

// messagePipes[0] -> read
// messagePipes[1] -> write
static int messagePipes[2] = { -1, -1 };

extern "C"
{

JNIEXPORT jint JNICALL
Java_me_magnum_melonds_impl_emulator_EmulatorMessageQueue_initMessagePipe(JNIEnv* env, jobject thiz)
{
    if (messagePipes[0] != -1) {
        return messagePipes[0];
    }

    if (pipe(messagePipes) == -1) {
        melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "Failed to create message queue pipes");
        return -1;
    }

    // Make read end non-blocking
    int flags = fcntl(messagePipes[0], F_GETFL, 0);
    fcntl(messagePipes[0], F_SETFL, flags | O_NONBLOCK);

    return messagePipes[0];
}

JNIEXPORT void JNICALL
Java_me_magnum_melonds_impl_emulator_EmulatorMessageQueue_closeMessagePipe(JNIEnv* env, jobject thiz)
{
    if (messagePipes[0] != -1) {
        close(messagePipes[0]);
        messagePipes[0] = -1;
    }
    if (messagePipes[1] != -1) {
        close(messagePipes[1]);
        messagePipes[1] = -1;
    }
}

}

namespace MelonDSAndroid {
    void fireEmulatorEvent(int type, int dataLength, const void* data) {
        if (messagePipes[1] == -1) {
            return;
        }

        // Keep header + payload atomic: the read end is nonblocking.
        struct {
            int type;
            int dataLength;
            unsigned char data[128];
        } event;
        if (dataLength < 0 || dataLength > sizeof(event.data) || (dataLength > 0 && data == nullptr)) {
            melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "Invalid emulator event payload");
            return;
        }
        event.type = type;
        event.dataLength = dataLength;
        if (dataLength > 0) std::memcpy(event.data, data, dataLength);
        const size_t messageSize = sizeof(int) * 2 + dataLength;
        ssize_t result;
        do { result = write(messagePipes[1], &event, messageSize); } while (result < 0 && errno == EINTR);
        if (result != messageSize)
            melonDS::Platform::Log(melonDS::Platform::LogLevel::Error, "Failed to write emulator event pipe");
    }
}