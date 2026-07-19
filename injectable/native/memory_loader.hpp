#pragma once

#include <windows.h>

#include "resource_guard.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

class MemoryLoader final {
public:
    MemoryLoader() = delete;

    [[nodiscard]] static void* LoadFromMemory(
        const std::vector<std::uint8_t>& imageData) noexcept {
        if (!ValidateImage(imageData)) {
            ::SetLastError(imageData.size() > kMaximumImageSize
                ? ERROR_FILE_TOO_LARGE
                : ERROR_BAD_EXE_FORMAT);
            return nullptr;
        }

        try {
            std::wstring path;
            HandleGuard file;
            if (!CreateTemporaryDll(path, file)) {
                return nullptr;
            }

            if (!WriteAll(file.get(), imageData) ||
                !::FlushFileBuffers(file.get())) {
                const DWORD error = ::GetLastError();
                file = HandleGuard{};
                ::DeleteFileW(path.c_str());
                ::SetLastError(error == ERROR_SUCCESS
                    ? ERROR_WRITE_FAULT : error);
                return nullptr;
            }
            file = HandleGuard{};

            if (!VerifyFile(path, imageData)) {
                const DWORD error = ::GetLastError();
                ::DeleteFileW(path.c_str());
                ::SetLastError(error == ERROR_SUCCESS
                    ? ERROR_CRC : error);
                return nullptr;
            }

            HMODULE module = ::LoadLibraryExW(
                path.c_str(), nullptr,
                LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR |
                    LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
            if (!module) {
                const DWORD error = ::GetLastError();
                ::DeleteFileW(path.c_str());
                ::SetLastError(error);
                return nullptr;
            }

            try {
                std::lock_guard<std::mutex> lock(RegistryMutex());
                Registry().push_back(Entry{module, path});
            } catch (...) {
                ::FreeLibrary(module);
                ::DeleteFileW(path.c_str());
                ::SetLastError(ERROR_NOT_ENOUGH_MEMORY);
                return nullptr;
            }

            ::SetLastError(ERROR_SUCCESS);
            return reinterpret_cast<void*>(module);
        } catch (...) {
            ::SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return nullptr;
        }
    }

    [[nodiscard]] static bool Unload(void* moduleHandle) noexcept {
        if (!moduleHandle) {
            ::SetLastError(ERROR_INVALID_HANDLE);
            return false;
        }

        const HMODULE module = reinterpret_cast<HMODULE>(moduleHandle);
        Entry entry;
        try {
            std::lock_guard<std::mutex> lock(RegistryMutex());
            std::vector<Entry>& entries = Registry();
            const auto match = std::find_if(
                entries.begin(), entries.end(),
                [module](const Entry& candidate) {
                    return candidate.module == module;
                });
            if (match == entries.end()) {
                ::SetLastError(ERROR_INVALID_HANDLE);
                return false;
            }
            entry = std::move(*match);
            entries.erase(match);
        } catch (...) {
            ::SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return false;
        }

        if (!::FreeLibrary(entry.module)) {
            const DWORD error = ::GetLastError();
            try {
                std::lock_guard<std::mutex> lock(RegistryMutex());
                Registry().push_back(std::move(entry));
            } catch (...) {
            }
            ::SetLastError(error);
            return false;
        }

        if (!DeleteOrSchedule(entry.path)) {
            return false;
        }
        ::SetLastError(ERROR_SUCCESS);
        return true;
    }

    static void UnloadAll() noexcept {
        std::vector<Entry> entries;
        try {
            std::lock_guard<std::mutex> lock(RegistryMutex());
            entries.swap(Registry());
        } catch (...) {
            ::SetLastError(ERROR_NOT_ENOUGH_MEMORY);
            return;
        }

        DWORD firstError = ERROR_SUCCESS;
        for (auto entry = entries.rbegin(); entry != entries.rend(); ++entry) {
            if (!::FreeLibrary(entry->module)) {
                if (firstError == ERROR_SUCCESS) {
                    firstError = ::GetLastError();
                }
                continue;
            }
            if (!DeleteOrSchedule(entry->path) &&
                firstError == ERROR_SUCCESS) {
                firstError = ::GetLastError();
            }
        }
        ::SetLastError(firstError);
    }

private:
    static constexpr std::size_t kMaximumImageSize =
        512u * 1024u * 1024u;
    static constexpr WORD kMaximumSections = 96u;
    static constexpr DWORD kIoChunkSize = 64u * 1024u;

    struct Entry {
        HMODULE module = nullptr;
        std::wstring path;
    };

    struct RawRange {
        std::size_t begin = 0u;
        std::size_t end = 0u;
    };

    [[nodiscard]] static std::vector<Entry>& Registry() {
        static std::vector<Entry> entries;
        return entries;
    }

    [[nodiscard]] static std::mutex& RegistryMutex() {
        static std::mutex mutex;
        return mutex;
    }

    [[nodiscard]] static bool IsRangeValid(
        std::size_t offset,
        std::size_t length,
        std::size_t total) noexcept {
        return offset <= total && length <= total - offset;
    }

    template <typename T>
    [[nodiscard]] static bool ReadStructure(
        const std::vector<std::uint8_t>& data,
        std::size_t offset,
        T& output) noexcept {
        if (!IsRangeValid(offset, sizeof(T), data.size())) {
            return false;
        }
        std::memcpy(&output, data.data() + offset, sizeof(T));
        return true;
    }

    [[nodiscard]] static bool ValidateImage(
        const std::vector<std::uint8_t>& data) noexcept {
        if (data.empty() || data.size() > kMaximumImageSize) {
            return false;
        }

        IMAGE_DOS_HEADER dos{};
        if (!ReadStructure(data, 0u, dos) || dos.e_magic != IMAGE_DOS_SIGNATURE ||
            dos.e_lfanew <= 0) {
            return false;
        }
        const std::size_t ntOffset = static_cast<std::size_t>(dos.e_lfanew);

        DWORD signature = 0u;
        if (!ReadStructure(data, ntOffset, signature) ||
            signature != IMAGE_NT_SIGNATURE) {
            return false;
        }

        const std::size_t fileHeaderOffset = ntOffset + sizeof(DWORD);
        if (fileHeaderOffset < ntOffset) {
            return false;
        }
        IMAGE_FILE_HEADER fileHeader{};
        if (!ReadStructure(data, fileHeaderOffset, fileHeader) ||
            fileHeader.Machine != IMAGE_FILE_MACHINE_AMD64 ||
            (fileHeader.Characteristics & IMAGE_FILE_DLL) == 0u ||
            fileHeader.NumberOfSections == 0u ||
            fileHeader.NumberOfSections > kMaximumSections ||
            fileHeader.SizeOfOptionalHeader < sizeof(IMAGE_OPTIONAL_HEADER64)) {
            return false;
        }

        const std::size_t optionalOffset =
            fileHeaderOffset + sizeof(IMAGE_FILE_HEADER);
        if (optionalOffset < fileHeaderOffset ||
            !IsRangeValid(optionalOffset, fileHeader.SizeOfOptionalHeader,
                          data.size())) {
            return false;
        }
        IMAGE_OPTIONAL_HEADER64 optionalHeader{};
        if (!ReadStructure(data, optionalOffset, optionalHeader) ||
            optionalHeader.Magic != IMAGE_NT_OPTIONAL_HDR64_MAGIC ||
            optionalHeader.SizeOfHeaders == 0u ||
            optionalHeader.SizeOfImage < optionalHeader.SizeOfHeaders ||
            optionalHeader.SectionAlignment == 0u ||
            optionalHeader.FileAlignment == 0u ||
            optionalHeader.SizeOfHeaders > data.size()) {
            return false;
        }

        const std::size_t sectionOffset =
            optionalOffset + fileHeader.SizeOfOptionalHeader;
        const std::size_t sectionBytes =
            static_cast<std::size_t>(fileHeader.NumberOfSections) *
            sizeof(IMAGE_SECTION_HEADER);
        if (sectionOffset < optionalOffset ||
            !IsRangeValid(sectionOffset, sectionBytes, data.size()) ||
            sectionOffset + sectionBytes > optionalHeader.SizeOfHeaders) {
            return false;
        }

        std::array<RawRange, kMaximumSections> rawRanges{};
        std::size_t rawRangeCount = 0u;
        for (WORD index = 0u; index < fileHeader.NumberOfSections; ++index) {
            IMAGE_SECTION_HEADER section{};
            const std::size_t currentOffset =
                sectionOffset + static_cast<std::size_t>(index) *
                    sizeof(IMAGE_SECTION_HEADER);
            if (!ReadStructure(data, currentOffset, section)) {
                return false;
            }

            const std::uint64_t virtualExtent = (std::max)(
                static_cast<std::uint64_t>(section.Misc.VirtualSize),
                static_cast<std::uint64_t>(section.SizeOfRawData));
            const std::uint64_t virtualEnd =
                static_cast<std::uint64_t>(section.VirtualAddress) +
                virtualExtent;
            if (virtualEnd > optionalHeader.SizeOfImage) {
                return false;
            }

            if (section.SizeOfRawData == 0u) {
                continue;
            }
            const std::size_t rawBegin = section.PointerToRawData;
            const std::size_t rawSize = section.SizeOfRawData;
            if (rawBegin < optionalHeader.SizeOfHeaders ||
                !IsRangeValid(rawBegin, rawSize, data.size())) {
                return false;
            }
            const std::size_t rawEnd = rawBegin + rawSize;
            for (std::size_t previous = 0u; previous < rawRangeCount;
                 ++previous) {
                if (rawBegin < rawRanges[previous].end &&
                    rawRanges[previous].begin < rawEnd) {
                    return false;
                }
            }
            rawRanges[rawRangeCount++] = RawRange{rawBegin, rawEnd};
        }
        return true;
    }

    [[nodiscard]] static bool GetTemporaryDirectory(
        std::wstring& output) {
        DWORD capacity = MAX_PATH + 1u;
        std::vector<wchar_t> buffer(capacity, L'\0');
        DWORD length = ::GetTempPathW(capacity, buffer.data());
        if (length == 0u) {
            return false;
        }
        if (length >= capacity) {
            capacity = length + 1u;
            buffer.assign(capacity, L'\0');
            length = ::GetTempPathW(capacity, buffer.data());
            if (length == 0u || length >= capacity) {
                return false;
            }
        }
        output.assign(buffer.data(), length);
        if (!output.empty() && output.back() != L'\\' &&
            output.back() != L'/') {
            output.push_back(L'\\');
        }
        return true;
    }

    [[nodiscard]] static bool CreateTemporaryDll(
        std::wstring& outputPath,
        HandleGuard& outputFile) {
        static std::atomic<std::uint64_t> sequence{0u};
        std::wstring directory;
        if (!GetTemporaryDirectory(directory)) {
            return false;
        }

        constexpr unsigned int kMaximumAttempts = 32u;
        for (unsigned int attempt = 0u; attempt < kMaximumAttempts; ++attempt) {
            const std::uint64_t nonce =
                static_cast<std::uint64_t>(
                    std::chrono::steady_clock::now().time_since_epoch().count()) ^
                sequence.fetch_add(1u, std::memory_order_relaxed);
            std::wstring path = directory + L"myapp-plugin-" +
                std::to_wstring(::GetCurrentProcessId()) + L"-" +
                std::to_wstring(nonce) + L".dll";
            HandleGuard file(::CreateFileW(
                path.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr,
                CREATE_NEW, FILE_ATTRIBUTE_TEMPORARY, nullptr));
            if (file) {
                outputPath = std::move(path);
                outputFile = std::move(file);
                return true;
            }
            if (::GetLastError() != ERROR_FILE_EXISTS &&
                ::GetLastError() != ERROR_ALREADY_EXISTS) {
                return false;
            }
        }
        ::SetLastError(ERROR_FILE_EXISTS);
        return false;
    }

    [[nodiscard]] static bool WriteAll(
        HANDLE file,
        const std::vector<std::uint8_t>& data) noexcept {
        std::size_t offset = 0u;
        while (offset < data.size()) {
            const DWORD chunk = static_cast<DWORD>((std::min)(
                data.size() - offset,
                static_cast<std::size_t>(
                    (std::numeric_limits<DWORD>::max)())));
            DWORD written = 0u;
            if (!::WriteFile(file, data.data() + offset, chunk, &written,
                             nullptr) || written != chunk) {
                return false;
            }
            offset += written;
        }
        return true;
    }

    [[nodiscard]] static bool VerifyFile(
        const std::wstring& path,
        const std::vector<std::uint8_t>& expected) noexcept {
        HandleGuard file(::CreateFileW(
            path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
        if (!file) {
            return false;
        }
        LARGE_INTEGER size{};
        if (!::GetFileSizeEx(file.get(), &size) || size.QuadPart < 0 ||
            static_cast<std::uint64_t>(size.QuadPart) != expected.size()) {
            ::SetLastError(ERROR_FILE_INVALID);
            return false;
        }

        std::array<std::uint8_t, kIoChunkSize> buffer{};
        std::size_t offset = 0u;
        while (offset < expected.size()) {
            const DWORD requested = static_cast<DWORD>((std::min)(
                expected.size() - offset, buffer.size()));
            DWORD read = 0u;
            if (!::ReadFile(file.get(), buffer.data(), requested, &read,
                            nullptr) || read != requested ||
                std::memcmp(buffer.data(), expected.data() + offset,
                            requested) != 0) {
                ::SetLastError(ERROR_CRC);
                return false;
            }
            offset += read;
        }
        return true;
    }

    [[nodiscard]] static bool DeleteOrSchedule(
        const std::wstring& path) noexcept {
        if (::DeleteFileW(path.c_str())) {
            return true;
        }
        const DWORD deleteError = ::GetLastError();
        static_cast<void>(::MoveFileExW(
            path.c_str(), nullptr, MOVEFILE_DELAY_UNTIL_REBOOT));
        ::SetLastError(deleteError);
        return false;
    }
};
