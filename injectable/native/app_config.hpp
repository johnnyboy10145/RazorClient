#pragma once

#include <windows.h>

// Dependency: vcpkg install nlohmann-json:x64-windows
#include <nlohmann/json.hpp>

#include <filesystem>
#include <fstream>
#include <mutex>
#include <string>
#include <system_error>
#include <type_traits>

class Config final {
public:
    [[nodiscard]] static Config& Instance() {
        static Config instance;
        return instance;
    }

    Config(const Config&) = delete;
    Config& operator=(const Config&) = delete;
    Config(Config&&) = delete;
    Config& operator=(Config&&) = delete;

    [[nodiscard]] bool Load() noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (config_path_.empty()) {
                return false;
            }

            std::ifstream input(config_path_);
            if (!input) {
                return false;
            }

            nlohmann::json loaded = nlohmann::json::parse(input, nullptr, false);
            if (loaded.is_discarded() || !loaded.is_object()) {
                return false;
            }

            nlohmann::json merged = DefaultValues();
            for (auto& item : loaded.items()) {
                merged[item.key()] = item.value();
            }
            values_.swap(merged);
            return true;
        } catch (...) {
            return false;
        }
    }

    [[nodiscard]] bool Save() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (config_path_.empty()) {
                return false;
            }

            std::error_code error;
            std::filesystem::create_directories(config_path_.parent_path(), error);
            if (error) {
                return false;
            }

            const std::filesystem::path temporary = config_path_.wstring() + L".tmp";
            {
                std::ofstream output(temporary, std::ios::out | std::ios::trunc);
                if (!output) {
                    return false;
                }
                output << values_.dump(4) << '\n';
                output.flush();
                if (!output.good()) {
                    output.close();
                    std::filesystem::remove(temporary, error);
                    return false;
                }
            }

            if (!MoveFileExW(temporary.c_str(), config_path_.c_str(),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
                std::filesystem::remove(temporary, error);
                return false;
            }
            return true;
        } catch (...) {
            return false;
        }
    }

    [[nodiscard]] std::filesystem::path Path() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return config_path_;
    }

    template <typename T>
    [[nodiscard]] T Get(const std::string& key, const T& default_value) const {
        static_assert(IsSupportedType<T>(),
            "Config::Get<T> supports only std::string, int, bool, and double");
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto value = values_.find(key);
            if (value == values_.end() || value->is_null()) {
                return default_value;
            }
            return value->get<T>();
        } catch (...) {
            return default_value;
        }
    }

    [[nodiscard]] bool Set(const std::string& key, const std::string& value) noexcept {
        return SetValue(key, value);
    }

    [[nodiscard]] bool Set(const std::string& key, const char* value) noexcept {
        try {
            return SetValue(key, std::string(value ? value : ""));
        } catch (...) {
            return false;
        }
    }

    [[nodiscard]] bool Set(const std::string& key, int value) noexcept {
        return SetValue(key, value);
    }

    [[nodiscard]] bool Set(const std::string& key, bool value) noexcept {
        return SetValue(key, value);
    }

    [[nodiscard]] bool Set(const std::string& key, double value) noexcept {
        return SetValue(key, value);
    }

private:
    Config()
        : config_path_(ResolveConfigPath()), values_(DefaultValues()) {
        if (config_path_.empty()) {
            return;
        }

        std::error_code error;
        const bool exists = std::filesystem::exists(config_path_, error);
        if (error) {
            return;
        }
        if (exists) {
            (void)Load();
        } else {
            (void)Save();
        }
    }

    ~Config() noexcept = default;

    template <typename T>
    [[nodiscard]] static constexpr bool IsSupportedType() noexcept {
        return std::is_same_v<T, std::string>
            || std::is_same_v<T, int>
            || std::is_same_v<T, bool>
            || std::is_same_v<T, double>;
    }

    [[nodiscard]] static nlohmann::json DefaultValues() {
        return {
            {"password", "NERVE"},
            {"manifest_url", "https://joisthegayest.com/razorclient/compat.json"},
            {"timeout_ms", 30000},
            {"log_level", "INFO"},
            {"enable_diagnostics", true}
        };
    }

    [[nodiscard]] static std::filesystem::path ResolveConfigPath() noexcept {
        try {
            const DWORD required = GetEnvironmentVariableW(L"LOCALAPPDATA", nullptr, 0);
            if (required == 0) {
                return {};
            }

            std::wstring local_app_data(required, L'\0');
            const DWORD written = GetEnvironmentVariableW(
                L"LOCALAPPDATA", local_app_data.data(), required);
            if (written == 0 || written >= required) {
                return {};
            }
            local_app_data.resize(written);
            return std::filesystem::path(local_app_data) / L"MyApp" / L"config.json";
        } catch (...) {
            return {};
        }
    }

    template <typename T>
    [[nodiscard]] bool SetValue(const std::string& key, const T& value) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            values_[key] = value;
            return true;
        } catch (...) {
            return false;
        }
    }

    mutable std::mutex mutex_;
    std::filesystem::path config_path_;
    nlohmann::json values_;
};
