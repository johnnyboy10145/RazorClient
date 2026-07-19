#pragma once

#include <windows.h>
#include <tlhelp32.h>
#include <psapi.h>

#include "resource_guard.hpp"

#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <vector>

#if defined(_MSC_VER)
#pragma comment(lib, "Psapi.lib")
#endif

struct ModuleInfo {
    std::wstring name;
    std::uint64_t baseAddress = 0u;
    std::size_t size = 0u;
};

struct ProcessInfo {
    DWORD pid = 0u;
    DWORD parentPid = 0u;
    std::wstring name;
    std::wstring exePath;
    std::vector<ModuleInfo> modules;
};

class ProcessUtils final {
public:
    ProcessUtils() = delete;

    [[nodiscard]] static std::vector<ProcessInfo> GetProcesses() noexcept {
        try {
            return CaptureProcesses();
        } catch (...) {
            return {};
        }
    }

    [[nodiscard]] static std::optional<ProcessInfo> FindByName(
        const std::wstring& name) noexcept {
        try {
            std::vector<ProcessInfo> processes = CaptureProcesses();
            for (ProcessInfo& process : processes) {
                if (EqualsOrdinalIgnoreCase(process.name, name)) {
                    return std::move(process);
                }
            }
        } catch (...) {
        }
        return std::nullopt;
    }

    [[nodiscard]] static std::vector<ProcessInfo> FindByNameAll(
        const std::wstring& name) noexcept {
        try {
            std::vector<ProcessInfo> matches;
            std::vector<ProcessInfo> processes = CaptureProcesses();
            for (ProcessInfo& process : processes) {
                if (EqualsOrdinalIgnoreCase(process.name, name)) {
                    matches.emplace_back(std::move(process));
                }
            }
            return matches;
        } catch (...) {
            return {};
        }
    }

    [[nodiscard]] static std::optional<ProcessInfo> GetParent(
        DWORD pid) noexcept {
        if (pid == 0u) {
            return std::nullopt;
        }

        try {
            std::vector<ProcessInfo> processes = CaptureProcesses();
            DWORD parentPid = 0u;
            for (const ProcessInfo& process : processes) {
                if (process.pid == pid) {
                    parentPid = process.parentPid;
                    break;
                }
            }
            if (parentPid == 0u || parentPid == pid) {
                return std::nullopt;
            }
            for (ProcessInfo& process : processes) {
                if (process.pid == parentPid) {
                    return std::move(process);
                }
            }
        } catch (...) {
        }
        return std::nullopt;
    }

    [[nodiscard]] static std::vector<ModuleInfo> GetModules(
        DWORD pid) noexcept {
        if (pid == 0u) {
            return {};
        }

        try {
            HandleGuard process(::OpenProcess(
                PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, pid));
            if (!process) {
                return {};
            }

            std::vector<HMODULE> handles;
            DWORD bytesNeeded = 0u;
            bool captured = false;
            constexpr unsigned int kMaximumAttempts = 4u;

            for (unsigned int attempt = 0u; attempt < kMaximumAttempts;
                 ++attempt) {
                if (handles.empty()) {
                    if (!::EnumProcessModulesEx(process.get(), nullptr, 0u,
                                                &bytesNeeded,
                                                LIST_MODULES_ALL) ||
                        bytesNeeded == 0u) {
                        return {};
                    }
                    handles.resize(
                        (static_cast<std::size_t>(bytesNeeded) +
                         sizeof(HMODULE) - 1u) /
                        sizeof(HMODULE));
                }

                const std::size_t bufferBytes =
                    handles.size() * sizeof(HMODULE);
                if (bufferBytes > static_cast<std::size_t>(
                        (std::numeric_limits<DWORD>::max)())) {
                    return {};
                }

                bytesNeeded = 0u;
                if (!::EnumProcessModulesEx(
                        process.get(), handles.data(),
                        static_cast<DWORD>(bufferBytes), &bytesNeeded,
                        LIST_MODULES_ALL)) {
                    return {};
                }

                if (bytesNeeded <= bufferBytes) {
                    captured = true;
                    break;
                }

                handles.resize(
                    (static_cast<std::size_t>(bytesNeeded) +
                     sizeof(HMODULE) - 1u) /
                    sizeof(HMODULE));
            }

            if (!captured) {
                return {};
            }

            const std::size_t moduleCount =
                static_cast<std::size_t>(bytesNeeded) / sizeof(HMODULE);
            std::vector<ModuleInfo> modules;
            modules.reserve(moduleCount);
            for (std::size_t index = 0u; index < moduleCount; ++index) {
                MODULEINFO nativeInfo{};
                if (!::GetModuleInformation(process.get(), handles[index],
                                            &nativeInfo,
                                            sizeof(nativeInfo))) {
                    continue;
                }

                std::wstring moduleName = QueryModuleName(
                    process.get(), handles[index]);
                if (moduleName.empty()) {
                    continue;
                }

                modules.push_back(ModuleInfo{
                    std::move(moduleName),
                    static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(
                        nativeInfo.lpBaseOfDll)),
                    static_cast<std::size_t>(nativeInfo.SizeOfImage)});
            }
            return modules;
        } catch (...) {
            return {};
        }
    }

    [[nodiscard]] static bool HasModule(
        DWORD pid,
        const std::wstring& moduleName) noexcept {
        if (moduleName.empty()) {
            return false;
        }
        try {
            const std::vector<ModuleInfo> modules = GetModules(pid);
            for (const ModuleInfo& module : modules) {
                if (EqualsOrdinalIgnoreCase(module.name, moduleName)) {
                    return true;
                }
            }
        } catch (...) {
        }
        return false;
    }

private:
    [[nodiscard]] static std::vector<ProcessInfo> CaptureProcesses() {
        HandleGuard snapshot(
            ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0u));
        if (!snapshot) {
            return {};
        }

        std::vector<ProcessInfo> processes;
        PROCESSENTRY32W entry{};
        entry.dwSize = sizeof(entry);
        for (BOOL available = ::Process32FirstW(snapshot.get(), &entry);
             available != FALSE;
             available = ::Process32NextW(snapshot.get(), &entry)) {
            ProcessInfo process;
            process.pid = entry.th32ProcessID;
            process.parentPid = entry.th32ParentProcessID;
            process.name = entry.szExeFile;
            process.exePath = QueryExecutablePath(process.pid);
            processes.emplace_back(std::move(process));
        }
        return processes;
    }

    [[nodiscard]] static std::wstring QueryExecutablePath(DWORD pid) {
        if (pid == 0u) {
            return {};
        }

        HandleGuard process(::OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid));
        if (!process) {
            return {};
        }

        constexpr DWORD kMaximumPathLength = 32768u;
        DWORD capacity = 512u;
        while (capacity <= kMaximumPathLength) {
            std::vector<wchar_t> buffer(
                static_cast<std::size_t>(capacity), L'\0');
            DWORD length = capacity;
            if (::QueryFullProcessImageNameW(process.get(), 0u, buffer.data(),
                                             &length)) {
                return std::wstring(buffer.data(), length);
            }
            if (::GetLastError() != ERROR_INSUFFICIENT_BUFFER ||
                capacity == kMaximumPathLength) {
                return {};
            }
            capacity = (capacity > kMaximumPathLength / 2u)
                ? kMaximumPathLength
                : capacity * 2u;
        }
        return {};
    }

    [[nodiscard]] static std::wstring QueryModuleName(
        HANDLE process,
        HMODULE module) {
        constexpr DWORD kMaximumNameLength = 32768u;
        DWORD capacity = MAX_PATH;
        while (capacity <= kMaximumNameLength) {
            std::vector<wchar_t> buffer(
                static_cast<std::size_t>(capacity), L'\0');
            const DWORD length = ::GetModuleBaseNameW(
                process, module, buffer.data(), capacity);
            if (length == 0u) {
                return {};
            }
            if (length + 1u < capacity) {
                return std::wstring(buffer.data(), length);
            }
            if (capacity == kMaximumNameLength) {
                return {};
            }
            capacity = (capacity > kMaximumNameLength / 2u)
                ? kMaximumNameLength
                : capacity * 2u;
        }
        return {};
    }

    [[nodiscard]] static bool EqualsOrdinalIgnoreCase(
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
};

