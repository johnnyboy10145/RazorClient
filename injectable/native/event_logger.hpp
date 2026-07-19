#pragma once

#include <windows.h>

// wingdi.h defines ERROR as a legacy numeric macro, which prevents the
// required scoped LogLevel::ERROR enumerator from being referenced.
#ifdef ERROR
#undef ERROR
#endif

#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <system_error>

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL
};

class EventLogger final {
public:
    [[nodiscard]] static EventLogger& Instance() noexcept {
        static EventLogger instance;
        return instance;
    }

    EventLogger(const EventLogger&) = delete;
    EventLogger& operator=(const EventLogger&) = delete;
    EventLogger(EventLogger&&) = delete;
    EventLogger& operator=(EventLogger&&) = delete;

    void SetMinLevel(LogLevel level) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            min_level_ = level;
        } catch (...) {
        }
    }

    void Log(LogLevel level, const std::string& message, const char* file, int line) noexcept {
        try {
            std::lock_guard<std::mutex> lock(mutex_);
            if (level < min_level_) {
                return;
            }

            const std::string entry = FormatEntry(level, message, file, line);

            std::cout << entry;
            std::cout.flush();

            OutputDebugStringA(entry.c_str());

            if (log_file_.is_open()) {
                log_file_ << entry;
                log_file_.flush();
            }
        } catch (...) {
            // Logging must never propagate failures into the application.
        }
    }

private:
    EventLogger() noexcept {
        try {
            const DWORD required = GetEnvironmentVariableW(L"LOCALAPPDATA", nullptr, 0);
            if (required == 0) {
                return;
            }

            std::wstring local_app_data(required, L'\0');
            const DWORD written = GetEnvironmentVariableW(
                L"LOCALAPPDATA", local_app_data.data(), required);
            if (written == 0 || written >= required) {
                return;
            }
            local_app_data.resize(written);

            const std::filesystem::path directory =
                std::filesystem::path(local_app_data) / L"MyApp";
            std::error_code error;
            std::filesystem::create_directories(directory, error);
            if (error) {
                return;
            }

            log_file_.open(directory / L"app.log", std::ios::out | std::ios::app);
        } catch (...) {
            // Console and debugger output remain available if file setup fails.
        }
    }

    ~EventLogger() noexcept = default;

    [[nodiscard]] static const char* LevelName(LogLevel level) noexcept {
        switch (level) {
            case LogLevel::DEBUG: return "DEBUG";
            case LogLevel::INFO: return "INFO";
            case LogLevel::WARNING: return "WARNING";
            case LogLevel::ERROR: return "ERROR";
            case LogLevel::FATAL: return "FATAL";
        }
        return "UNKNOWN";
    }

    [[nodiscard]] static std::string FormatEntry(
        LogLevel level, const std::string& message, const char* file, int line) {
        const auto now = std::chrono::system_clock::now();
        const std::time_t current_time = std::chrono::system_clock::to_time_t(now);
        std::tm local_time{};
        if (localtime_s(&local_time, &current_time) != 0) {
            local_time = {};
        }

        std::ostringstream output;
        output << std::put_time(&local_time, "%Y-%m-%d %H:%M:%S")
               << " [" << LevelName(level) << "] "
               << message << " (" << (file ? file : "<unknown>") << ':' << line << ")\n";
        return output.str();
    }

    std::mutex mutex_;
    std::ofstream log_file_;
    LogLevel min_level_ = LogLevel::DEBUG;
};

#define LOG_DEBUG(message) \
    ::EventLogger::Instance().Log(::LogLevel::DEBUG, (message), __FILE__, __LINE__)
#define LOG_INFO(message) \
    ::EventLogger::Instance().Log(::LogLevel::INFO, (message), __FILE__, __LINE__)
#define LOG_WARN(message) \
    ::EventLogger::Instance().Log(::LogLevel::WARNING, (message), __FILE__, __LINE__)
#define LOG_ERROR(message) \
    ::EventLogger::Instance().Log(::LogLevel::ERROR, (message), __FILE__, __LINE__)
#define LOG_FATAL(message) \
    ::EventLogger::Instance().Log(::LogLevel::FATAL, (message), __FILE__, __LINE__)
