#pragma once

#include <windows.h>

#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#ifndef STRING_PROTECT_XOR_KEY
#define STRING_PROTECT_XOR_KEY 0xA7u
#endif

static_assert(STRING_PROTECT_XOR_KEY >= 1u && STRING_PROTECT_XOR_KEY <= 0xFFu,
              "STRING_PROTECT_XOR_KEY must be in the range 1..255");

namespace string_protect_detail {

constexpr std::uint8_t base_key =
    static_cast<std::uint8_t>(STRING_PROTECT_XOR_KEY);

constexpr std::uint8_t key_at(std::size_t index) noexcept {
    const auto mixed = static_cast<std::uint8_t>(
        static_cast<std::uint32_t>(base_key) +
        static_cast<std::uint32_t>(index * 0x3Du) +
        static_cast<std::uint32_t>((index >> 1u) * 0x17u));
    return mixed == 0u ? base_key : mixed;
}

constexpr std::uint64_t mix64(std::uint64_t value) noexcept {
    value += 0x9E3779B97F4A7C15ull;
    value = (value ^ (value >> 30u)) * 0xBF58476D1CE4E5B9ull;
    value = (value ^ (value >> 27u)) * 0x94D049BB133111EBull;
    return value ^ (value >> 31u);
}

inline std::uint64_t make_runtime_key() noexcept {
    static std::atomic<std::uint64_t> sequence{0u};
    const auto timestamp = static_cast<std::uint64_t>(
        std::chrono::high_resolution_clock::now().time_since_epoch().count());
    const auto process_id = static_cast<std::uint64_t>(::GetCurrentProcessId());
    const auto nonce = sequence.fetch_add(1u, std::memory_order_relaxed);
    const auto seed = timestamp ^ (process_id << 32u) ^ nonce;
    const auto key = mix64(seed);
    return key == 0u ? 0xD6E8FEB86659FD93ull : key;
}

constexpr std::uint8_t runtime_key_at(std::uint64_t key,
                                      std::size_t index) noexcept {
    const auto stream = mix64(
        key + static_cast<std::uint64_t>(index) * 0x9E3779B97F4A7C15ull);
    return static_cast<std::uint8_t>(stream >> ((index & 7u) * 8u));
}

}  // namespace string_protect_detail

template <std::size_t N>
struct ProtectedString {
    static_assert(N > 0u, "ProtectedString requires a null-terminated array");

    constexpr explicit ProtectedString(const char (&value)[N]) noexcept
        : encrypted_{} {
        for (std::size_t index = 0; index < N; ++index) {
            encrypted_[index] = static_cast<std::uint8_t>(value[index]) ^
                                string_protect_detail::key_at(index);
        }
    }

    [[nodiscard]] std::string decrypt() const {
        std::string plaintext;
        plaintext.resize(N - 1u);
        for (std::size_t index = 0; index + 1u < N; ++index) {
            plaintext[index] = static_cast<char>(
                encrypted_[index] ^ string_protect_detail::key_at(index));
        }
        return plaintext;
    }

    operator const char*() const noexcept {
        // Valid until the next conversion of a ProtectedString with this N on
        // the current thread. decrypt() is preferable for retained values.
        thread_local std::array<char, N> plaintext{};
        for (std::size_t index = 0; index < N; ++index) {
            plaintext[index] = static_cast<char>(
                encrypted_[index] ^ string_protect_detail::key_at(index));
        }
        plaintext[N - 1u] = '\0';
        return plaintext.data();
    }

    [[nodiscard]] constexpr const std::array<std::uint8_t, N>&
    encrypted_data() const noexcept {
        return encrypted_;
    }

private:
    std::array<std::uint8_t, N> encrypted_;
};

#define OBFUSCATE(value)                                                     \
    ([]() -> const ProtectedString<sizeof(value)>& {                         \
        static constexpr ProtectedString<sizeof(value)> protected_value(    \
            value);                                                          \
        return protected_value;                                              \
    }())

class XORString {
public:
    explicit XORString(std::string_view value)
        : encrypted_(value.size()),
          key_(string_protect_detail::make_runtime_key()) {
        for (std::size_t index = 0; index < value.size(); ++index) {
            encrypted_[index] = static_cast<std::uint8_t>(value[index]) ^
                                string_protect_detail::runtime_key_at(key_, index);
        }
    }

    [[nodiscard]] std::string decrypt() const {
        std::string plaintext;
        plaintext.resize(encrypted_.size());
        for (std::size_t index = 0; index < encrypted_.size(); ++index) {
            plaintext[index] = static_cast<char>(
                encrypted_[index] ^
                string_protect_detail::runtime_key_at(key_, index));
        }
        return plaintext;
    }

private:
    std::vector<std::uint8_t> encrypted_;
    std::uint64_t key_;
};

constexpr std::uint64_t constexpr_hash(const char* data,
                                       std::size_t size) noexcept {
    std::uint64_t hash = 0xCBF29CE484222325ull;
    for (std::size_t index = 0; index < size; ++index) {
        hash ^= static_cast<std::uint8_t>(data[index]);
        hash *= 0x100000001B3ull;
    }
    return hash;
}

template <std::size_t N>
constexpr std::uint64_t constexpr_hash(const char (&value)[N]) noexcept {
    static_assert(N > 0u, "constexpr_hash requires a null-terminated array");
    return constexpr_hash(value, N - 1u);
}

