#pragma once

#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>

#include "event_logger.hpp"
#include "resource_guard.hpp"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <utility>
#include <vector>

#if defined(_MSC_VER)
#pragma comment(lib, "Psapi.lib")
#endif

class ModuleLoader final {
public:
    explicit ModuleLoader(DWORD pid) noexcept : pid_(pid) {
        if (pid == 0u) {
            SetFailure("open process", ERROR_INVALID_PARAMETER);
            return;
        }

        constexpr DWORD access = PROCESS_CREATE_THREAD |
            PROCESS_QUERY_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION |
            PROCESS_VM_OPERATION | PROCESS_VM_READ | PROCESS_VM_WRITE |
            SYNCHRONIZE;
        process_ = HandleGuard(::OpenProcess(access, FALSE, pid));
        if (!process_) {
            SetFailure("open process", ::GetLastError());
            return;
        }
        if (!ProcessIsRunning()) {
            SetFailure("open process", ERROR_PROCESS_ABORTED);
            process_ = HandleGuard{};
            return;
        }
        if (!HasCompatibleArchitecture()) {
            SetFailure("architecture check", ERROR_EXE_MACHINE_TYPE_MISMATCH);
            process_ = HandleGuard{};
            return;
        }
        lastError_ = ERROR_SUCCESS;
    }

    ~ModuleLoader() noexcept {
        CleanupPending();
        if (ProcessIsRunning()) {
            // An unexecuted APC or running LoadLibraryW call may still read its
            // argument. Abandon those allocations rather than creating a UAF;
            // the target OS process will reclaim them at exit.
            for (PendingLoad& pending : pending_) {
                static_cast<void>(pending.memory.release());
            }
        }
    }

    ModuleLoader(const ModuleLoader&) = delete;
    ModuleLoader& operator=(const ModuleLoader&) = delete;
    ModuleLoader(ModuleLoader&&) = delete;
    ModuleLoader& operator=(ModuleLoader&&) = delete;

    [[nodiscard]] bool IsValid() const noexcept {
        return static_cast<bool>(process_) && ProcessIsRunning();
    }

    [[nodiscard]] bool Load(const std::wstring& dllPath) noexcept {
        try {
            CleanupPending();
            if (!IsValid()) {
                return SetFailure("load", ERROR_INVALID_HANDLE);
            }

            std::wstring normalizedPath;
            if (!NormalizeExistingFile(dllPath, normalizedPath)) {
                return SetFailure("load path", ::GetLastError());
            }
            pending_.reserve(pending_.size() + 1u);

            LPTHREAD_START_ROUTINE remoteLoadLibrary =
                ResolveRemoteProcedure("LoadLibraryW");
            if (!remoteLoadLibrary) {
                return SetFailure("resolve LoadLibraryW", ERROR_PROC_NOT_FOUND);
            }

            RemoteMemoryGuard remotePath;
            if (!WriteRemotePath(normalizedPath, remotePath)) {
                return false;
            }

            ThreadGuard thread(::CreateRemoteThread(
                process_.get(), nullptr, 0u, remoteLoadLibrary,
                remotePath.get(), 0u, nullptr));
            if (!thread) {
                return SetFailure("CreateRemoteThread", ::GetLastError());
            }

            const DWORD waitResult = thread.wait(kOperationTimeoutMs);
            if (waitResult == WAIT_TIMEOUT || waitResult == WAIT_FAILED) {
                const DWORD error = waitResult == WAIT_TIMEOUT
                    ? WAIT_TIMEOUT
                    : ::GetLastError();
                pending_.emplace_back(
                    PendingKind::RemoteThread, std::move(normalizedPath),
                    std::move(remotePath), std::move(thread));
                return SetFailure("LoadLibraryW wait", error);
            }
            if (waitResult != WAIT_OBJECT_0) {
                return SetFailure("LoadLibraryW wait", ERROR_GEN_FAILURE);
            }

            if (!WaitForPathLoaded(normalizedPath, kUnloadVerifyMs)) {
                return SetFailure("verify loaded module", ERROR_MOD_NOT_FOUND);
            }

            lastError_ = ERROR_SUCCESS;
            return true;
        } catch (...) {
            return SetFailure("load", ERROR_NOT_ENOUGH_MEMORY);
        }
    }

    [[nodiscard]] bool LoadAPC(const std::wstring& dllPath) noexcept {
        try {
            CleanupPending();
            if (!IsValid()) {
                return SetFailure("APC load", ERROR_INVALID_HANDLE);
            }

            std::wstring normalizedPath;
            if (!NormalizeExistingFile(dllPath, normalizedPath)) {
                return SetFailure("APC load path", ::GetLastError());
            }
            if (IsPathLoaded(normalizedPath)) {
                return SetFailure("APC load", ERROR_ALREADY_EXISTS);
            }
            pending_.reserve(pending_.size() + 1u);

            LPTHREAD_START_ROUTINE remoteLoadLibrary =
                ResolveRemoteProcedure("LoadLibraryW");
            if (!remoteLoadLibrary) {
                return SetFailure("resolve APC LoadLibraryW", ERROR_PROC_NOT_FOUND);
            }

            RemoteMemoryGuard remotePath;
            if (!WriteRemotePath(normalizedPath, remotePath)) {
                return false;
            }

            ThreadGuard targetThread = FindTargetThread();
            if (!targetThread) {
                return SetFailure("find APC thread", ERROR_NOT_FOUND);
            }

            if (::QueueUserAPC(
                    reinterpret_cast<PAPCFUNC>(
                        reinterpret_cast<std::uintptr_t>(remoteLoadLibrary)),
                    targetThread.get(),
                    reinterpret_cast<ULONG_PTR>(remotePath.get())) == 0u) {
                return SetFailure("QueueUserAPC", ::GetLastError());
            }

            const ULONGLONG deadline = ::GetTickCount64() + kOperationTimeoutMs;
            do {
                if (IsPathLoaded(normalizedPath)) {
                    lastError_ = ERROR_SUCCESS;
                    return true;
                }
                ::Sleep(kPollIntervalMs);
            } while (::GetTickCount64() < deadline && ProcessIsRunning());

            pending_.emplace_back(
                PendingKind::Apc, std::move(normalizedPath),
                std::move(remotePath), ThreadGuard{});
            return SetFailure("APC LoadLibraryW wait", WAIT_TIMEOUT);
        } catch (...) {
            return SetFailure("APC load", ERROR_NOT_ENOUGH_MEMORY);
        }
    }

    [[nodiscard]] bool Unload(const std::wstring& dllName) noexcept {
        try {
            CleanupPending();
            if (!IsValid()) {
                return SetFailure("unload", ERROR_INVALID_HANDLE);
            }

            RemoteModule module;
            const FindModuleResult findResult = FindRemoteModule(dllName, module);
            if (findResult == FindModuleResult::NotFound) {
                return SetFailure("find unload module", ERROR_MOD_NOT_FOUND);
            }
            if (findResult == FindModuleResult::Ambiguous) {
                return SetFailure("find unload module", ERROR_MORE_DATA);
            }

            LPTHREAD_START_ROUTINE remoteFreeLibrary =
                ResolveRemoteProcedure("FreeLibrary");
            if (!remoteFreeLibrary) {
                return SetFailure("resolve FreeLibrary", ERROR_PROC_NOT_FOUND);
            }

            ThreadGuard thread(::CreateRemoteThread(
                process_.get(), nullptr, 0u, remoteFreeLibrary,
                reinterpret_cast<LPVOID>(module.base), 0u, nullptr));
            if (!thread) {
                return SetFailure("CreateRemoteThread FreeLibrary",
                                  ::GetLastError());
            }

            const DWORD waitResult = thread.wait(kOperationTimeoutMs);
            if (waitResult != WAIT_OBJECT_0) {
                return SetFailure(
                    "FreeLibrary wait",
                    waitResult == WAIT_TIMEOUT ? WAIT_TIMEOUT : ::GetLastError());
            }

            DWORD exitCode = 0u;
            if (!::GetExitCodeThread(thread.get(), &exitCode)) {
                return SetFailure("FreeLibrary result", ::GetLastError());
            }
            if (exitCode == 0u) {
                return SetFailure("FreeLibrary result", ERROR_INVALID_HANDLE);
            }

            const ULONGLONG deadline = ::GetTickCount64() + kUnloadVerifyMs;
            do {
                if (!ContainsModuleBase(module.base)) {
                    lastError_ = ERROR_SUCCESS;
                    return true;
                }
                ::Sleep(kPollIntervalMs);
            } while (::GetTickCount64() < deadline && ProcessIsRunning());
            return SetFailure("verify unloaded module", ERROR_BUSY);
        } catch (...) {
            return SetFailure("unload", ERROR_NOT_ENOUGH_MEMORY);
        }
    }

    [[nodiscard]] DWORD LastError() const noexcept {
        return lastError_;
    }

private:
    static constexpr DWORD kOperationTimeoutMs = 30000u;
    static constexpr DWORD kUnloadVerifyMs = 2000u;
    static constexpr DWORD kPollIntervalMs = 25u;

    enum class PendingKind { RemoteThread, Apc };
    enum class FindModuleResult { Found, NotFound, Ambiguous };

    struct RemoteModule {
        std::wstring name;
        std::wstring path;
        std::uintptr_t base = 0u;
        DWORD size = 0u;
    };

    struct PendingLoad {
        PendingLoad(PendingKind operationKind, std::wstring&& modulePath,
                    RemoteMemoryGuard&& allocation,
                    ThreadGuard&& operationThread) noexcept
            : kind(operationKind), path(std::move(modulePath)),
              memory(std::move(allocation)), thread(std::move(operationThread)) {}

        PendingLoad(PendingLoad&&) noexcept = default;
        PendingLoad& operator=(PendingLoad&&) noexcept = default;
        PendingLoad(const PendingLoad&) = delete;
        PendingLoad& operator=(const PendingLoad&) = delete;

        PendingKind kind;
        std::wstring path;
        RemoteMemoryGuard memory;
        ThreadGuard thread;
    };

    [[nodiscard]] bool ProcessIsRunning() const noexcept {
        return process_ &&
               ::WaitForSingleObject(process_.get(), 0u) == WAIT_TIMEOUT;
    }

    [[nodiscard]] bool HasCompatibleArchitecture() const noexcept {
        ModuleGuard kernel = ModuleGuard::from_name(L"kernel32.dll");
        if (!kernel) {
            return false;
        }

        using IsWow64Process2Fn = BOOL(WINAPI*)(HANDLE, USHORT*, USHORT*);
        const auto isWow64Process2 = reinterpret_cast<IsWow64Process2Fn>(
            ::GetProcAddress(kernel.get(), "IsWow64Process2"));
        if (isWow64Process2) {
            USHORT currentMachine = 0u;
            USHORT currentNative = 0u;
            USHORT targetMachine = 0u;
            USHORT targetNative = 0u;
            if (!isWow64Process2(::GetCurrentProcess(), &currentMachine,
                                 &currentNative) ||
                !isWow64Process2(process_.get(), &targetMachine,
                                 &targetNative)) {
                return false;
            }
            const USHORT currentEffective = currentMachine == IMAGE_FILE_MACHINE_UNKNOWN
                ? currentNative : currentMachine;
            const USHORT targetEffective = targetMachine == IMAGE_FILE_MACHINE_UNKNOWN
                ? targetNative : targetMachine;
            return currentEffective == targetEffective;
        }

        BOOL currentWow64 = FALSE;
        BOOL targetWow64 = FALSE;
        return ::IsWow64Process(::GetCurrentProcess(), &currentWow64) &&
               ::IsWow64Process(process_.get(), &targetWow64) &&
               currentWow64 == targetWow64;
    }

    [[nodiscard]] bool WriteRemotePath(
        const std::wstring& path,
        RemoteMemoryGuard& output) noexcept {
        if (path.size() >= (std::numeric_limits<std::size_t>::max)() /
                sizeof(wchar_t)) {
            return SetFailure("remote path size", ERROR_ARITHMETIC_OVERFLOW);
        }
        const std::size_t byteCount = (path.size() + 1u) * sizeof(wchar_t);
        RemoteMemoryGuard memory(process_.get(), byteCount);
        if (!memory) {
            return SetFailure("VirtualAllocEx", ::GetLastError());
        }
        SIZE_T bytesWritten = 0u;
        if (!::WriteProcessMemory(process_.get(), memory.get(), path.c_str(),
                                  byteCount, &bytesWritten) ||
            bytesWritten != byteCount) {
            return SetFailure("WriteProcessMemory", ::GetLastError());
        }
        output = std::move(memory);
        return true;
    }

    [[nodiscard]] LPTHREAD_START_ROUTINE ResolveRemoteProcedure(
        const char* procedureName) noexcept {
        ModuleGuard kernel = ModuleGuard::from_name(L"kernel32.dll");
        if (!kernel) {
            return nullptr;
        }
        FARPROC localProcedure = ::GetProcAddress(kernel.get(), procedureName);
        if (!localProcedure) {
            return nullptr;
        }

        HMODULE owner = nullptr;
        if (!::GetModuleHandleExW(
                GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                    GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                reinterpret_cast<LPCWSTR>(localProcedure), &owner) ||
            !owner) {
            return nullptr;
        }
        ModuleGuard ownerGuard(owner);

        wchar_t ownerName[MAX_PATH]{};
        if (::GetModuleBaseNameW(::GetCurrentProcess(), ownerGuard.get(),
                                 ownerName, MAX_PATH) == 0u) {
            return nullptr;
        }
        MODULEINFO ownerInfo{};
        if (!::GetModuleInformation(::GetCurrentProcess(), ownerGuard.get(),
                                    &ownerInfo, sizeof(ownerInfo))) {
            return nullptr;
        }
        const std::uintptr_t localBase = reinterpret_cast<std::uintptr_t>(
            ownerInfo.lpBaseOfDll);
        const std::uintptr_t localAddress = reinterpret_cast<std::uintptr_t>(
            localProcedure);
        if (localAddress < localBase) {
            return nullptr;
        }
        const std::uintptr_t offset = localAddress - localBase;
        if (offset >= ownerInfo.SizeOfImage) {
            return nullptr;
        }

        const std::vector<RemoteModule> modules = CaptureRemoteModules();
        for (const RemoteModule& module : modules) {
            if (EqualsIgnoreCase(module.name, ownerName) &&
                offset < module.size) {
                return reinterpret_cast<LPTHREAD_START_ROUTINE>(
                    module.base + offset);
            }
        }
        return nullptr;
    }

    [[nodiscard]] ThreadGuard FindTargetThread() noexcept {
        HandleGuard snapshot(
            ::CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0u));
        if (!snapshot) {
            return ThreadGuard{};
        }
        THREADENTRY32 entry{};
        entry.dwSize = sizeof(entry);
        for (BOOL available = ::Thread32First(snapshot.get(), &entry);
             available != FALSE;
             available = ::Thread32Next(snapshot.get(), &entry)) {
            if (entry.th32OwnerProcessID != pid_) {
                continue;
            }
            ThreadGuard thread(::OpenThread(
                THREAD_SET_CONTEXT | THREAD_QUERY_LIMITED_INFORMATION,
                FALSE, entry.th32ThreadID));
            if (thread) {
                return thread;
            }
        }
        return ThreadGuard{};
    }

    [[nodiscard]] std::vector<RemoteModule> CaptureRemoteModules() const {
        HandleGuard snapshot;
        constexpr unsigned int kAttempts = 4u;
        for (unsigned int attempt = 0u; attempt < kAttempts; ++attempt) {
            snapshot = HandleGuard(::CreateToolhelp32Snapshot(
                TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid_));
            if (snapshot || ::GetLastError() != ERROR_BAD_LENGTH) {
                break;
            }
        }
        if (!snapshot) {
            return {};
        }

        std::vector<RemoteModule> modules;
        MODULEENTRY32W entry{};
        entry.dwSize = sizeof(entry);
        for (BOOL available = ::Module32FirstW(snapshot.get(), &entry);
             available != FALSE;
             available = ::Module32NextW(snapshot.get(), &entry)) {
            modules.push_back(RemoteModule{
                entry.szModule, entry.szExePath,
                reinterpret_cast<std::uintptr_t>(entry.modBaseAddr),
                entry.modBaseSize});
        }
        return modules;
    }

    [[nodiscard]] bool IsPathLoaded(const std::wstring& path) const {
        for (const RemoteModule& module : CaptureRemoteModules()) {
            if (EqualsIgnoreCase(module.path, path)) {
                return true;
            }
        }
        return false;
    }

    [[nodiscard]] bool WaitForPathLoaded(
        const std::wstring& path,
        DWORD timeoutMs) const noexcept {
        const ULONGLONG deadline = ::GetTickCount64() + timeoutMs;
        do {
            if (IsPathLoaded(path)) {
                return true;
            }
            ::Sleep(kPollIntervalMs);
        } while (::GetTickCount64() < deadline && ProcessIsRunning());
        return IsPathLoaded(path);
    }

    [[nodiscard]] bool ContainsModuleBase(std::uintptr_t base) const {
        for (const RemoteModule& module : CaptureRemoteModules()) {
            if (module.base == base) {
                return true;
            }
        }
        return false;
    }

    [[nodiscard]] FindModuleResult FindRemoteModule(
        const std::wstring& value,
        RemoteModule& output) const {
        if (value.empty()) {
            return FindModuleResult::NotFound;
        }
        const bool isPath = value.find_first_of(L"\\/:") != std::wstring::npos;
        std::wstring wanted = value;
        if (isPath && !NormalizePath(value, wanted)) {
            return FindModuleResult::NotFound;
        }

        std::size_t matchCount = 0u;
        for (const RemoteModule& module : CaptureRemoteModules()) {
            const bool matches = isPath
                ? EqualsIgnoreCase(module.path, wanted)
                : EqualsIgnoreCase(module.name, wanted);
            if (matches) {
                output = module;
                ++matchCount;
            }
        }
        if (matchCount == 0u) {
            return FindModuleResult::NotFound;
        }
        return matchCount == 1u
            ? FindModuleResult::Found
            : FindModuleResult::Ambiguous;
    }

    void CleanupPending() noexcept {
        if (!process_) {
            return;
        }
        try {
            const bool running = ProcessIsRunning();
            auto pending = pending_.begin();
            while (pending != pending_.end()) {
                const bool loaded = running && IsPathLoaded(pending->path);
                const bool threadFinished =
                    pending->kind == PendingKind::RemoteThread &&
                    pending->thread.wait(0u) == WAIT_OBJECT_0;
                if (!running || loaded || threadFinished) {
                    pending = pending_.erase(pending);
                } else {
                    ++pending;
                }
            }
        } catch (...) {
        }
    }

    [[nodiscard]] static bool NormalizeExistingFile(
        const std::wstring& input,
        std::wstring& output) noexcept {
        if (!NormalizePath(input, output)) {
            return false;
        }
        const DWORD attributes = ::GetFileAttributesW(output.c_str());
        if (attributes == INVALID_FILE_ATTRIBUTES ||
            (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0u) {
            if (attributes != INVALID_FILE_ATTRIBUTES) {
                ::SetLastError(ERROR_FILE_NOT_FOUND);
            }
            return false;
        }
        return true;
    }

    [[nodiscard]] static bool NormalizePath(
        const std::wstring& input,
        std::wstring& output) noexcept {
        if (input.empty()) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return false;
        }
        try {
            const DWORD required = ::GetFullPathNameW(
                input.c_str(), 0u, nullptr, nullptr);
            if (required == 0u) {
                return false;
            }
            std::vector<wchar_t> buffer(
                static_cast<std::size_t>(required), L'\0');
            const DWORD written = ::GetFullPathNameW(
                input.c_str(), required, buffer.data(), nullptr);
            if (written == 0u || written >= required) {
                return false;
            }
            output.assign(buffer.data(), written);
            return true;
        } catch (...) {
            ::SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return false;
        }
    }

    [[nodiscard]] static bool EqualsIgnoreCase(
        const std::wstring& left,
        const std::wstring& right) noexcept {
        if (left.size() > static_cast<std::size_t>(
                (std::numeric_limits<int>::max)()) ||
            right.size() > static_cast<std::size_t>(
                (std::numeric_limits<int>::max)())) {
            return false;
        }
        return ::CompareStringOrdinal(
                   left.data(), static_cast<int>(left.size()), right.data(),
                   static_cast<int>(right.size()), TRUE) == CSTR_EQUAL;
    }

    bool SetFailure(const char* operation, DWORD error) noexcept {
        lastError_ = error == ERROR_SUCCESS ? ERROR_GEN_FAILURE : error;
        try {
            LOG_ERROR(std::string("ModuleLoader ") + operation +
                      " pid=" + std::to_string(pid_) +
                      " error=" + std::to_string(lastError_));
        } catch (...) {
        }
        return false;
    }

    DWORD pid_ = 0u;
    HandleGuard process_;
    std::vector<PendingLoad> pending_;
    DWORD lastError_ = ERROR_SUCCESS;
};
