#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <jvmti.h>
#include <filesystem>
#include <fstream>
#include <cstdio>
#include <cstring>
#include <string>

namespace {
JavaVM* vm = nullptr;
jvmtiEnv* jvmti = nullptr;
HINSTANCE selfInstance = nullptr;
std::filesystem::path statusFile;

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
    wchar_t local[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    std::filesystem::path dir = std::filesystem::path(local) / L"RazorClient";
    std::filesystem::create_directories(dir);
    std::ofstream out(dir / L"bootstrap.log", std::ios::app);
    out << timestamp() << " " << message << '\n';
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
        jmethodID toString = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
        auto text = static_cast<jstring>(env->CallObjectMethod(error, toString));
        if (text) {
            const char* utf = env->GetStringUTFChars(text, nullptr);
            detail = utf ? utf : "";
            env->ReleaseStringUTFChars(text, utf);
        }
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
    if (!std::filesystem::exists(jar) || std::filesystem::file_size(jar) == 0) {
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
