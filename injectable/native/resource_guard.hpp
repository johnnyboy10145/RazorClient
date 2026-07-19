#pragma once

#include <windows.h>

class HandleGuard final {
public:
    constexpr HandleGuard() noexcept = default;

    explicit constexpr HandleGuard(HANDLE handle) noexcept
        : handle_(is_valid(handle) ? handle : nullptr) {}

    ~HandleGuard() noexcept {
        reset();
    }

    HandleGuard(const HandleGuard&) = delete;
    HandleGuard& operator=(const HandleGuard&) = delete;

    constexpr HandleGuard(HandleGuard&& other) noexcept
        : handle_(other.release()) {}

    HandleGuard& operator=(HandleGuard&& other) noexcept {
        if (this != &other) {
            reset();
            handle_ = other.release();
        }
        return *this;
    }

    [[nodiscard]] constexpr HANDLE get() const noexcept {
        return handle_;
    }

    [[nodiscard]] constexpr HANDLE release() noexcept {
        HANDLE handle = handle_;
        handle_ = nullptr;
        return handle;
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return is_valid(handle_);
    }

private:
    static constexpr bool is_valid(HANDLE handle) noexcept {
        return handle != nullptr && handle != INVALID_HANDLE_VALUE;
    }

    void reset() noexcept {
        if (is_valid(handle_)) {
            CloseHandle(handle_);
        }
        handle_ = nullptr;
    }

    HANDLE handle_ = nullptr;
};

class ModuleGuard final {
public:
    constexpr ModuleGuard() noexcept = default;

    explicit constexpr ModuleGuard(HMODULE module) noexcept
        : module_(module) {}

    ~ModuleGuard() noexcept = default;

    ModuleGuard(const ModuleGuard&) = delete;
    ModuleGuard& operator=(const ModuleGuard&) = delete;

    constexpr ModuleGuard(ModuleGuard&& other) noexcept
        : module_(other.release()) {}

    ModuleGuard& operator=(ModuleGuard&& other) noexcept {
        if (this != &other) {
            module_ = other.release();
        }
        return *this;
    }

    [[nodiscard]] static ModuleGuard from_name(LPCWSTR module_name) noexcept {
        return ModuleGuard(GetModuleHandleW(module_name));
    }

    [[nodiscard]] constexpr HMODULE get() const noexcept {
        return module_;
    }

    [[nodiscard]] constexpr HMODULE release() noexcept {
        HMODULE module = module_;
        module_ = nullptr;
        return module;
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return module_ != nullptr;
    }

private:
    HMODULE module_ = nullptr;
};

class MemoryGuard final {
public:
    constexpr MemoryGuard() noexcept = default;

    explicit MemoryGuard(SIZE_T size,
                         DWORD allocation_type = MEM_COMMIT | MEM_RESERVE,
                         DWORD protection = PAGE_READWRITE) noexcept
        : memory_(VirtualAlloc(nullptr, size, allocation_type, protection)) {}

    explicit constexpr MemoryGuard(LPVOID memory) noexcept
        : memory_(memory) {}

    ~MemoryGuard() noexcept {
        reset();
    }

    MemoryGuard(const MemoryGuard&) = delete;
    MemoryGuard& operator=(const MemoryGuard&) = delete;

    constexpr MemoryGuard(MemoryGuard&& other) noexcept
        : memory_(other.release()) {}

    MemoryGuard& operator=(MemoryGuard&& other) noexcept {
        if (this != &other) {
            reset();
            memory_ = other.release();
        }
        return *this;
    }

    [[nodiscard]] constexpr LPVOID get() const noexcept {
        return memory_;
    }

    [[nodiscard]] constexpr LPVOID release() noexcept {
        LPVOID memory = memory_;
        memory_ = nullptr;
        return memory;
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return memory_ != nullptr;
    }

private:
    void reset() noexcept {
        if (memory_) {
            VirtualFree(memory_, 0, MEM_RELEASE);
        }
        memory_ = nullptr;
    }

    LPVOID memory_ = nullptr;
};

class RemoteMemoryGuard final {
public:
    constexpr RemoteMemoryGuard() noexcept = default;

    explicit RemoteMemoryGuard(
        HANDLE process,
        SIZE_T size,
        DWORD allocation_type = MEM_COMMIT | MEM_RESERVE,
        DWORD protection = PAGE_READWRITE) noexcept
        : process_(process),
          memory_(is_valid_process(process)
              ? VirtualAllocEx(process, nullptr, size, allocation_type, protection)
              : nullptr) {}

    constexpr RemoteMemoryGuard(HANDLE process, LPVOID memory) noexcept
        : process_(process), memory_(memory) {}

    ~RemoteMemoryGuard() noexcept {
        reset();
    }

    RemoteMemoryGuard(const RemoteMemoryGuard&) = delete;
    RemoteMemoryGuard& operator=(const RemoteMemoryGuard&) = delete;

    constexpr RemoteMemoryGuard(RemoteMemoryGuard&& other) noexcept
        : process_(other.process_), memory_(other.release()) {
        other.process_ = nullptr;
    }

    RemoteMemoryGuard& operator=(RemoteMemoryGuard&& other) noexcept {
        if (this != &other) {
            reset();
            process_ = other.process_;
            memory_ = other.release();
            other.process_ = nullptr;
        }
        return *this;
    }

    [[nodiscard]] constexpr LPVOID get() const noexcept {
        return memory_;
    }

    [[nodiscard]] constexpr HANDLE process() const noexcept {
        return process_;
    }

    [[nodiscard]] constexpr LPVOID release() noexcept {
        LPVOID memory = memory_;
        memory_ = nullptr;
        return memory;
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return memory_ != nullptr;
    }

private:
    static constexpr bool is_valid_process(HANDLE process) noexcept {
        return process != nullptr && process != INVALID_HANDLE_VALUE;
    }

    void reset() noexcept {
        if (memory_ && is_valid_process(process_)) {
            VirtualFreeEx(process_, memory_, 0, MEM_RELEASE);
        }
        memory_ = nullptr;
        process_ = nullptr;
    }

    HANDLE process_ = nullptr;  // Non-owning; must outlive this guard.
    LPVOID memory_ = nullptr;
};

class ThreadGuard final {
public:
    constexpr ThreadGuard() noexcept = default;

    explicit constexpr ThreadGuard(HANDLE thread) noexcept
        : thread_(is_valid(thread) ? thread : nullptr) {}

    ~ThreadGuard() noexcept {
        reset();
    }

    ThreadGuard(const ThreadGuard&) = delete;
    ThreadGuard& operator=(const ThreadGuard&) = delete;

    constexpr ThreadGuard(ThreadGuard&& other) noexcept
        : thread_(other.release()) {}

    ThreadGuard& operator=(ThreadGuard&& other) noexcept {
        if (this != &other) {
            reset();
            thread_ = other.release();
        }
        return *this;
    }

    [[nodiscard]] DWORD wait(DWORD timeout = INFINITE) const noexcept {
        return is_valid(thread_) ? WaitForSingleObject(thread_, timeout) : WAIT_FAILED;
    }

    [[nodiscard]] constexpr HANDLE get() const noexcept {
        return thread_;
    }

    [[nodiscard]] constexpr HANDLE release() noexcept {
        HANDLE thread = thread_;
        thread_ = nullptr;
        return thread;
    }

    [[nodiscard]] explicit constexpr operator bool() const noexcept {
        return is_valid(thread_);
    }

private:
    static constexpr bool is_valid(HANDLE thread) noexcept {
        return thread != nullptr && thread != INVALID_HANDLE_VALUE;
    }

    void reset() noexcept {
        if (is_valid(thread_)) {
            CloseHandle(thread_);
        }
        thread_ = nullptr;
    }

    HANDLE thread_ = nullptr;
};
