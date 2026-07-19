#pragma once

#include <windows.h>
#include <wincrypt.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

#if defined(_MSC_VER)
#pragma comment(lib, "Advapi32.lib")
#endif

class DataCrypto final {
public:
    DataCrypto() = delete;

    [[nodiscard]] static bool DecryptResource(
        int resourceId,
        const std::string& password,
        std::vector<std::uint8_t>& output) noexcept {
        if (resourceId <= 0) {
            return false;
        }

        const HMODULE module = ::GetModuleHandleW(nullptr);
        if (!module) {
            return false;
        }

        const HRSRC resource = ::FindResourceW(
            module, MAKEINTRESOURCEW(resourceId), MAKEINTRESOURCEW(10));
        if (!resource) {
            return false;
        }

        const DWORD resourceSize = ::SizeofResource(module, resource);
        if (resourceSize == 0u) {
            return false;
        }

        const HGLOBAL loadedResource = ::LoadResource(module, resource);
        if (!loadedResource) {
            return false;
        }

        const void* resourceData = ::LockResource(loadedResource);
        if (!resourceData) {
            return false;
        }

        try {
            const auto* bytes = static_cast<const std::uint8_t*>(resourceData);
            const std::vector<std::uint8_t> encrypted(
                bytes, bytes + static_cast<std::size_t>(resourceSize));
            return DecryptData(encrypted, password, output);
        } catch (...) {
            return false;
        }
    }

    [[nodiscard]] static bool DecryptData(
        const std::vector<std::uint8_t>& input,
        const std::string& password,
        std::vector<std::uint8_t>& output) noexcept {
        if (input.size() > static_cast<std::size_t>(
                (std::numeric_limits<DWORD>::max)())) {
            return false;
        }

        if (input.empty()) {
            try {
                std::vector<std::uint8_t> empty;
                output.swap(empty);
                return true;
            } catch (...) {
                return false;
            }
        }

        try {
            std::vector<std::uint8_t> decrypted(input);
            if (TryCryptoApiDecrypt(decrypted, password)) {
                output.swap(decrypted);
                return true;
            }

            decrypted.assign(input.begin(), input.end());
            XORFallback(decrypted, PasswordFallbackKey(password));
            output.swap(decrypted);
            return true;
        } catch (...) {
            return false;
        }
    }

    [[nodiscard]] static bool SaveToTemp(
        const std::vector<std::uint8_t>& data,
        const std::wstring& fileName,
        std::wstring& outputPath) noexcept {
        if (!IsSafeLeafName(fileName)) {
            return false;
        }

        try {
            std::vector<wchar_t> tempPathBuffer(MAX_PATH + 1u, L'\0');
            DWORD pathLength = ::GetTempPathW(
                static_cast<DWORD>(tempPathBuffer.size()), tempPathBuffer.data());
            if (pathLength == 0u) {
                return false;
            }

            if (pathLength >= tempPathBuffer.size()) {
                tempPathBuffer.assign(static_cast<std::size_t>(pathLength) + 1u,
                                      L'\0');
                pathLength = ::GetTempPathW(
                    static_cast<DWORD>(tempPathBuffer.size()),
                    tempPathBuffer.data());
                if (pathLength == 0u || pathLength >= tempPathBuffer.size()) {
                    return false;
                }
            }

            std::wstring path(tempPathBuffer.data(), pathLength);
            if (!path.empty() && path.back() != L'\\' && path.back() != L'/') {
                path.push_back(L'\\');
            }
            path.append(fileName);
            std::wstring completedPath(path);

            bool writeSucceeded = false;
            {
                FileHandle file(::CreateFileW(
                    path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                    FILE_ATTRIBUTE_TEMPORARY, nullptr));
                if (!file) {
                    return false;
                }

                std::size_t offset = 0u;
                while (offset < data.size()) {
                    const std::size_t remaining = data.size() - offset;
                    const DWORD chunkSize = static_cast<DWORD>((std::min)(
                        remaining,
                        static_cast<std::size_t>(
                            (std::numeric_limits<DWORD>::max)())));
                    DWORD bytesWritten = 0u;
                    if (!::WriteFile(file.get(), data.data() + offset, chunkSize,
                                     &bytesWritten, nullptr) ||
                        bytesWritten != chunkSize) {
                        break;
                    }
                    offset += bytesWritten;
                }

                writeSucceeded = offset == data.size() &&
                                 ::FlushFileBuffers(file.get()) != FALSE;
            }

            if (!writeSucceeded) {
                ::DeleteFileW(path.c_str());
                return false;
            }

            outputPath.swap(completedPath);
            return true;
        } catch (...) {
            return false;
        }
    }

    static void XORFallback(std::vector<std::uint8_t>& data,
                            std::uint32_t key) noexcept {
        std::uint32_t state = key == 0u ? kFallbackNonzeroKey : key;
        for (std::uint8_t& byte : data) {
            state ^= state << 13u;
            state ^= state >> 17u;
            state ^= state << 5u;
            byte ^= static_cast<std::uint8_t>(state & 0xFFu);
        }
    }

private:
    static constexpr std::uint32_t kFallbackNonzeroKey = 0x9E3779B9u;
    static constexpr DWORD kRc4KeyBits = 128u << 16u;

    class ProviderHandle final {
    public:
        ProviderHandle() noexcept = default;
        ~ProviderHandle() noexcept {
            if (value_ != 0u) {
                ::CryptReleaseContext(value_, 0u);
            }
        }
        ProviderHandle(const ProviderHandle&) = delete;
        ProviderHandle& operator=(const ProviderHandle&) = delete;
        [[nodiscard]] HCRYPTPROV* put() noexcept { return &value_; }
        [[nodiscard]] HCRYPTPROV get() const noexcept { return value_; }

    private:
        HCRYPTPROV value_ = 0u;
    };

    class HashHandle final {
    public:
        HashHandle() noexcept = default;
        ~HashHandle() noexcept {
            if (value_ != 0u) {
                ::CryptDestroyHash(value_);
            }
        }
        HashHandle(const HashHandle&) = delete;
        HashHandle& operator=(const HashHandle&) = delete;
        [[nodiscard]] HCRYPTHASH* put() noexcept { return &value_; }
        [[nodiscard]] HCRYPTHASH get() const noexcept { return value_; }

    private:
        HCRYPTHASH value_ = 0u;
    };

    class KeyHandle final {
    public:
        KeyHandle() noexcept = default;
        ~KeyHandle() noexcept {
            if (value_ != 0u) {
                ::CryptDestroyKey(value_);
            }
        }
        KeyHandle(const KeyHandle&) = delete;
        KeyHandle& operator=(const KeyHandle&) = delete;
        [[nodiscard]] HCRYPTKEY* put() noexcept { return &value_; }
        [[nodiscard]] HCRYPTKEY get() const noexcept { return value_; }

    private:
        HCRYPTKEY value_ = 0u;
    };

    class FileHandle final {
    public:
        explicit FileHandle(HANDLE value) noexcept : value_(value) {}
        ~FileHandle() noexcept {
            if (value_ != nullptr && value_ != INVALID_HANDLE_VALUE) {
                ::CloseHandle(value_);
            }
        }
        FileHandle(const FileHandle&) = delete;
        FileHandle& operator=(const FileHandle&) = delete;
        [[nodiscard]] HANDLE get() const noexcept { return value_; }
        [[nodiscard]] explicit operator bool() const noexcept {
            return value_ != nullptr && value_ != INVALID_HANDLE_VALUE;
        }

    private:
        HANDLE value_ = INVALID_HANDLE_VALUE;
    };

    [[nodiscard]] static bool TryCryptoApiDecrypt(
        std::vector<std::uint8_t>& data,
        const std::string& password) noexcept {
        if (password.size() > static_cast<std::size_t>(
                (std::numeric_limits<DWORD>::max)())) {
            return false;
        }

        ProviderHandle provider;
        if (!::CryptAcquireContextW(provider.put(), nullptr, nullptr,
                                    PROV_RSA_FULL, CRYPT_VERIFYCONTEXT)) {
            return false;
        }

        HashHandle hash;
        if (!::CryptCreateHash(provider.get(), CALG_MD5, 0u, 0u, hash.put())) {
            return false;
        }

        const BYTE* passwordBytes = password.empty()
            ? nullptr
            : reinterpret_cast<const BYTE*>(password.data());
        if (!::CryptHashData(hash.get(), passwordBytes,
                             static_cast<DWORD>(password.size()), 0u)) {
            return false;
        }

        KeyHandle key;
        if (!::CryptDeriveKey(provider.get(), CALG_RC4, hash.get(),
                              kRc4KeyBits, key.put())) {
            return false;
        }

        DWORD dataLength = static_cast<DWORD>(data.size());
        if (!::CryptDecrypt(key.get(), 0u, TRUE, 0u, data.data(), &dataLength)) {
            return false;
        }
        data.resize(dataLength);
        return true;
    }

    [[nodiscard]] static std::uint32_t PasswordFallbackKey(
        const std::string& password) noexcept {
        std::uint32_t hash = 0x811C9DC5u;
        for (const unsigned char byte : password) {
            hash ^= byte;
            hash *= 0x01000193u;
        }
        return hash == 0u ? kFallbackNonzeroKey : hash;
    }

    [[nodiscard]] static bool IsSafeLeafName(
        const std::wstring& fileName) noexcept {
        if (fileName.empty() || fileName == L"." || fileName == L"..") {
            return false;
        }
        return fileName.find_first_of(L"\\/:") == std::wstring::npos &&
               fileName.find(L'\0') == std::wstring::npos;
    }
};
