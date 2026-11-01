// Replacement for Termux's libtermux.so: terminals never fork here; each one attaches
// to a PTY master of a workspace shell (WorkspaceInteractiveShell) registered by token.
#include <jni.h>
#include <android/log.h>
#include <condition_variable>
#include <map>
#include <mutex>
#include <string>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "RikkiRemotePty"

namespace {
std::mutex g_lock;
std::condition_variable g_cv;
std::map<std::string, int> g_pending_fds;     // token -> master fd (owned until claimed)
std::map<int, std::string> g_pid_tokens;      // synthetic pid -> token
std::map<std::string, int> g_exit_codes;      // token -> exit status reported by the PTY session
int g_next = 0;
// Synthetic pids are above any real pid_max (<= 4194304), so a stray kill() is ESRCH.
constexpr int kSyntheticPidBase = 0x7FF00000;
const char kRemotePrefix[] = "rikki-remote:";

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkihub_terminal_RemotePtyNative_register(JNIEnv *env, jclass, jstring token, jint fd) {
    std::lock_guard<std::mutex> guard(g_lock);
    auto key = to_string(env, token);
    auto old = g_pending_fds.find(key);
    if (old != g_pending_fds.end()) close(old->second);
    g_pending_fds[key] = fd;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkihub_terminal_RemotePtyNative_markExited(JNIEnv *env, jclass, jstring token, jint code) {
    {
        std::lock_guard<std::mutex> guard(g_lock);
        g_exit_codes[to_string(env, token)] = code;
    }
    g_cv.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkihub_terminal_RemotePtyNative_forget(JNIEnv *env, jclass, jstring token) {
    std::lock_guard<std::mutex> guard(g_lock);
    auto key = to_string(env, token);
    auto fd = g_pending_fds.find(key);
    if (fd != g_pending_fds.end()) { close(fd->second); g_pending_fds.erase(fd); }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv *env, jclass, jstring cmd, jstring, jobjectArray, jobjectArray,
        jintArray processId, jint rows, jint columns) {
    auto command = to_string(env, cmd);
    if (command.rfind(kRemotePrefix, 0) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "local subprocess creation is disabled in the host");
        return -1;
    }
    auto token = command.substr(sizeof(kRemotePrefix) - 1);
    int fd;
    int pid;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        auto found = g_pending_fds.find(token);
        if (found == g_pending_fds.end()) return -1;
        fd = found->second;
        g_pending_fds.erase(found);
        pid = kSyntheticPidBase + ((++g_next) & 0xFFFFF);
        g_pid_tokens[pid] = token;
    }
    winsize size = {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
    if (processId != nullptr) {
        jint ids[1] = {pid};
        env->SetIntArrayRegion(processId, 0, 1, ids);
    }
    return fd;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_setPtyWindowSize(JNIEnv *, jclass, jint fd, jint rows, jint columns) {
    winsize size = {};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_termux_terminal_JNI_waitFor(JNIEnv *, jclass, jint pid) {
    std::unique_lock<std::mutex> lock(g_lock);
    auto token = g_pid_tokens.find(pid);
    if (token == g_pid_tokens.end()) return -1;  // never waitpid() in the host
    std::string key = token->second;
    g_cv.wait(lock, [&] { return g_exit_codes.count(key) > 0; });
    int code = g_exit_codes[key];
    g_exit_codes.erase(key);
    g_pid_tokens.erase(pid);
    return code;
}

extern "C" JNIEXPORT void JNICALL
Java_com_termux_terminal_JNI_close(JNIEnv *, jclass, jint fd) {
    close(fd);
}
