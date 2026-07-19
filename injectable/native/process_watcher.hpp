#pragma once

#include <windows.h>

#include "resource_guard.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <thread>
#include <utility>

class ProcessWatcher final {
public:
    ProcessWatcher() : control_(std::make_shared<SharedControl>()) {}

    ~ProcessWatcher() noexcept {
        Stop();
    }

    ProcessWatcher(const ProcessWatcher&) = delete;
    ProcessWatcher& operator=(const ProcessWatcher&) = delete;
    ProcessWatcher(ProcessWatcher&&) = delete;
    ProcessWatcher& operator=(ProcessWatcher&&) = delete;

    [[nodiscard]] bool Start(DWORD pid, std::function<void()> onExit) {
        if (pid == 0u || !onExit) {
            return false;
        }

        HandleGuard process(::OpenProcess(
            PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, pid));
        if (!process) {
            return false;
        }

        HandleGuard stopEvent(::CreateEventW(nullptr, TRUE, FALSE, nullptr));
        if (!stopEvent) {
            return false;
        }

        std::thread completedWorker;
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        if (state_ && !state_->finished.load(std::memory_order_acquire)) {
            return false;
        }
        if (worker_.joinable()) {
            if (worker_.get_id() == std::this_thread::get_id()) {
                return false;
            }
            completedWorker = std::move(worker_);
        }

        const std::uint64_t generation =
            control_->activeGeneration.fetch_add(1u, std::memory_order_acq_rel) +
            1u;
        std::shared_ptr<WatchState> newState;
        try {
            newState = std::make_shared<WatchState>(
                std::move(process), std::move(stopEvent), generation);
            newState->running.store(true, std::memory_order_release);
            worker_ = std::thread(
                &ProcessWatcher::Worker, newState, control_, std::move(onExit));
            state_ = std::move(newState);
        } catch (...) {
            if (newState) {
                newState->running.store(false, std::memory_order_release);
                newState->finished.store(true, std::memory_order_release);
            }
            control_->activeGeneration.fetch_add(1u, std::memory_order_acq_rel);
            if (completedWorker.joinable()) {
                completedWorker.join();
            }
            return false;
        }

        // A naturally completed prior worker is already done, so joining it
        // here cannot block on the new watch or its callback.
        if (completedWorker.joinable()) {
            completedWorker.join();
        }
        return true;
    }

    void Stop() noexcept {
        std::thread workerToJoin;
        {
            std::lock_guard<std::mutex> lock(lifecycleMutex_);
            control_->activeGeneration.fetch_add(1u,
                                                   std::memory_order_acq_rel);
            if (state_) {
                state_->stopRequested.store(true, std::memory_order_release);
                state_->running.store(false, std::memory_order_release);
                if (state_->stopEvent) {
                    ::SetEvent(state_->stopEvent.get());
                }
            }

            if (worker_.joinable()) {
                if (worker_.get_id() == std::this_thread::get_id()) {
                    worker_.detach();
                } else {
                    workerToJoin = std::move(worker_);
                }
            }
            state_.reset();
        }

        if (workerToJoin.joinable()) {
            workerToJoin.join();
        }
    }

    [[nodiscard]] bool IsRunning() const noexcept {
        try {
            std::lock_guard<std::mutex> lock(lifecycleMutex_);
            return state_ &&
                   state_->running.load(std::memory_order_acquire);
        } catch (...) {
            return false;
        }
    }

private:
    struct SharedControl {
        std::atomic<std::uint64_t> activeGeneration{0u};
    };

    struct WatchState {
        WatchState(HandleGuard&& processHandle, HandleGuard&& eventHandle,
                   std::uint64_t watchGeneration) noexcept
            : process(std::move(processHandle)),
              stopEvent(std::move(eventHandle)),
              generation(watchGeneration) {}

        HandleGuard process;
        HandleGuard stopEvent;
        const std::uint64_t generation;
        std::atomic<bool> running{false};
        std::atomic<bool> stopRequested{false};
        std::atomic<bool> finished{false};
    };

    static void Worker(std::shared_ptr<WatchState> state,
                       std::shared_ptr<SharedControl> control,
                       std::function<void()> onExit) noexcept {
        bool processExited = false;
        const HANDLE handles[2] = {
            state->process.get(),
            state->stopEvent.get()
        };

        for (;;) {
            const DWORD waitResult = ::WaitForMultipleObjects(
                2u, handles, FALSE,
                static_cast<DWORD>(std::chrono::milliseconds(1000).count()));
            if (waitResult == WAIT_OBJECT_0) {
                processExited = true;
                break;
            }
            if (waitResult == WAIT_OBJECT_0 + 1u) {
                break;
            }
            if (waitResult != WAIT_TIMEOUT) {
                break;
            }
        }

        const bool stopped =
            state->stopRequested.load(std::memory_order_acquire) ||
            ::WaitForSingleObject(state->stopEvent.get(), 0u) == WAIT_OBJECT_0;
        const bool currentGeneration =
            control->activeGeneration.load(std::memory_order_acquire) ==
            state->generation;

        state->running.store(false, std::memory_order_release);
        if (processExited && !stopped && currentGeneration) {
            try {
                onExit();
            } catch (...) {
                // A user callback must not terminate the monitoring thread.
            }
        }
        state->finished.store(true, std::memory_order_release);
    }

    std::shared_ptr<SharedControl> control_;
    mutable std::mutex lifecycleMutex_;
    std::shared_ptr<WatchState> state_;
    std::thread worker_;
};

