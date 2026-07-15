#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <jni.h>
#include <jvmti.h>
#include <filesystem>
#include <fstream>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>

namespace {
JavaVM* vm = nullptr;
jvmtiEnv* jvmti = nullptr;
HINSTANCE selfInstance = nullptr;
std::filesystem::path statusFile;
using SwapBuffersFn = BOOL (WINAPI*)(HDC);
struct ImportPatch { HMODULE module; void** slot; void* original; };
std::vector<ImportPatch> renderPatches;
std::mutex renderMutex;
std::atomic<bool> renderInstalled{false};
std::atomic<int> activeRenderCallbacks{0};
std::atomic<DWORD> renderThreadId{0};
std::atomic<HGLRC> renderContext{nullptr};
std::atomic<HDC> renderDeviceContext{nullptr};
std::atomic<HWND> renderWindow{nullptr};
std::atomic<ULONGLONG> lastRenderCallbackTick{0};
jclass liveEntrypointClass = nullptr;
jmethodID nativeRenderMethod = nullptr;
SwapBuffersFn originalSwapBuffers = nullptr;
void writeStatus(const std::string& phase, const std::string& detail);

BOOL WINAPI hookedSwapBuffers(HDC dc) {
    thread_local bool inside = false;
    const DWORD currentThreadId = GetCurrentThreadId();
    const HGLRC currentContext = wglGetCurrentContext();
    const HWND currentWindow = dc ? WindowFromDC(dc) : nullptr;
    const DWORD selectedThreadId = renderThreadId.load(std::memory_order_acquire);
    const HGLRC selectedContext = renderContext.load(std::memory_order_acquire);
    const HDC selectedDc = renderDeviceContext.load(std::memory_order_acquire);
    const HWND selectedWindow = renderWindow.load(std::memory_order_acquire);
    const ULONGLONG lastRender = lastRenderCallbackTick.load(std::memory_order_acquire);
    const bool selectedSurface = selectedThreadId == currentThreadId
        && selectedContext == currentContext && selectedDc == dc;
    const bool selectedWindowGone = selectedWindow && !IsWindow(selectedWindow);
    const bool rendererReloadCandidate = selectedThreadId == currentThreadId
        && currentWindow && (currentWindow == selectedWindow || selectedWindowGone)
        && GetTickCount64() - lastRender > 1000;
    const bool eligibleSurface = (selectedThreadId == 0 && currentWindow)
        || selectedSurface || rendererReloadCandidate;
    if (!inside && eligibleSurface && currentContext && dc
            && renderInstalled.load(std::memory_order_acquire) && vm) {
        inside = true;
        JNIEnv* env = nullptr;
        bool attached = false;
        jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (state == JNI_EDETACHED && vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) == JNI_OK) {
            attached = true;
        }

        jclass entrypoint = nullptr;
        jmethodID renderMethod = nullptr;
        if (env) {
            // Take a local reference while holding the lifecycle lock. This closes the
            // install/uninstall race without holding the mutex across arbitrary Java code.
            std::lock_guard<std::mutex> lock(renderMutex);
            if (renderInstalled.load(std::memory_order_acquire) && liveEntrypointClass && nativeRenderMethod) {
                entrypoint = static_cast<jclass>(env->NewLocalRef(liveEntrypointClass));
                renderMethod = nativeRenderMethod;
                if (entrypoint) activeRenderCallbacks.fetch_add(1, std::memory_order_acq_rel);
            }
        }
        if (entrypoint) {
            const jboolean rendered = env->CallStaticBooleanMethod(entrypoint, renderMethod);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            } else if (rendered == JNI_TRUE) {
                // Java confirms this is Minecraft's client/render thread before the
                // native bridge pins subsequent callbacks to its OpenGL context.
                renderContext.store(currentContext, std::memory_order_release);
                renderDeviceContext.store(dc, std::memory_order_release);
                renderWindow.store(currentWindow, std::memory_order_release);
                lastRenderCallbackTick.store(GetTickCount64(), std::memory_order_release);
                renderThreadId.store(currentThreadId, std::memory_order_release);
            }
            env->DeleteLocalRef(entrypoint);
            activeRenderCallbacks.fetch_sub(1, std::memory_order_acq_rel);
        }
        if (attached) vm->DetachCurrentThread();
        inside = false;
    }
    return originalSwapBuffers ? originalSwapBuffers(dc) : FALSE;
}

bool patchModuleSwapBuffers(HMODULE module) {
    auto base = reinterpret_cast<unsigned char*>(module);
    auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    if (!dos || dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    auto nt = reinterpret_cast<IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;
    auto directory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (!directory.VirtualAddress || !directory.Size) return false;
    bool patched = false;
    auto descriptor = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(base + directory.VirtualAddress);
    for (; descriptor->Name; ++descriptor) {
        auto thunk = reinterpret_cast<IMAGE_THUNK_DATA*>(base + descriptor->FirstThunk);
        auto names = descriptor->OriginalFirstThunk
            ? reinterpret_cast<IMAGE_THUNK_DATA*>(base + descriptor->OriginalFirstThunk) : nullptr;
        for (; thunk->u1.Function; ++thunk) {
            void** slot = reinterpret_cast<void**>(&thunk->u1.Function);
            bool isSwapBuffers = false;
            if (names) {
                if (names->u1.AddressOfData && !IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) {
                    auto import = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(base + names->u1.AddressOfData);
                    isSwapBuffers = std::strcmp(reinterpret_cast<const char*>(import->Name), "SwapBuffers") == 0;
                }
                ++names;
            } else {
                isSwapBuffers = *slot == reinterpret_cast<void*>(originalSwapBuffers);
            }
            if (!isSwapBuffers) continue;
            if (*slot == reinterpret_cast<void*>(&hookedSwapBuffers)) continue;
            // Do not bypass another renderer/overlay that already owns this import.
            if (*slot != reinterpret_cast<void*>(originalSwapBuffers)) continue;
            DWORD oldProtect = 0;
            if (!VirtualProtect(slot, sizeof(void*), PAGE_READWRITE, &oldProtect)) continue;
            renderPatches.push_back({module, slot, *slot});
            *slot = reinterpret_cast<void*>(&hookedSwapBuffers);
            VirtualProtect(slot, sizeof(void*), oldProtect, &oldProtect);
            FlushInstructionCache(GetCurrentProcess(), slot, sizeof(void*));
            patched = true;
        }
    }
    return patched;
}

jboolean JNICALL installRenderBridge(JNIEnv* env, jclass owner) {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (renderInstalled.load()) {
        return liveEntrypointClass && env->IsSameObject(liveEntrypointClass, owner)
            ? JNI_TRUE : JNI_FALSE;
    }
    nativeRenderMethod = env->GetStaticMethodID(owner, "renderNativeFrame", "()Z");
    if (!nativeRenderMethod || env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    liveEntrypointClass = static_cast<jclass>(env->NewGlobalRef(owner));
    HMODULE gdi = GetModuleHandleW(L"gdi32.dll");
    originalSwapBuffers = gdi ? reinterpret_cast<SwapBuffersFn>(GetProcAddress(gdi, "SwapBuffers")) : nullptr;
    if (!liveEntrypointClass || !originalSwapBuffers) {
        if (liveEntrypointClass) env->DeleteGlobalRef(liveEntrypointClass);
        liveEntrypointClass = nullptr;
        nativeRenderMethod = nullptr;
        writeStatus("RENDER_BRIDGE_FAILED", "JNI class reference or SwapBuffers export unavailable");
        return JNI_FALSE;
    }
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, GetCurrentProcessId());
    MODULEENTRY32W entry{sizeof(entry)};
    bool patched = false;
    if (snapshot != INVALID_HANDLE_VALUE) {
        if (Module32FirstW(snapshot, &entry)) {
            do { patched |= patchModuleSwapBuffers(entry.hModule); } while (Module32NextW(snapshot, &entry));
        }
        CloseHandle(snapshot);
    }
    if (!patched) {
        env->DeleteGlobalRef(liveEntrypointClass);
        liveEntrypointClass = nullptr;
        nativeRenderMethod = nullptr;
        writeStatus("RENDER_BRIDGE_FAILED", "SwapBuffers import not found");
        return JNI_FALSE;
    }
    renderInstalled.store(true, std::memory_order_release);
    renderThreadId.store(0, std::memory_order_release);
    renderContext.store(nullptr, std::memory_order_release);
    renderDeviceContext.store(nullptr, std::memory_order_release);
    renderWindow.store(nullptr, std::memory_order_release);
    lastRenderCallbackTick.store(0, std::memory_order_release);
    writeStatus("RENDER_BRIDGE_INSTALLED", "OpenGL present callback active");
    return JNI_TRUE;
}

void JNICALL uninstallRenderBridge(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(renderMutex);
    renderInstalled.store(false, std::memory_order_release);
    for (const auto& patch : renderPatches) {
        MEMORY_BASIC_INFORMATION memory{};
        if (!VirtualQuery(patch.slot, &memory, sizeof(memory))
                || memory.State != MEM_COMMIT || memory.AllocationBase != patch.module) {
            continue;
        }
        DWORD oldProtect = 0;
        if (VirtualProtect(patch.slot, sizeof(void*), PAGE_READWRITE, &oldProtect)) {
            InterlockedCompareExchangePointer(
                reinterpret_cast<PVOID volatile*>(patch.slot),
                patch.original,
                reinterpret_cast<void*>(&hookedSwapBuffers)
            );
            VirtualProtect(patch.slot, sizeof(void*), oldProtect, &oldProtect);
        }
    }
    renderPatches.clear();
    for (int i = 0; i < 2000 && activeRenderCallbacks.load(std::memory_order_acquire) != 0; ++i) Sleep(1);
    if (liveEntrypointClass) env->DeleteGlobalRef(liveEntrypointClass);
    liveEntrypointClass = nullptr;
    nativeRenderMethod = nullptr;
    renderThreadId.store(0, std::memory_order_release);
    renderContext.store(nullptr, std::memory_order_release);
    renderDeviceContext.store(nullptr, std::memory_order_release);
    renderWindow.store(nullptr, std::memory_order_release);
    lastRenderCallbackTick.store(0, std::memory_order_release);
    writeStatus("RENDER_BRIDGE_REMOVED", "OpenGL present callback removed");
}

std::string timestamp() {
    SYSTEMTIME time{};
    GetLocalTime(&time);
    char buffer[64]{};
    std::snprintf(buffer, sizeof(buffer), "%04u-%02u-%02u %02u:%02u:%02u",
        time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond);
    return buffer;
}

std::string jsonEscape(const std::string& value) {
    std::string result;
    result.reserve(value.size() + 8);
    for (char ch : value) {
        switch (ch) {
            case '\\': result += "\\\\"; break;
            case '"': result += "\\\""; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default: result.push_back(ch); break;
        }
    }
    return result;
}

void log(const std::string& message) {
    try {
        wchar_t local[MAX_PATH]{};
        GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
        std::filesystem::path dir = std::filesystem::path(local) / L"RazorClient";
        std::filesystem::create_directories(dir);
        std::ofstream out(dir / L"bootstrap.log", std::ios::app);
        out << timestamp() << " " << message << '\n';
    } catch (...) {
        // Logging must never terminate the host JVM.
    }
}

void writeStatus(const std::string& phase, const std::string& detail = "") {
    if (statusFile.empty()) {
        return;
    }
    try {
        std::filesystem::create_directories(statusFile.parent_path());
        std::ofstream out(statusFile, std::ios::app);
        out << "{\"ts\":\"" << jsonEscape(timestamp()) << "\",\"source\":\"bootstrap\",\"phase\":\""
            << jsonEscape(phase) << "\",\"detail\":\"" << jsonEscape(detail) << "\"}\n";
    } catch (...) {
    }
}

std::filesystem::path resolveStatusFile() {
    wchar_t modulePath[MAX_PATH]{};
    if (selfInstance && GetModuleFileNameW(selfInstance, modulePath, MAX_PATH)) {
        std::filesystem::path dll(modulePath);
        std::wstring name = dll.filename().wstring();
        const std::wstring prefix = L"razorclient-bootstrap-live-";
        if (name.rfind(prefix, 0) == 0 && name.size() > prefix.size() + 4) {
            std::wstring suffix = name.substr(prefix.size());
            if (suffix.substr(suffix.size() - 4) == L".dll") {
                suffix.resize(suffix.size() - 4);
                return dll.parent_path() / (L"razorclient-status-live-" + suffix + L".jsonl");
            }
        }
    }

    wchar_t local[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    return std::filesystem::path(local) / L"RazorClient" / L"razorclient-status-live-unknown.jsonl";
}

std::filesystem::path resolveBuildMetadataFile() {
    wchar_t modulePath[MAX_PATH]{};
    if (selfInstance && GetModuleFileNameW(selfInstance, modulePath, MAX_PATH)) {
        std::filesystem::path dll(modulePath);
        std::wstring name = dll.filename().wstring();
        const std::wstring prefix = L"razorclient-bootstrap-live-";
        if (name.rfind(prefix, 0) == 0 && name.size() > prefix.size() + 4) {
            std::wstring suffix = name.substr(prefix.size());
            if (suffix.substr(suffix.size() - 4) == L".dll") {
                suffix.resize(suffix.size() - 4);
                return dll.parent_path() / (L"razorclient-build-live-" + suffix + L".properties");
            }
        }
    }

    wchar_t local[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    return std::filesystem::path(local) / L"RazorClient" / L"razorclient-build-live-unknown.properties";
}

std::string propertyValue(const std::filesystem::path& path, const std::string& key) {
    std::ifstream input(path);
    std::string line;
    const std::string prefix = key + "=";
    while (std::getline(input, line)) {
        if (line.rfind(prefix, 0) == 0) {
            return line.substr(prefix.size());
        }
    }
    return {};
}

bool clearException(JNIEnv* env, const char* context) {
    if (!env->ExceptionCheck()) {
        return false;
    }

    jthrowable error = env->ExceptionOccurred();
    env->ExceptionClear();
    std::string detail;
    if (error) {
        jclass type = env->GetObjectClass(error);
        jmethodID toString = type ? env->GetMethodID(type, "toString", "()Ljava/lang/String;") : nullptr;
        auto text = toString ? static_cast<jstring>(env->CallObjectMethod(error, toString)) : nullptr;
        if (!env->ExceptionCheck() && text) {
            const char* utf = env->GetStringUTFChars(text, nullptr);
            if (utf) {
                detail = utf;
                env->ReleaseStringUTFChars(text, utf);
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (text) env->DeleteLocalRef(text);
        if (type) env->DeleteLocalRef(type);
        env->DeleteLocalRef(error);
    }
    log(std::string(context) + " raised a Java exception: " + detail);
    writeStatus("FAILED", std::string(context) + ": " + detail);
    return true;
}

jclass findSystemClass(JNIEnv* env, const char* binaryName) {
    jclass loaderType = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem = env->GetStaticMethodID(loaderType, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = env->CallStaticObjectMethod(loaderType, getSystem);
    jmethodID loadClass = env->GetMethodID(loaderType, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = env->NewStringUTF(binaryName);
    return static_cast<jclass>(env->CallObjectMethod(loader, loadClass, name));
}

jobject findMinecraftClassLoader(JNIEnv* env) {
    jint loadedCount = 0;
    jclass* loaded = nullptr;
    if (jvmti->GetLoadedClasses(&loadedCount, &loaded) != JVMTI_ERROR_NONE) {
        return nullptr;
    }

    jobject result = nullptr;
    jclass classClass = env->FindClass("java/lang/Class");
    jmethodID getClassLoader = env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    for (jint i = 0; i < loadedCount && !result; ++i) {
        char* signature = nullptr;
        if (jvmti->GetClassSignature(loaded[i], &signature, nullptr) == JVMTI_ERROR_NONE && signature) {
            if (std::strcmp(signature, "Lnet/minecraft/client/Minecraft;") == 0) {
                jobject loader = env->CallObjectMethod(loaded[i], getClassLoader);
                if (!clearException(env, "Minecraft.getClassLoader") && loader) {
                    result = env->NewGlobalRef(loader);
                }
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
        }
    }
    jvmti->Deallocate(reinterpret_cast<unsigned char*>(loaded));
    return result;
}

std::filesystem::path resolveAgentJar() {
    wchar_t modulePath[MAX_PATH]{};
    if (selfInstance && GetModuleFileNameW(selfInstance, modulePath, MAX_PATH)) {
        std::filesystem::path dll(modulePath);
        std::wstring name = dll.filename().wstring();
        const std::wstring prefix = L"razorclient-bootstrap-live-";
        if (name.rfind(prefix, 0) == 0 && name.size() > prefix.size() + 4) {
            std::wstring suffix = name.substr(prefix.size());
            if (suffix.substr(suffix.size() - 4) == L".dll") {
                suffix.resize(suffix.size() - 4);
                return dll.parent_path() / (L"razorclient-agent-live-" + suffix + L".jar");
            }
        }
    }

    wchar_t local[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    return std::filesystem::path(local) / L"RazorClient" / L"razorclient-agent.jar";
}

jobject loadClassWith(JNIEnv* env, jobject loader, const char* binaryName) {
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass = env->GetMethodID(loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring name = env->NewStringUTF(binaryName);
    return env->CallObjectMethod(loader, loadClass, name);
}

void setSystemProperty(JNIEnv* env, jclass systemClass, jmethodID setProperty, const char* key, const std::string& value) {
    if (value.empty()) {
        return;
    }
    jstring propertyKey = env->NewStringUTF(key);
    jstring propertyValueText = env->NewStringUTF(value.c_str());
    env->CallStaticObjectMethod(systemClass, setProperty, propertyKey, propertyValueText);
    clearException(env, key);
}

jobject createPayloadClassLoader(JNIEnv* env, jobjectArray urls, jobject gameLoader) {
    jclass loaderClass = findSystemClass(env, "com.razorclient.inject.ChildFirstPayloadLoader");
    if (!loaderClass || clearException(env, "Resolve ChildFirstPayloadLoader")) {
        return nullptr;
    }
    jmethodID loaderCtor = env->GetMethodID(loaderClass, "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V");
    if (!loaderCtor || clearException(env, "Resolve ChildFirstPayloadLoader constructor")) {
        return nullptr;
    }
    return env->NewObject(loaderClass, loaderCtor, urls, gameLoader);
}

jclass loadPayloadEntrypoint(JNIEnv* env, const std::string& jarUtf8) {
    jobject gameLoader = findMinecraftClassLoader(env);
    if (!gameLoader) {
        log("Minecraft classloader not found");
        writeStatus("FAILED", "Minecraft classloader not found");
        return nullptr;
    }
    writeStatus("CLASSLOADER_FOUND", "Minecraft classloader resolved");

    jclass fileClass = env->FindClass("java/io/File");
    jmethodID fileCtor = env->GetMethodID(fileClass, "<init>", "(Ljava/lang/String;)V");
    jmethodID toUri = env->GetMethodID(fileClass, "toURI", "()Ljava/net/URI;");
    jclass uriClass = env->FindClass("java/net/URI");
    jmethodID toUrl = env->GetMethodID(uriClass, "toURL", "()Ljava/net/URL;");
    jclass urlClass = env->FindClass("java/net/URL");
    jobjectArray urls = env->NewObjectArray(1, urlClass, nullptr);
    jstring path = env->NewStringUTF(jarUtf8.c_str());
    jobject file = env->NewObject(fileClass, fileCtor, path);
    jobject uri = env->CallObjectMethod(file, toUri);
    jobject url = env->CallObjectMethod(uri, toUrl);
    if (clearException(env, "Build payload URL")) {
        return nullptr;
    }
    env->SetObjectArrayElement(urls, 0, url);

    jobject payloadLoader = createPayloadClassLoader(env, urls, gameLoader);
    if (clearException(env, "Create child-first payload classloader") || !payloadLoader) {
        return nullptr;
    }

    jclass threadClass = env->FindClass("java/lang/Thread");
    jmethodID currentThread = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jmethodID setContextClassLoader = env->GetMethodID(threadClass, "setContextClassLoader", "(Ljava/lang/ClassLoader;)V");
    jobject thread = env->CallStaticObjectMethod(threadClass, currentThread);
    env->CallVoidMethod(thread, setContextClassLoader, payloadLoader);
    clearException(env, "Set context classloader");

    jclass entry = static_cast<jclass>(loadClassWith(env, payloadLoader, "com.razorclient.inject.LiveEntrypoint"));
    if (clearException(env, "Load LiveEntrypoint from payload classloader")) {
        return nullptr;
    }
    log("Loaded LiveEntrypoint with child-first payload classloader: " + jarUtf8);
    return entry;
}

DWORD WINAPI initialize(void*) {
    statusFile = resolveStatusFile();
    writeStatus("BOOTSTRAP_LOADED", "Bootstrap DLL loaded");
    HMODULE jvm = nullptr;
    for (int i = 0; i < 300 && !(jvm = GetModuleHandleW(L"jvm.dll")); ++i) {
        Sleep(100);
    }
    if (!jvm) {
        log("jvm.dll not found");
        writeStatus("FAILED", "jvm.dll not found");
        return 1;
    }
    writeStatus("JVM_FOUND", "jvm.dll found");

    auto getVms = reinterpret_cast<jint(JNICALL*)(JavaVM**, jsize, jsize*)>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs"));
    if (!getVms) {
        log("JNI_GetCreatedJavaVMs export missing");
        writeStatus("FAILED", "JNI_GetCreatedJavaVMs export missing");
        return 2;
    }

    jsize count = 0;
    for (int i = 0; i < 3000 && count != 1; ++i) {
        count = 0;
        if (getVms(&vm, 1, &count) == JNI_OK && count == 1) {
            break;
        }
        Sleep(10);
    }
    if (count != 1 || !vm) {
        log("Unable to acquire JavaVM after waiting");
        writeStatus("FAILED", "Unable to acquire JavaVM after waiting");
        return 3;
    }

    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
        log("AttachCurrentThread failed");
        writeStatus("FAILED", "AttachCurrentThread failed");
        return 4;
    }
    if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
        log("JVMTI unavailable");
        writeStatus("FAILED", "JVMTI unavailable");
        vm->DetachCurrentThread();
        return 5;
    }

    jvmtiPhase phase{};
    jvmti->GetPhase(&phase);
    log("Initial JVMTI phase " + std::to_string(phase));
    for (int i = 0; i < 600 && phase != JVMTI_PHASE_LIVE; ++i) {
        Sleep(100);
        jvmti->GetPhase(&phase);
    }
    if (phase != JVMTI_PHASE_LIVE) {
        log("JVM did not reach live phase");
        writeStatus("FAILED", "JVM did not reach live phase");
        vm->DetachCurrentThread();
        return 6;
    }
    writeStatus("LIVE_PHASE", "JVM is live");

    std::filesystem::path jar = resolveAgentJar();
    std::string jarUtf8 = jar.string();
    log("Using agent jar " + jarUtf8);
    std::error_code jarError;
    const bool jarExists = std::filesystem::exists(jar, jarError);
    const auto jarSize = jarExists ? std::filesystem::file_size(jar, jarError) : 0;
    if (jarError || !jarExists || jarSize == 0) {
        log("Agent jar missing or empty");
        writeStatus("FAILED", "Agent jar missing or empty: " + jarUtf8);
        vm->DetachCurrentThread();
        return 7;
    }
    writeStatus("AGENT_FOUND", jarUtf8);
    if (jvmti->AddToSystemClassLoaderSearch(jarUtf8.c_str()) != JVMTI_ERROR_NONE) {
        log("AddToSystemClassLoaderSearch failed");
        writeStatus("FAILED", "AddToSystemClassLoaderSearch failed");
        vm->DetachCurrentThread();
        return 8;
    }

    jclass systemClass = env->FindClass("java/lang/System");
    jmethodID setProperty = env->GetStaticMethodID(systemClass, "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    setSystemProperty(env, systemClass, setProperty, "razorclient.live.statusFile", statusFile.string());

    std::filesystem::path metadataFile = resolveBuildMetadataFile();
    setSystemProperty(env, systemClass, setProperty, "razorclient.live.buildName", propertyValue(metadataFile, "buildName"));
    setSystemProperty(env, systemClass, setProperty, "razorclient.live.adapterVersion", propertyValue(metadataFile, "adapterVersion"));
    setSystemProperty(env, systemClass, setProperty, "razorclient.live.bakeHash", propertyValue(metadataFile, "bakeHash"));
    setSystemProperty(env, systemClass, setProperty, "razorclient.live.mappingsHash", propertyValue(metadataFile, "mappingsHash"));
    writeStatus("BUILD_METADATA", metadataFile.string());

    jclass live = loadPayloadEntrypoint(env, jarUtf8);
    if (!live) {
        clearException(env, "Resolve LiveEntrypoint");
        vm->DetachCurrentThread();
        return 9;
    }

    JNINativeMethod renderMethods[] = {
        {const_cast<char*>("installNativeRenderBridge"), const_cast<char*>("()Z"), reinterpret_cast<void*>(&installRenderBridge)},
        {const_cast<char*>("uninstallNativeRenderBridge"), const_cast<char*>("()V"), reinterpret_cast<void*>(&uninstallRenderBridge)}
    };
    if (env->RegisterNatives(live, renderMethods, 2) != JNI_OK || clearException(env, "Register render bridge natives")) {
        writeStatus("FAILED", "Register render bridge natives failed");
        vm->DetachCurrentThread();
        return 12;
    }

    jmethodID startMethod = env->GetStaticMethodID(live, "start", "()V");
    if (!startMethod) {
        clearException(env, "Resolve LiveEntrypoint.start");
        writeStatus("FAILED", "Resolve LiveEntrypoint.start failed");
        vm->DetachCurrentThread();
        return 10;
    }
    env->CallStaticVoidMethod(live, startMethod);
    if (clearException(env, "LiveEntrypoint.start")) {
        vm->DetachCurrentThread();
        return 11;
    }

    log("RazorClient live entrypoint started");
    writeStatus("ENTRYPOINT_CALLED", "LiveEntrypoint.start returned");
    vm->DetachCurrentThread();
    return 0;
}
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        selfInstance = instance;
        DisableThreadLibraryCalls(instance);
        if (HANDLE thread = CreateThread(nullptr, 0, initialize, nullptr, 0, nullptr)) {
            CloseHandle(thread);
        }
    }
    return TRUE;
}
