#pragma once

#include <windows.h>

#include "resource_guard.hpp"

#include <cstddef>

class DirectApi final {
public:
    DirectApi() = delete;

    [[nodiscard]] static HandleGuard OpenProcessHandle(
        DWORD pid,
        DWORD access = PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION |
                       PROCESS_VM_READ | PROCESS_VM_WRITE |
                       PROCESS_CREATE_THREAD) noexcept {
        if (pid == 0u || access == 0u) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return HandleGuard{};
        }
        return HandleGuard(::OpenProcess(access, FALSE, pid));
    }

    [[nodiscard]] static RemoteMemoryGuard AllocateVirtualMemory(
        HANDLE process,
        SIZE_T size,
        DWORD allocationType = MEM_COMMIT | MEM_RESERVE,
        DWORD protection = PAGE_READWRITE) noexcept {
        if (!IsValidHandle(process) || size == 0u || allocationType == 0u ||
            protection == 0u) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return RemoteMemoryGuard{};
        }
        return RemoteMemoryGuard(
            process, size, allocationType, protection);
    }

    [[nodiscard]] static bool WriteVirtualMemory(
        HANDLE process,
        LPVOID destination,
        const void* source,
        SIZE_T size,
        SIZE_T* bytesWritten = nullptr) noexcept {
        if (bytesWritten) {
            *bytesWritten = 0u;
        }
        if (!IsValidHandle(process) || !destination || !source || size == 0u) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return false;
        }

        SIZE_T actualBytesWritten = 0u;
        if (!::WriteProcessMemory(process, destination, source, size,
                                  &actualBytesWritten)) {
            if (bytesWritten) {
                *bytesWritten = actualBytesWritten;
            }
            return false;
        }
        if (bytesWritten) {
            *bytesWritten = actualBytesWritten;
        }
        if (actualBytesWritten != size) {
            ::SetLastError(ERROR_PARTIAL_COPY);
            return false;
        }
        return true;
    }

    [[nodiscard]] static ThreadGuard CreateThread(
        HANDLE process,
        LPTHREAD_START_ROUTINE entrypoint,
        LPVOID parameter = nullptr,
        DWORD creationFlags = 0u) noexcept {
        if (!IsValidHandle(process) || !entrypoint) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return ThreadGuard{};
        }
        return ThreadGuard(::CreateRemoteThread(
            process, nullptr, 0u, entrypoint, parameter, creationFlags,
            nullptr));
    }

    [[nodiscard]] static bool ProtectVirtualMemory(
        HANDLE process,
        LPVOID address,
        SIZE_T size,
        DWORD newProtection,
        DWORD& previousProtection) noexcept {
        if (!IsValidHandle(process) || !address || size == 0u ||
            newProtection == 0u) {
            ::SetLastError(ERROR_INVALID_PARAMETER);
            return false;
        }

        DWORD originalProtection = 0u;
        if (!::VirtualProtectEx(process, address, size, newProtection,
                                &originalProtection)) {
            return false;
        }
        previousProtection = originalProtection;
        return true;
    }

private:
    [[nodiscard]] static constexpr bool IsValidHandle(
        HANDLE handle) noexcept {
        return handle != nullptr && handle != INVALID_HANDLE_VALUE;
    }
};

