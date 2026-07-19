#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>
#include <tlhelp32.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <wintrust.h>
#include <softpub.h>
#include <psapi.h>
#include <dwmapi.h>
#include <winhttp.h>
#include <filesystem>
#include <fstream>
#include <cwchar>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>
#include <algorithm>
#include <ctime>
#include <atomic>
#include <mutex>
#include <limits>
#include <utility>
#include "resource.h"
#include "compat_public_key.h"
#include "payload_hashes.h"
#include "payload_key.h"
#include "app_config.hpp"
#include "data_crypto.hpp"
#include "event_logger.hpp"
#include "module_loader.hpp"
#include "process_info.hpp"
#include "process_watcher.hpp"
#include "resource_guard.hpp"
#include "string_protect.hpp"

namespace {
std::wstring status = L"Launch Lunar 1.8.9 first, then press Inject.";
std::wstring payloadStatus = L"Payload: checking...";
HWND windowHandle{};
HWND injectButton{};
HWND diagnosticsButton{};
HWND passwordEdit{};
HWND unlockButton{};
HWND detailsEdit{};
HINSTANCE appInstance{};
HFONT titleFont{};
HFONT bodyFont{};
HFONT buttonFont{};
HBRUSH editBrush{};
int injectionExitCode = 1;
std::string fingerprintDetail;
DWORD lastInjectError = 0;
bool passwordAccepted = false;
bool configLoaded = false;
bool diagnosticsInProgress = false;
std::wstring configuredPassword = L"NERVE";
std::wstring effectiveManifestUrl = L"https://joisthegayest.com/razorclient/compat.json";
DWORD configuredTimeoutMs = 30000;
std::string remoteCompatStatus = "not checked";
std::atomic<bool> injectionInProgress{false};
std::mutex statusMutex;
std::mutex detailsMutex;
std::wstring details = L"Ready.";
ProcessWatcher targetWatcher;
ThreadGuard injectionWorkerHandle;
ThreadGuard diagnosticsWorkerHandle;

constexpr DWORD WM_INJECTION_FINISHED = WM_APP + 1;
constexpr DWORD WM_DIAGNOSTICS_FINISHED = WM_APP + 2;
constexpr DWORD WM_TARGET_EXITED = WM_APP + 3;
constexpr size_t MAX_MANIFEST_ENVELOPE_BYTES = 1024 * 1024;
constexpr size_t MAX_MANIFEST_PAYLOAD_BYTES = 512 * 1024;
constexpr unsigned long long MAX_MANIFEST_LIFETIME_SECONDS = 30ULL * 24ULL * 60ULL * 60ULL;
constexpr unsigned long long MANIFEST_CLOCK_SKEW_SECONDS = 5ULL * 60ULL;

std::wstring jvmModulePath(HANDLE process);

#ifdef RAZORCLIENT_RELEASE
constexpr bool REQUIRE_SIGNED_COMPAT = true;
#else
constexpr bool REQUIRE_SIGNED_COMPAT = false;
#endif

COLORREF rgb(unsigned char r, unsigned char g, unsigned char b) {
    return RGB(r, g, b);
}

HBRUSH solidBrush(COLORREF color) {
    return CreateSolidBrush(color);
}

void fillRoundRect(HDC dc, const RECT& rect, int radius, COLORREF color) {
    HBRUSH brush = solidBrush(color);
    HPEN pen = CreatePen(PS_SOLID, 1, color);
    HGDIOBJ oldBrush = SelectObject(dc, brush);
    HGDIOBJ oldPen = SelectObject(dc, pen);
    RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, radius, radius);
    SelectObject(dc, oldBrush);
    SelectObject(dc, oldPen);
    DeleteObject(brush);
    DeleteObject(pen);
}

void frameRoundRect(HDC dc, const RECT& rect, int radius, COLORREF color) {
    HBRUSH brush = reinterpret_cast<HBRUSH>(GetStockObject(NULL_BRUSH));
    HPEN pen = CreatePen(PS_SOLID, 1, color);
    HGDIOBJ oldBrush = SelectObject(dc, brush);
    HGDIOBJ oldPen = SelectObject(dc, pen);
    RoundRect(dc, rect.left, rect.top, rect.right, rect.bottom, radius, radius);
    SelectObject(dc, oldBrush);
    SelectObject(dc, oldPen);
    DeleteObject(pen);
}

HFONT makeFont(int points, int weight) {
    HDC dc = GetDC(nullptr);
    int height = -MulDiv(points, GetDeviceCaps(dc, LOGPIXELSY), 72);
    ReleaseDC(nullptr, dc);
    return CreateFontW(height, 0, 0, 0, weight, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, VARIABLE_PITCH, L"Segoe UI");
}

struct ProcessCandidate {
    DWORD pid{};
    std::wstring exePath;
    std::wstring jvmPath;
    unsigned long long creationTime{};
    bool x64{};
};

struct SupportedLunarBuild {
    const char* name;
    const char* bakeHash;
    const char* mappingsHash;
    const char* adapterVersion;
};

struct LunarBuildDetection {
    std::string bakeHash;
    std::string mappingsHash;
    std::filesystem::path bakePath;
    std::filesystem::path mappingsPath;
    const SupportedLunarBuild* supported{};
    std::string remoteName;
    std::string remoteAdapterVersion;
    DWORD targetPid{};
    unsigned long long processCreationTime{};
    std::string executableHash;
    std::string jvmHash;

    bool isSupported() const {
        return supported != nullptr || !remoteAdapterVersion.empty();
    }

    std::string buildName() const {
        if (supported) {
            return supported->name;
        }
        return remoteName;
    }

    std::string adapterVersion() const {
        if (supported) {
            return supported->adapterVersion;
        }
        return remoteAdapterVersion;
    }
};

static const SupportedLunarBuild SUPPORTED_LUNAR_BUILDS[] = {
    {
        "Lunar 1.8.9 pinned-known",
        "0F013D8D1160D32DFA1A92C8DB05C3A3AC460C7F295C482CC4238B2DC202019B",
        "D72270ADBD6386CC6F18C96C4EA2FC1382F315139EDDB80C74B295E5644946C8",
        "lunar-1.8.9-a"
    },
    {
        "Lunar 1.8.9 friend-racin-2026-07-08",
        "9DB20ACE64D09C83F293001DF03312FABD65D37166C13AE343B5D6E3F8A63D11",
        "D72270ADBD6386CC6F18C96C4EA2FC1382F315139EDDB80C74B295E5644946C8",
        "lunar-1.8.9-a"
    }
};

std::wstring timestamp() {
    SYSTEMTIME time{};
    GetLocalTime(&time);
    wchar_t buffer[64]{};
    swprintf(buffer, 64, L"%04u-%02u-%02u %02u:%02u:%02u",
        time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond);
    return buffer;
}

std::wstring widen(const std::string& value) {
    return std::wstring(value.begin(), value.end());
}

std::string narrow(const std::wstring& value) {
    return std::string(value.begin(), value.end());
}

std::filesystem::path localRazorDirectory() {
    wchar_t local[MAX_PATH]{};
    GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    return std::filesystem::path(local) / L"RazorClient";
}

std::filesystem::path currentExePath() {
    wchar_t path[MAX_PATH]{};
    GetModuleFileNameW(appInstance ? appInstance : GetModuleHandleW(nullptr), path, MAX_PATH);
    return std::filesystem::path(path);
}

void writeLauncherLogLine(const std::wstring& line) {
    auto directory=localRazorDirectory(); std::filesystem::create_directories(directory);
    std::wofstream out(directory/L"launcher.log",std::ios::app); out<<timestamp()<<L" "<<line<<L'\n';
    LOG_INFO(narrow(line));
}

std::wstring currentStatus() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return status;
}

void setStatus(std::wstring value) {
    {
        std::lock_guard<std::mutex> lock(statusMutex);
        status = std::move(value);
    }
    if (windowHandle) PostMessageW(windowHandle, WM_APP, 0, 0);
}

std::wstring currentDetails() {
    std::lock_guard<std::mutex> lock(detailsMutex);
    return details;
}

void setDetails(std::wstring value) {
    {
        std::lock_guard<std::mutex> lock(detailsMutex);
        details = std::move(value);
    }
    if (windowHandle) PostMessageW(windowHandle, WM_APP, 0, 0);
}

bool isHttpsUrl(const std::wstring& value) {
    URL_COMPONENTS components{};
    components.dwStructSize = sizeof(components);
    components.dwSchemeLength = static_cast<DWORD>(-1);
    return !value.empty() && WinHttpCrackUrl(value.c_str(), 0, 0, &components) &&
        components.nScheme == INTERNET_SCHEME_HTTPS;
}

LogLevel parseLogLevel(const std::string& value) {
    if (_stricmp(value.c_str(), "DEBUG") == 0) return LogLevel::DEBUG;
    if (_stricmp(value.c_str(), "WARNING") == 0 || _stricmp(value.c_str(), "WARN") == 0) return LogLevel::WARNING;
    if (_stricmp(value.c_str(), "ERROR") == 0) return LogLevel::ERROR;
    if (_stricmp(value.c_str(), "FATAL") == 0) return LogLevel::FATAL;
    return LogLevel::INFO;
}

void initializeSharedSystems() {
    Config& config = Config::Instance();
    configLoaded = config.Load();
    EventLogger::Instance().SetMinLevel(parseLogLevel(config.Get<std::string>("log_level", "INFO")));
    configuredPassword = widen(config.Get<std::string>("password", "NERVE"));
    if (configuredPassword.empty()) configuredPassword = L"NERVE";
    const std::wstring requestedUrl = widen(config.Get<std::string>(
        "manifest_url", "https://joisthegayest.com/razorclient/compat.json"));
    effectiveManifestUrl = isHttpsUrl(requestedUrl)
        ? requestedUrl
        : L"https://joisthegayest.com/razorclient/compat.json";
    const int timeout = config.Get<int>("timeout_ms", 30000);
    configuredTimeoutMs = static_cast<DWORD>((std::max)(5000, (std::min)(120000, timeout)));
    writeLauncherLogLine(L"Configuration " + std::wstring(configLoaded ? L"loaded" : L"using defaults") +
        L" manifest=" + effectiveManifestUrl + L" timeoutMs=" + std::to_wstring(configuredTimeoutMs));
}

void writeLauncherLog() {
    writeLauncherLogLine(currentStatus());
}

void redraw() {
    if (windowHandle) {
        InvalidateRect(windowHandle, nullptr, TRUE);
    }
}

std::vector<unsigned char> sha256Bytes(const unsigned char* bytes, size_t length) {
    BCRYPT_ALG_HANDLE algorithm{}; BCRYPT_HASH_HANDLE hash{};
    DWORD objectSize=0,cb=0,hashSize=0;
    if (BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)!=0) return {};
    if (BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectSize),sizeof(objectSize),&cb,0)!=0 ||
        BCryptGetProperty(algorithm,BCRYPT_HASH_LENGTH,reinterpret_cast<PUCHAR>(&hashSize),sizeof(hashSize),&cb,0)!=0) {
        BCryptCloseAlgorithmProvider(algorithm,0);
        return {};
    }
    std::vector<unsigned char> object(objectSize),digest(hashSize);
    if (BCryptCreateHash(algorithm,&hash,object.data(),objectSize,nullptr,0,0)!=0) { BCryptCloseAlgorithmProvider(algorithm,0); return {}; }
    const bool hashed = length <= std::numeric_limits<ULONG>::max() &&
        BCryptHashData(hash, const_cast<PUCHAR>(bytes), static_cast<ULONG>(length), 0) == 0 &&
        BCryptFinishHash(hash,digest.data(),hashSize,0) == 0;
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm,0);
    if (!hashed) return {};
    return digest;
}

std::string sha256(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm{}; BCRYPT_HASH_HANDLE hash{};
    DWORD objectSize=0,cb=0,hashSize=0;
    std::ifstream input(path,std::ios::binary);
    if (!input || BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)!=0) return {};
    if (BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectSize),sizeof(objectSize),&cb,0)!=0 ||
        BCryptGetProperty(algorithm,BCRYPT_HASH_LENGTH,reinterpret_cast<PUCHAR>(&hashSize),sizeof(hashSize),&cb,0)!=0) {
        BCryptCloseAlgorithmProvider(algorithm,0);
        return {};
    }
    std::vector<unsigned char> object(objectSize),digest(hashSize),buffer(1024*1024);
    if (BCryptCreateHash(algorithm,&hash,object.data(),objectSize,nullptr,0,0)!=0) { BCryptCloseAlgorithmProvider(algorithm,0); return {}; }
    bool hashed = true;
    while(input){
        input.read(reinterpret_cast<char*>(buffer.data()),buffer.size());
        auto count=input.gcount();
        if(count>0 && BCryptHashData(hash,buffer.data(),static_cast<ULONG>(count),0)!=0) { hashed=false; break; }
    }
    if (input.bad() || !hashed || BCryptFinishHash(hash,digest.data(),hashSize,0)!=0) hashed=false;
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm,0);
    if (!hashed) return {};
    static const char hex[]="0123456789ABCDEF"; std::string result; result.reserve(hashSize*2);
    for(unsigned char value:digest){result.push_back(hex[value>>4]);result.push_back(hex[value&15]);} return result;
}

bool verifyAuthenticode(const std::filesystem::path& path) {
#ifndef RAZORCLIENT_RELEASE
    (void)path;
    return true;
#else
    WINTRUST_FILE_INFO fileInfo{};
    fileInfo.cbStruct = sizeof(fileInfo);
    fileInfo.pcwszFilePath = path.c_str();
    GUID policy = WINTRUST_ACTION_GENERIC_VERIFY_V2;
    WINTRUST_DATA trust{};
    trust.cbStruct = sizeof(trust);
    trust.dwUIChoice = WTD_UI_NONE;
    trust.fdwRevocationChecks = WTD_REVOKE_NONE;
    trust.dwUnionChoice = WTD_CHOICE_FILE;
    trust.pFile = &fileInfo;
    trust.dwStateAction = WTD_STATEACTION_VERIFY;
    LONG result = WinVerifyTrust(nullptr, &policy, &trust);
    trust.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(nullptr, &policy, &trust);
    return result == ERROR_SUCCESS;
#endif
}

bool verifyExpectedPayloadHash(WORD id, const std::filesystem::path& path) {
    const std::string actual = sha256(path);
    if (id == IDR_BOOTSTRAP) return !actual.empty() && actual == RAZORCLIENT_EXPECTED_BOOTSTRAP_SHA256;
    if (id == IDR_AGENT) return !actual.empty() && actual == RAZORCLIENT_EXPECTED_AGENT_SHA256;
    return false;
}

std::vector<unsigned char> base64Decode(const std::string& encoded) {
    if (encoded.empty() || encoded.size() > 8 * 1024 * 1024) return {};
    DWORD required = 0;
    if (!CryptStringToBinaryA(encoded.c_str(), static_cast<DWORD>(encoded.size()),
            CRYPT_STRING_BASE64, nullptr, &required, nullptr, nullptr)) {
        return {};
    }
    std::vector<unsigned char> decoded(required);
    if (!CryptStringToBinaryA(encoded.c_str(), static_cast<DWORD>(encoded.size()),
            CRYPT_STRING_BASE64, decoded.data(), &required, nullptr, nullptr)) {
        return {};
    }
    decoded.resize(required);
    return decoded;
}

bool verifyRsaPssSha256WithKey(const std::string& payload, const std::string& signatureB64,
        const std::string& publicKeyB64) {
    std::vector<unsigned char> keyBlob = base64Decode(publicKeyB64);
    std::vector<unsigned char> signature = base64Decode(signatureB64);
    if (keyBlob.size() < sizeof(BCRYPT_RSAKEY_BLOB) || signature.empty()) return false;

    BCRYPT_RSAKEY_BLOB* header = reinterpret_cast<BCRYPT_RSAKEY_BLOB*>(keyBlob.data());
    const size_t headerSize = sizeof(BCRYPT_RSAKEY_BLOB);
    const size_t required = headerSize + header->cbPublicExp + header->cbModulus;
    if (header->Magic != BCRYPT_RSAPUBLIC_MAGIC || header->cbPrime1 != 0 || header->cbPrime2 != 0 ||
        required != keyBlob.size() || header->cbModulus == 0 || header->cbPublicExp == 0) {
        return false;
    }

    std::vector<unsigned char> digest = sha256Bytes(
        reinterpret_cast<const unsigned char*>(payload.data()), payload.size());
    if (digest.size() != 32) return false;

    BCRYPT_ALG_HANDLE algorithm = nullptr;
    if (BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_RSA_ALGORITHM, nullptr, 0) != 0) return false;
    BCRYPT_KEY_HANDLE key = nullptr;
    if (BCryptImportKeyPair(algorithm, nullptr, BCRYPT_RSAPUBLIC_BLOB,
            &key, keyBlob.data(), static_cast<ULONG>(keyBlob.size()), 0) != 0) {
        BCryptCloseAlgorithmProvider(algorithm, 0);
        return false;
    }
    BCRYPT_PSS_PADDING_INFO padding{};
    padding.pszAlgId = BCRYPT_SHA256_ALGORITHM;
    padding.cbSalt = 32;
    NTSTATUS result = BCryptVerifySignature(key, &padding,
        digest.data(), static_cast<ULONG>(digest.size()),
        signature.data(), static_cast<ULONG>(signature.size()), BCRYPT_PAD_PSS);
    BCryptDestroyKey(key);
    BCryptCloseAlgorithmProvider(algorithm, 0);
    return result == 0;
}

bool verifyRsaPssSha256(const std::string& payload, const std::string& signatureB64) {
    return verifyRsaPssSha256WithKey(payload, signatureB64, RAZORCLIENT_COMPAT_PUBLIC_KEY_B64);
}

bool cryptoSelfCheck() {
    static const std::string payload =
        "{\"manifestVersion\":1,\"issuedAt\":1700000000,\"expires\":1700003600}";
    static const std::string publicKey =
        "UlNBMQAIAAADAAAAAAEAAAAAAAAAAAAAAQABwR/Wm+oC2Droula4eQsIFheS+LGTv6jIqqZ87s3aVB0vZBrbwIdeWSJLaw73Owvr6Kzgg2dNsU5eOucWunyA0IOb0SLRwRmliU66QQHgOzM6MWr/XCjtj0rrvDRutgqOnmw0HK9q8gDB35rG2QTBjPkF8tqc8RxZJ8Ya/7/tDieihHWnfWf8R+kdP3l3B2cNn5/UOzMZoR+x4BU9+Vs25lHgCcx+7rL4yrI86bkphZpuRjtNB6/cWZ5GNCBGDCg9FmvrsUQ4CreS2TdPRuo72dAkg+kJRWvKBMKVoTxkSa7+EPv8/hDUOfex8BdWiPW/lv8Toj77omfMwNRaLR4gsQ==";
    static const std::string signature =
        "bG9iHwPxvpaBdYsKZWt7ERZcj88sNwP8FCBNjDtYaW72CvjCVJ5H4qI1U6x/JJ8l8REZ7/GyIAC7dsZTZLZghLEY51U7Hi6WURdJXuAFWTpmKwkIN+d7EfHT+Uhd/InwtSEtgLVoDBepfiYWDcaYaUuSYyx2oCX9Lhj30anQtVsdQPqXoXfKzKvzKd0KpRMezc3WbNLpeRAL1BUmiA+wY7O5guVxMyEwTf+oybZDvSqt688pMm4rcu0BEZkxZRQhITy/5EZrIEgxksz1kfbXo4yiRohp5EQX9zSRr82h/CFYblKHQufkEEfDgQChQCZKU3gvTrL+WUx5gH8a+p5yJw==";
    if (!verifyRsaPssSha256WithKey(payload, signature, publicKey)) return false;
    std::string tampered = payload;
    tampered.back() = ']';
    return !verifyRsaPssSha256WithKey(tampered, signature, publicKey);
}

bool embeddedResourceExists(WORD id) {
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(id), RT_RCDATA);
    return resource && SizeofResource(module, resource) > 0;
}

bool embeddedPayloadAvailable() {
    return embeddedResourceExists(IDR_BOOTSTRAP) && embeddedResourceExists(IDR_AGENT);
}

bool embeddedResourceHasPlaintextMagic(WORD id) {
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (!resource || SizeofResource(module, resource) < 2u) return false;
    HGLOBAL loaded = LoadResource(module, resource);
    const auto* bytes = loaded ? static_cast<const unsigned char*>(LockResource(loaded)) : nullptr;
    if (!bytes) return false;
    return (id == IDR_BOOTSTRAP && bytes[0] == 'M' && bytes[1] == 'Z') ||
        (id == IDR_AGENT && bytes[0] == 'P' && bytes[1] == 'K');
}

std::string readTextFileBounded(const std::filesystem::path& path, size_t maximumBytes) {
    std::error_code ec;
    const auto size = std::filesystem::file_size(path, ec);
    if (ec || size == 0 || size > maximumBytes) return {};
    std::ifstream input(path, std::ios::binary);
    if (!input) return {};
    std::string value(static_cast<size_t>(size), '\0');
    input.read(value.data(), static_cast<std::streamsize>(value.size()));
    return input && input.gcount() == static_cast<std::streamsize>(value.size()) ? value : std::string{};
}

bool writeTextFileAtomic(const std::filesystem::path& path, const std::string& text) {
    if (text.empty() || text.size() > MAX_MANIFEST_ENVELOPE_BYTES) return false;
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    if (ec) return false;
    const std::filesystem::path temporary = path.wstring() + L".tmp-" +
        std::to_wstring(GetCurrentProcessId()) + L"-" + std::to_wstring(GetTickCount64());
    {
        std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
        output.write(text.data(), static_cast<std::streamsize>(text.size()));
        output.flush();
        if (!output.good()) {
            output.close();
            std::filesystem::remove(temporary, ec);
            return false;
        }
    }
    if (!MoveFileExW(temporary.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        std::filesystem::remove(temporary, ec);
        return false;
    }
    return true;
}

std::filesystem::path compatCachePath() {
    return localRazorDirectory() / L"compat-cache.json";
}

std::filesystem::path compatVersionPath() {
    return localRazorDirectory() / L"compat-version.txt";
}

std::string extractJsonStringField(const std::string& object, const std::string& field);
bool verifyManifestEnvelope(const std::string& envelope, std::string& payload);
bool manifestPayloadPolicyValid(const std::string& payload, unsigned long long& version);

bool fetchRemoteCompatManifest(std::string& manifest) {
    remoteCompatStatus = "fetch failed";
    URL_COMPONENTS parts{};
    wchar_t host[256]{};
    wchar_t path[2048]{};
    parts.dwStructSize = sizeof(parts);
    parts.lpszHostName = host;
    parts.dwHostNameLength = 256;
    parts.lpszUrlPath = path;
    parts.dwUrlPathLength = 2048;
    if (!WinHttpCrackUrl(effectiveManifestUrl.c_str(), 0, 0, &parts)) {
        remoteCompatStatus = "invalid manifest URL";
        return false;
    }
    if (parts.nScheme != INTERNET_SCHEME_HTTPS) {
        remoteCompatStatus = "compatibility manifest must use HTTPS";
        return false;
    }

    HINTERNET session = WinHttpOpen(L"RazorClient/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        remoteCompatStatus = "WinHttpOpen failed";
        return false;
    }
    WinHttpSetTimeouts(session, 3000, 3000, 5000, 5000);
    DWORD redirectPolicy = WINHTTP_OPTION_REDIRECT_POLICY_DISALLOW_HTTPS_TO_HTTP;
    WinHttpSetOption(session, WINHTTP_OPTION_REDIRECT_POLICY, &redirectPolicy, sizeof(redirectPolicy));

    HINTERNET connect = WinHttpConnect(session, std::wstring(host, parts.dwHostNameLength).c_str(), parts.nPort, 0);
    if (!connect) {
        WinHttpCloseHandle(session);
        remoteCompatStatus = "WinHttpConnect failed";
        return false;
    }

    DWORD flags = WINHTTP_FLAG_SECURE;
    HINTERNET request = WinHttpOpenRequest(connect, L"GET", std::wstring(path, parts.dwUrlPathLength).c_str(), nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, flags);
    if (!request) {
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        remoteCompatStatus = "WinHttpOpenRequest failed";
        return false;
    }

    bool ok = WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0)
        && WinHttpReceiveResponse(request, nullptr);
    if (ok) {
        DWORD statusCode = 0;
        DWORD statusSize = sizeof(statusCode);
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, nullptr, &statusCode, &statusSize, nullptr);
        ok = statusCode == 200;
        if (!ok) {
            remoteCompatStatus = "HTTP status " + std::to_string(statusCode);
        }
        DWORD contentLength = 0;
        DWORD contentLengthSize = sizeof(contentLength);
        if (ok && WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                nullptr, &contentLength, &contentLengthSize, nullptr) &&
                contentLength > MAX_MANIFEST_ENVELOPE_BYTES) {
            ok = false;
            remoteCompatStatus = "manifest response exceeds size limit";
        }
    }

    if (ok) {
        std::string body;
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
            if (available > MAX_MANIFEST_ENVELOPE_BYTES || body.size() > MAX_MANIFEST_ENVELOPE_BYTES - available) {
                ok = false;
                remoteCompatStatus = "manifest response exceeds size limit";
                break;
            }
            std::vector<char> buffer(available);
            DWORD read = 0;
            if (!WinHttpReadData(request, buffer.data(), available, &read)) {
                ok = false;
                remoteCompatStatus = "WinHttpReadData failed";
                break;
            }
            body.append(buffer.data(), buffer.data() + read);
        }
        if (ok && !body.empty() && body.size() <= MAX_MANIFEST_ENVELOPE_BYTES) {
            manifest = body;
            remoteCompatStatus = "remote manifest fetched";
        }
    } else if (remoteCompatStatus == "fetch failed") {
        remoteCompatStatus = "request failed";
    }

    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return ok && !manifest.empty();
}

std::string loadCompatManifest() {
    std::string manifest;
    if (fetchRemoteCompatManifest(manifest)) {
        std::string verified;
        if (verifyManifestEnvelope(manifest, verified)) {
            unsigned long long version = 0;
            if (manifestPayloadPolicyValid(verified, version) &&
                    writeTextFileAtomic(compatVersionPath(), std::to_string(version)) &&
                    writeTextFileAtomic(compatCachePath(), manifest)) {
                remoteCompatStatus += "; signed manifest accepted";
                return verified;
            }
            remoteCompatStatus += "; signed manifest policy/cache update rejected";
        }
        if (!REQUIRE_SIGNED_COMPAT && manifest.find("\"payload\"") == std::string::npos) {
            remoteCompatStatus += "; unsigned manifest accepted in debug mode";
            return manifest;
        }
        remoteCompatStatus += "; manifest signature rejected";
    }
    manifest = readTextFileBounded(compatCachePath(), MAX_MANIFEST_ENVELOPE_BYTES);
    if (!manifest.empty()) {
        std::string verified;
        unsigned long long version = 0;
        if (verifyManifestEnvelope(manifest, verified) && manifestPayloadPolicyValid(verified, version)) {
            remoteCompatStatus += "; using cached signed manifest";
            return verified;
        }
        if (!REQUIRE_SIGNED_COMPAT && manifest.find("\"payload\"") == std::string::npos) {
            remoteCompatStatus += "; using cached unsigned manifest in debug mode";
            return manifest;
        }
        remoteCompatStatus += "; cached manifest signature rejected";
    }
    return {};
}

std::string extractJsonStringField(const std::string& object, const std::string& field) {
    std::string needle = "\"" + field + "\"";
    size_t pos = object.find(needle);
    if (pos == std::string::npos) {
        return {};
    }
    pos = object.find(':', pos + needle.size());
    if (pos == std::string::npos) {
        return {};
    }
    pos = object.find('"', pos + 1);
    if (pos == std::string::npos) {
        return {};
    }
    size_t end = object.find('"', pos + 1);
    if (end == std::string::npos) {
        return {};
    }
    return object.substr(pos + 1, end - pos - 1);
}

bool verifyManifestEnvelope(const std::string& envelope, std::string& payload) {
    payload.clear();
    if (envelope.empty() || envelope.size() > MAX_MANIFEST_ENVELOPE_BYTES) return false;
    const size_t firstEnvelope = envelope.find_first_not_of(" \t\r\n");
    const size_t lastEnvelope = envelope.find_last_not_of(" \t\r\n");
    if (firstEnvelope == std::string::npos || lastEnvelope <= firstEnvelope ||
            envelope[firstEnvelope] != '{' || envelope[lastEnvelope] != '}') return false;
    const auto uniqueField = [&](const char* field) {
        const std::string needle = std::string("\"") + field + "\"";
        const size_t first = envelope.find(needle);
        return first != std::string::npos && envelope.find(needle, first + needle.size()) == std::string::npos;
    };
    if (!uniqueField("schemaVersion") || !uniqueField("payload") || !uniqueField("signature")) return false;
    const std::string schema = extractJsonStringField(envelope, "schemaVersion");
    const std::string payloadB64 = extractJsonStringField(envelope, "payload");
    const std::string signatureB64 = extractJsonStringField(envelope, "signature");
    if (schema != "1" || payloadB64.empty() || signatureB64.empty()) return false;
    std::vector<unsigned char> decoded = base64Decode(payloadB64);
    if (decoded.empty() || decoded.size() > MAX_MANIFEST_PAYLOAD_BYTES) return false;
    payload.assign(reinterpret_cast<const char*>(decoded.data()), decoded.size());
    if (!verifyRsaPssSha256(payload, signatureB64)) {
        payload.clear();
        return false;
    }
    const size_t first = payload.find_first_not_of(" \t\r\n");
    const size_t last = payload.find_last_not_of(" \t\r\n");
    return first != std::string::npos && last > first && payload[first] == '{' && payload[last] == '}';
}

bool jsonBooleanFieldTrue(const std::string& object, const std::string& field) {
    std::string needle = "\"" + field + "\"";
    size_t pos = object.find(needle);
    if (pos == std::string::npos) {
        return false;
    }
    pos = object.find(':', pos + needle.size());
    if (pos == std::string::npos) {
        return false;
    }
    size_t value = object.find_first_not_of(" \t\r\n", pos + 1);
    return value != std::string::npos && object.compare(value, 4, "true") == 0;
}

bool jsonUnsignedLongField(const std::string& object, const std::string& field, unsigned long long& value) {
    const std::string needle = "\"" + field + "\"";
    size_t pos = object.find(needle);
    if (pos == std::string::npos) return false;
    pos = object.find(':', pos + needle.size());
    if (pos == std::string::npos) return false;
    pos = object.find_first_not_of(" \t\r\n", pos + 1);
    if (pos == std::string::npos || pos >= object.size() || object[pos] < '0' || object[pos] > '9') return false;
    size_t end = pos;
    while (end < object.size() && object[end] >= '0' && object[end] <= '9') ++end;
    try {
        value = std::stoull(object.substr(pos, end - pos));
        return true;
    } catch (...) {
        return false;
    }
}

bool manifestPayloadPolicyValid(const std::string& payload, unsigned long long& version) {
    unsigned long long issuedAt = 0;
    unsigned long long expires = 0;
    version = 0;
    const auto hasUniqueField = [&](const char* field) {
        const std::string needle = std::string("\"") + field + "\"";
        const size_t first = payload.find(needle);
        return first != std::string::npos && payload.find(needle, first + needle.size()) == std::string::npos;
    };
    if (!hasUniqueField("issuedAt") || !hasUniqueField("expires") || !hasUniqueField("manifestVersion") ||
            !jsonUnsignedLongField(payload, "issuedAt", issuedAt) ||
            !jsonUnsignedLongField(payload, "expires", expires) ||
            !jsonUnsignedLongField(payload, "manifestVersion", version) || version == 0) {
        return false;
    }
    const unsigned long long now = static_cast<unsigned long long>(std::time(nullptr));
    if (issuedAt > now + MANIFEST_CLOCK_SKEW_SECONDS || expires < now || expires <= issuedAt ||
            expires - issuedAt > MAX_MANIFEST_LIFETIME_SECONDS) {
        return false;
    }
    const std::string acceptedText = readTextFileBounded(compatVersionPath(), 64);
    if (!acceptedText.empty()) {
        try {
            const unsigned long long acceptedVersion = std::stoull(acceptedText);
            if (version < acceptedVersion) return false;
        } catch (...) {
            return false;
        }
    }
    return true;
}

bool manifestPayloadFresh(const std::string& payload) {
    unsigned long long expires = 0;
    if (!jsonUnsignedLongField(payload, "expires", expires)) return !REQUIRE_SIGNED_COMPAT;
    return expires >= static_cast<unsigned long long>(std::time(nullptr));
}

bool isKnownAdapter(const std::string& adapterVersion) {
    for (const auto& build : SUPPORTED_LUNAR_BUILDS) {
        if (adapterVersion == build.adapterVersion) {
            return true;
        }
    }
    return false;
}

bool knownMappingsAdapter(const std::string& mappingsHash, std::string& adapterVersion) {
    for (const auto& build : SUPPORTED_LUNAR_BUILDS) {
        if (mappingsHash == build.mappingsHash) {
            adapterVersion = build.adapterVersion;
            return true;
        }
    }
    return false;
}

bool fileContainsMarkers(const std::filesystem::path& path, const std::vector<std::string>& markers) {
    if (markers.empty()) return true;
    std::ifstream input(path, std::ios::binary);
    if (!input) return false;
    size_t longest = 0;
    for (const auto& marker : markers) longest = std::max(longest, marker.size());
    std::string carry;
    std::vector<char> buffer(1024 * 1024);
    std::vector<bool> found(markers.size(), false);
    size_t foundCount = 0;
    while (input && foundCount < markers.size()) {
        input.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
        std::streamsize count = input.gcount();
        if (count <= 0) break;
        std::string chunk = carry + std::string(buffer.data(), static_cast<size_t>(count));
        for (size_t index = 0; index < markers.size(); ++index) {
            if (!found[index] && chunk.find(markers[index]) != std::string::npos) {
                found[index] = true;
                ++foundCount;
            }
        }
        size_t keep = longest > 0 ? longest - 1 : 0;
        carry = chunk.size() > keep ? chunk.substr(chunk.size() - keep) : chunk;
    }
    return foundCount == markers.size();
}

bool genericLunar189Compatible(const LunarBuildDetection& detection, std::string& adapterVersion) {
    if (detection.bakePath.empty() || !knownMappingsAdapter(detection.mappingsHash, adapterVersion)) return false;
    std::error_code ec;
    auto size = std::filesystem::file_size(detection.bakePath, ec);
    if (ec || size < 10ULL * 1024ULL * 1024ULL || size > 250ULL * 1024ULL * 1024ULL) return false;
    return fileContainsMarkers(detection.bakePath, {
        "net.minecraft.client.Minecraft",
        "com.lunarclient.apollo.network.ApolloNetworkManager"
    });
}

bool remoteManifestApproves(const LunarBuildDetection& detection, std::string& name, std::string& adapterVersion) {
    if (detection.bakeHash.empty() || detection.mappingsHash.empty() || detection.targetPid == 0 ||
            detection.executableHash.empty() || detection.jvmHash.empty()) {
        remoteCompatStatus = "missing local fingerprint";
        return false;
    }
    std::string manifest = loadCompatManifest();
    if (manifest.empty()) {
        return false;
    }

    size_t search = 0;
    while (true) {
        size_t bake = manifest.find(detection.bakeHash, search);
        if (bake == std::string::npos) {
            break;
        }
        size_t start = manifest.rfind('{', bake);
        size_t end = manifest.find('}', bake);
        if (start != std::string::npos && end != std::string::npos && end > start) {
            std::string object = manifest.substr(start, end - start + 1);
            std::string objectBake = extractJsonStringField(object, "bakeHash");
            std::string objectMappings = extractJsonStringField(object, "mappingsHash");
            std::string objectAdapter = extractJsonStringField(object, "adapterVersion");
            std::string objectExecutable = extractJsonStringField(object, "executableHash");
            std::string objectJvm = extractJsonStringField(object, "jvmHash");
            std::string objectArchitecture = extractJsonStringField(object, "architecture");
            const bool runtimeBound = objectExecutable == detection.executableHash &&
                objectJvm == detection.jvmHash && objectArchitecture == "x64";
            if (objectBake == detection.bakeHash && objectMappings == detection.mappingsHash && runtimeBound &&
                jsonBooleanFieldTrue(object, "approved") &&
                isKnownAdapter(objectAdapter) && manifestPayloadFresh(manifest)) {
                name = extractJsonStringField(object, "name");
                adapterVersion = objectAdapter;
                if (name.empty()) {
                    name = "Remote Lunar 1.8.9 approved";
                }
                remoteCompatStatus += "; remote build approved";
                return true;
            }
        }
        search = bake + detection.bakeHash.size();
    }
    remoteCompatStatus += "; no approved remote match";
    return false;
}

std::wstring payloadSummary(const wchar_t* label, const std::filesystem::path& path) {
    std::error_code ec;
    auto size = std::filesystem::file_size(path, ec);
    std::wstringstream out;
    out << label << L" path=" << path.wstring() << L" size=" << (ec ? 0 : size) << L" sha256=" << widen(sha256(path));
    return out.str();
}

std::filesystem::path lunarMultiverDirectory() {
    wchar_t home[MAX_PATH]{};
    GetEnvironmentVariableW(L"USERPROFILE", home, MAX_PATH);
    return std::filesystem::path(home) / L".lunarclient" / L"offline" / L"multiver";
}

std::vector<std::filesystem::path> findBakeCandidates(const std::filesystem::path& multiver) {
    std::vector<std::filesystem::path> candidates;
    std::error_code ec;
    auto cache = multiver / L"cache";
    if (!std::filesystem::is_directory(cache, ec)) {
        return candidates;
    }
    for (const auto& entry : std::filesystem::recursive_directory_iterator(cache, ec)) {
        if (ec) {
            break;
        }
        if (entry.is_regular_file(ec) && entry.path().filename() == L"bake.zip") {
            candidates.push_back(entry.path());
        }
    }
    std::sort(candidates.begin(), candidates.end(), [](const std::filesystem::path& left, const std::filesystem::path& right) {
        std::error_code leftError, rightError;
        auto leftTime = std::filesystem::last_write_time(left, leftError);
        auto rightTime = std::filesystem::last_write_time(right, rightError);
        if (leftError || rightError) return left.wstring() < right.wstring();
        return leftTime > rightTime;
    });
    return candidates;
}

unsigned long long fileTimeValue(const FILETIME& value) {
    ULARGE_INTEGER integer{};
    integer.LowPart = value.dwLowDateTime;
    integer.HighPart = value.dwHighDateTime;
    return integer.QuadPart;
}

unsigned long long fileLastWriteTime(const std::filesystem::path& path) {
    WIN32_FILE_ATTRIBUTE_DATA attributes{};
    return GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &attributes)
        ? fileTimeValue(attributes.ftLastWriteTime) : 0;
}

LunarBuildDetection detectLunarBuild(const ProcessCandidate* target = nullptr) {
    LunarBuildDetection detection;
    if (target) {
        detection.targetPid = target->pid;
        detection.processCreationTime = target->creationTime;
        detection.executableHash = sha256(target->exePath);
        detection.jvmHash = sha256(target->jvmPath);
        if (!target->x64 || target->creationTime == 0) {
            fingerprintDetail = "selected Lunar JVM architecture or creation time unavailable";
            return detection;
        }
    }
    auto multiver = lunarMultiverDirectory();
    detection.mappingsPath = multiver / L"lunar-platform-mappings-v1_8.jar";
    detection.mappingsHash = sha256(detection.mappingsPath);

    std::vector<std::filesystem::path> bakeCandidates = findBakeCandidates(multiver);
    std::stringstream detail;
    detail << "pid=" << detection.targetPid << " processCreated=" << detection.processCreationTime
           << " exeHash=" << detection.executableHash << " jvmHash=" << detection.jvmHash
           << " mappings=" << detection.mappingsHash << " mappingsPath=" << narrow(detection.mappingsPath.wstring());

    if (!bakeCandidates.empty()) {
        auto selected = bakeCandidates.end();
        constexpr unsigned long long launchWriteTolerance = 5ULL * 60ULL * 10000000ULL;
        for (auto candidate = bakeCandidates.begin(); candidate != bakeCandidates.end(); ++candidate) {
            const unsigned long long written = fileLastWriteTime(*candidate);
            if (!target || target->creationTime == 0 || (written != 0 && written <= target->creationTime + launchWriteTolerance)) {
                selected = candidate;
                break;
            }
        }
        if (selected == bakeCandidates.end()) {
            detail << " bake=(no candidate bound to selected process start)";
            fingerprintDetail = detail.str();
            return detection;
        }
        const auto& bakePath = *selected;
        std::string bakeHash = sha256(bakePath);
        detail << " bakeCandidate=" << bakeHash << " path=" << narrow(bakePath.wstring());
        detail << " ignoredBakeCandidates=" << (bakeCandidates.size() - 1);
        detection.bakeHash = bakeHash;
        detection.bakePath = bakePath;
        for (const auto& build : SUPPORTED_LUNAR_BUILDS) {
            if (bakeHash == build.bakeHash && detection.mappingsHash == build.mappingsHash) {
                detection.supported = &build;
                fingerprintDetail = detail.str() + " supportedBuild=" + build.name + " adapter=" + build.adapterVersion;
                return detection;
            }
        }
    }

    if (bakeCandidates.empty()) {
        detail << " bake=(missing)";
    }
    if (!detection.bakeHash.empty()) {
        std::string remoteName;
        std::string remoteAdapter;
        if (remoteManifestApproves(detection, remoteName, remoteAdapter)) {
            detection.remoteName = remoteName;
            detection.remoteAdapterVersion = remoteAdapter;
            fingerprintDetail = detail.str() + " remoteSupportedBuild=" + remoteName + " adapter=" + remoteAdapter;
            return detection;
        }
    }
#ifndef RAZORCLIENT_RELEASE
    std::string genericAdapter;
    if (genericLunar189Compatible(detection, genericAdapter)) {
        detection.remoteName = "Lunar 1.8.9 generic-compatible";
        detection.remoteAdapterVersion = genericAdapter;
        remoteCompatStatus += "; generic structural match";
        fingerprintDetail = detail.str() + " genericSupportedBuild=" + detection.remoteName + " adapter=" + genericAdapter;
        return detection;
    }
#endif
    fingerprintDetail = detail.str();
    return detection;
}

std::vector<ProcessCandidate> lunarProcesses() {
    std::vector<ProcessCandidate> result;
    for (const ProcessInfo& info : ProcessUtils::GetProcesses()) {
        if (_wcsicmp(info.name.c_str(), L"javaw.exe") != 0 &&
                _wcsicmp(info.name.c_str(), L"java.exe") != 0) continue;
        HandleGuard process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION |
            PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, info.pid));
        if (process) {
            const std::vector<ModuleInfo> modules = ProcessUtils::GetModules(info.pid);
            const bool hasJvm = std::any_of(modules.begin(), modules.end(), [](const ModuleInfo& module) {
                return _wcsicmp(module.name.c_str(), L"jvm.dll") == 0;
            });
            const std::wstring value = info.exePath;
            std::wstring jvmPath = hasJvm ? jvmModulePath(process.get()) : std::wstring{};
            if (value.find(L"\\.lunarclient\\jre\\") != std::wstring::npos && hasJvm && !jvmPath.empty()) {
                FILETIME created{}, exited{}, kernel{}, user{};
                BOOL wow64 = TRUE;
                const bool hasCreationTime = GetProcessTimes(process.get(), &created, &exited, &kernel, &user) != FALSE;
                const bool architectureKnown = IsWow64Process(process.get(), &wow64) != FALSE;
                result.push_back(ProcessCandidate{
                    info.pid,
                    value,
                    jvmPath,
                    hasCreationTime ? fileTimeValue(created) : 0,
                    architectureKnown && wow64 == FALSE
                });
            } else if (!jvmPath.empty() && (value.find(L"\\.minecraft\\") != std::wstring::npos ||
                       value.find(L"Microsoft.4297127D64EC6") != std::wstring::npos ||
                       value.find(L"\\java-runtime-") != std::wstring::npos)) {
                writeLauncherLogLine(L"Ignored non-Lunar Minecraft JVM pid=" + std::to_wstring(info.pid) + L" exe=" + value + L" jvm=" + jvmPath);
            }
        }
    }
    return result;
}

std::wstring jvmModulePath(HANDLE process) {
    HMODULE modules[1024]{};
    DWORD needed = 0;
    if (!EnumProcessModulesEx(process, modules, sizeof(modules), &needed, LIST_MODULES_64BIT)) {
        return {};
    }
    DWORD count = needed / sizeof(HMODULE);
    for (DWORD i = 0; i < count; ++i) {
        wchar_t name[MAX_PATH]{};
        if (GetModuleBaseNameW(process, modules[i], name, MAX_PATH) && _wcsicmp(name, L"jvm.dll") == 0) {
            wchar_t path[MAX_PATH]{};
            if (GetModuleFileNameExW(process, modules[i], path, MAX_PATH)) {
                return path;
            }
            return name;
        }
    }
    return {};
}

bool extract(WORD id, const wchar_t*, const std::filesystem::path& target) {
    const std::string payloadKey = OBFUSCATE(RAZORCLIENT_PAYLOAD_KEY_LITERAL).decrypt();
    std::vector<std::uint8_t> plaintext;
    if (!DataCrypto::DecryptResource(id, payloadKey, plaintext) || plaintext.empty()) {
        writeLauncherLogLine(L"Encrypted resource decryption failed for id=" + std::to_wstring(id));
        return false;
    }
    const std::filesystem::path temporary = target.wstring() + L".tmp";
    std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(plaintext.data()),
        static_cast<std::streamsize>(plaintext.size()));
    out.flush();
    const bool written = out.good();
    out.close();
    if (written && MoveFileExW(temporary.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) &&
            std::filesystem::exists(target) && std::filesystem::file_size(target) > 0) {
        if (!verifyExpectedPayloadHash(id, target)) return false;
        if (id == IDR_BOOTSTRAP && !verifyAuthenticode(target)) return false;
        return true;
    }
    std::error_code cleanupError;
    std::filesystem::remove(temporary, cleanupError);
    return false;
}

struct ReusableBootstrap {
    HMODULE remoteBase{};
    std::filesystem::path dllPath;
    std::filesystem::path jarPath;
};

std::filesystem::path bootstrapAgentPath(const std::filesystem::path& dllPath) {
    constexpr wchar_t prefix[] = L"razorclient-bootstrap-live-";
    const std::wstring name = dllPath.filename().wstring();
    if (name.size() <= std::size(prefix) - 1 + 4 ||
            _wcsnicmp(name.c_str(), prefix, std::size(prefix) - 1) != 0 ||
            _wcsicmp(dllPath.extension().c_str(), L".dll") != 0) {
        return {};
    }
    const std::wstring suffix = name.substr(std::size(prefix) - 1,
        name.size() - (std::size(prefix) - 1) - 4);
    return dllPath.parent_path() / (L"razorclient-agent-live-" + suffix + L".jar");
}

bool findReusableBootstrap(HANDLE process, const std::filesystem::path& currentDll,
        const std::filesystem::path& currentJar, ReusableBootstrap& result) {
    const std::string currentDllHash = sha256(currentDll);
    const std::string currentJarHash = sha256(currentJar);
    if (currentDllHash.empty() || currentJarHash.empty()) return false;

    DWORD needed = 0;
    if (!EnumProcessModulesEx(process, nullptr, 0, &needed, LIST_MODULES_64BIT) || needed == 0) return false;
    std::vector<HMODULE> modules((needed + sizeof(HMODULE) - 1) / sizeof(HMODULE));
    if (!EnumProcessModulesEx(process, modules.data(), static_cast<DWORD>(modules.size() * sizeof(HMODULE)),
            &needed, LIST_MODULES_64BIT)) return false;

    const size_t count = std::min(modules.size(), static_cast<size_t>(needed / sizeof(HMODULE)));
    for (size_t index = 0; index < count; ++index) {
        wchar_t moduleName[MAX_PATH]{};
        if (!GetModuleBaseNameW(process, modules[index], moduleName, MAX_PATH) ||
                _wcsnicmp(moduleName, L"razorclient-bootstrap-live-", 27) != 0) {
            continue;
        }

        std::vector<wchar_t> modulePath(32768);
        if (!GetModuleFileNameExW(process, modules[index], modulePath.data(),
                static_cast<DWORD>(modulePath.size()))) {
            continue;
        }
        std::filesystem::path existingDll(modulePath.data());
        std::filesystem::path existingJar = bootstrapAgentPath(existingDll);
        if (existingJar.empty() || sha256(existingDll) != currentDllHash || sha256(existingJar) != currentJarHash) {
            continue;
        }
        result = {modules[index], std::move(existingDll), std::move(existingJar)};
        return true;
    }
    return false;
}

void* remoteBootstrapExport(const ReusableBootstrap& bootstrap, const char* exportName) {
    HMODULE local = LoadLibraryExW(bootstrap.dllPath.c_str(), nullptr, DONT_RESOLVE_DLL_REFERENCES);
    if (!local) return nullptr;
    FARPROC exported = GetProcAddress(local, exportName);
    if (!exported) {
        FreeLibrary(local);
        return nullptr;
    }

    const auto base = reinterpret_cast<const unsigned char*>(local);
    const auto dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) {
        FreeLibrary(local);
        return nullptr;
    }
    const auto nt = reinterpret_cast<const IMAGE_NT_HEADERS*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) {
        FreeLibrary(local);
        return nullptr;
    }
    const auto rva = reinterpret_cast<const unsigned char*>(exported) - base;
    const bool valid = rva >= 0 && static_cast<size_t>(rva) < nt->OptionalHeader.SizeOfImage;
    FreeLibrary(local);
    return valid ? reinterpret_cast<unsigned char*>(bootstrap.remoteBase) + rva : nullptr;
}

enum class BootstrapReuseResult { NotFound, Restarted, Failed };

BootstrapReuseResult restartReusableBootstrap(HANDLE process, const std::filesystem::path& dll,
        const std::filesystem::path& jar, const std::filesystem::path& statusPath) {
    ReusableBootstrap bootstrap;
    if (!findReusableBootstrap(process, dll, jar, bootstrap)) return BootstrapReuseResult::NotFound;

    void* restartAddress = remoteBootstrapExport(bootstrap, "RazorClientRestart");
    if (!restartAddress) {
        lastInjectError = ERROR_PROC_NOT_FOUND;
        return BootstrapReuseResult::Failed;
    }

    const std::wstring path = statusPath.wstring();
    const size_t bytes = (path.size() + 1) * sizeof(wchar_t);
    RemoteMemoryGuard remoteStatus(process, bytes);
    SIZE_T written = 0;
    if (!remoteStatus || !WriteProcessMemory(process, remoteStatus.get(), path.c_str(), bytes, &written) ||
            written != bytes) {
        lastInjectError = GetLastError();
        return BootstrapReuseResult::Failed;
    }

    ThreadGuard thread(CreateRemoteThread(process, nullptr, 0,
        reinterpret_cast<LPTHREAD_START_ROUTINE>(restartAddress), remoteStatus.get(), 0, nullptr));
    if (!thread) {
        lastInjectError = GetLastError();
        return BootstrapReuseResult::Failed;
    }
    const DWORD wait = WaitForSingleObject(thread.get(), 30000);
    DWORD code = ERROR_GEN_FAILURE;
    const bool restarted = wait == WAIT_OBJECT_0 && GetExitCodeThread(thread.get(), &code) && code == 0;
    if (!restarted) {
        if (wait == WAIT_TIMEOUT) {
            lastInjectError = WAIT_TIMEOUT;
            static_cast<void>(remoteStatus.release());
        } else {
            lastInjectError = code ? code : GetLastError();
        }
        return BootstrapReuseResult::Failed;
    }
    writeLauncherLogLine(L"Reused loaded bootstrap " + bootstrap.dllPath.wstring());
    return BootstrapReuseResult::Restarted;
}

bool inject(DWORD pid, const std::filesystem::path& dll, const std::filesystem::path& jar,
        const std::filesystem::path& statusPath, bool& reusedBootstrap) {
    lastInjectError = 0;
    reusedBootstrap = false;
    const std::wstring eventName = L"Local\\RazorClient_Bootstrap_Loaded_" + std::to_wstring(pid);
    HandleGuard bootstrapEvent(CreateEventW(nullptr, TRUE, FALSE, eventName.c_str()));
    if (!bootstrapEvent) {
        lastInjectError = GetLastError();
        return false;
    }
    ResetEvent(bootstrapEvent.get());
    HandleGuard process(OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION |
        PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid));
    if (!process) { lastInjectError = GetLastError(); return false; }

    const BootstrapReuseResult reuse = restartReusableBootstrap(process.get(), dll, jar, statusPath);
    if (reuse == BootstrapReuseResult::Restarted) {
        reusedBootstrap = true;
        const DWORD eventWait = WaitForSingleObject(bootstrapEvent.get(), configuredTimeoutMs);
        if (eventWait != WAIT_OBJECT_0) {
            lastInjectError = eventWait == WAIT_TIMEOUT ? WAIT_TIMEOUT : GetLastError();
            return false;
        }
        writeLauncherLogLine(L"Bootstrap event confirmed " + eventName);
        return true;
    }
    if (reuse == BootstrapReuseResult::Failed) return false;

    ModuleLoader loader(pid);
    if (!loader.IsValid() || !loader.Load(dll.wstring())) {
        lastInjectError = loader.LastError();
        return false;
    }
    const DWORD eventWait = WaitForSingleObject(bootstrapEvent.get(), configuredTimeoutMs);
    if (eventWait != WAIT_OBJECT_0) {
        lastInjectError = eventWait == WAIT_TIMEOUT ? WAIT_TIMEOUT : GetLastError();
        return false;
    }
    writeLauncherLogLine(L"Bootstrap event confirmed " + eventName);
    return true;
}

int selfCheck() {
    writeLauncherLogLine(L"Running one-file self-check");
    if (!verifyAuthenticode(currentExePath())) {
        writeLauncherLogLine(L"Self-check failed: launcher signature verification failed");
        return 1;
    }
    if (Config::Instance().Path().empty()) {
        writeLauncherLogLine(L"Self-check failed: configuration path unavailable");
        return 2;
    }
    if (!isHttpsUrl(effectiveManifestUrl)) {
        writeLauncherLogLine(L"Self-check failed: manifest URL is not HTTPS");
        return 3;
    }
    if (!cryptoSelfCheck()) {
        writeLauncherLogLine(L"Self-check failed: compatibility manifest verifier vector failed");
        return 4;
    }
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    if (!FindResourceW(module, MAKEINTRESOURCEW(IDR_BOOTSTRAP), RT_RCDATA)) {
        writeLauncherLogLine(L"Self-check failed: embedded bootstrap resource missing");
        return 5;
    }
    if (!FindResourceW(module, MAKEINTRESOURCEW(IDR_AGENT), RT_RCDATA)) {
        writeLauncherLogLine(L"Self-check failed: embedded agent resource missing");
        return 6;
    }
    if (embeddedResourceHasPlaintextMagic(IDR_BOOTSTRAP) || embeddedResourceHasPlaintextMagic(IDR_AGENT)) {
        writeLauncherLogLine(L"Self-check failed: payload resource was embedded without encryption");
        return 7;
    }

    wchar_t local[MAX_PATH]{};
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH)) {
        writeLauncherLogLine(L"Self-check failed: LOCALAPPDATA unavailable");
        return 8;
    }

    std::filesystem::path directory = std::filesystem::path(local) / L"RazorClient" / L"self-check";
    std::error_code ec;
    std::filesystem::remove_all(directory, ec);
    std::filesystem::create_directories(directory, ec);
    if (ec) {
        writeLauncherLogLine(L"Self-check failed: cannot create " + directory.wstring());
        return 9;
    }

    auto dll = directory / L"razorclient-bootstrap-self-check.dll";
    auto jar = directory / L"razorclient-agent-self-check.jar";
    bool ok = extract(IDR_BOOTSTRAP, L"__missing_adjacent_bootstrap__.dll", dll)
        && extract(IDR_AGENT, L"__missing_adjacent_agent__.jar", jar)
        && std::filesystem::exists(dll)
        && std::filesystem::exists(jar)
        && std::filesystem::file_size(dll, ec) > 0
        && std::filesystem::file_size(jar, ec) > 0
        && !sha256(dll).empty()
        && !sha256(jar).empty();
    if (ok) {
        HMODULE bootstrap = LoadLibraryExW(dll.c_str(), nullptr, DONT_RESOLVE_DLL_REFERENCES);
        ok = bootstrap && GetProcAddress(bootstrap, "RazorClientRestart") != nullptr;
        if (bootstrap) FreeLibrary(bootstrap);
    }
    writeLauncherLogLine(payloadSummary(L"Self-check bootstrap", dll));
    writeLauncherLogLine(payloadSummary(L"Self-check agent", jar));
    std::filesystem::remove_all(directory, ec);
    writeLauncherLogLine(ok ? L"Self-check passed" : L"Self-check failed: embedded payload or restart export invalid");
    return ok ? 0 : 10;
}

std::filesystem::path latestStatusFile() {
    std::filesystem::path best;
    std::error_code ec;
    auto directory = localRazorDirectory();
    if (!std::filesystem::is_directory(directory, ec)) {
        return best;
    }
    std::filesystem::file_time_type bestTime{};
    for (const auto& entry : std::filesystem::directory_iterator(directory, ec)) {
        if (ec || !entry.is_regular_file()) {
            continue;
        }
        std::wstring name = entry.path().filename().wstring();
        if (name.rfind(L"razorclient-status-live-", 0) != 0 || entry.path().extension() != L".jsonl") {
            continue;
        }
        auto time = entry.last_write_time(ec);
        if (ec) {
            continue;
        }
        if (best.empty() || time > bestTime) {
            best = entry.path();
            bestTime = time;
        }
    }
    return best;
}

std::string lastPhaseFromStatus(const std::filesystem::path& path) {
    if (path.empty()) {
        return {};
    }
    std::ifstream input(path);
    std::string text((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    std::string phase;
    size_t search = 0;
    while (true) {
        size_t pos = text.find("\"phase\":\"", search);
        if (pos == std::string::npos) {
            break;
        }
        pos += 9;
        size_t end = text.find('"', pos);
        if (end == std::string::npos) {
            break;
        }
        phase = text.substr(pos, end - pos);
        search = end + 1;
    }
    return phase;
}

void copyToClipboard(HWND owner, const std::wstring& text) {
    if (!OpenClipboard(owner)) {
        return;
    }
    EmptyClipboard();
    size_t bytes = (text.size() + 1) * sizeof(wchar_t);
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (memory) {
        void* target = GlobalLock(memory);
        if (target) {
            memcpy(target, text.c_str(), bytes);
            GlobalUnlock(memory);
            SetClipboardData(CF_UNICODETEXT, memory);
            memory = nullptr;
        }
    }
    if (memory) {
        GlobalFree(memory);
    }
    CloseClipboard();
}

int writeDiagnostics() {
    auto directory = localRazorDirectory();
    std::filesystem::create_directories(directory);
    auto checkDirectory = directory / L"diagnostics-check";
    std::error_code ec;
    std::filesystem::remove_all(checkDirectory, ec);
    std::filesystem::create_directories(checkDirectory, ec);
    auto dll = checkDirectory / L"razorclient-bootstrap-diagnostics.dll";
    auto jar = checkDirectory / L"razorclient-agent-diagnostics.jar";
    bool bootstrapOk = extract(IDR_BOOTSTRAP, L"__missing_adjacent_bootstrap__.dll", dll);
    bool agentOk = extract(IDR_AGENT, L"__missing_adjacent_agent__.jar", jar);
    auto processes = lunarProcesses();
    LunarBuildDetection lunarBuild = processes.size() == 1 ? detectLunarBuild(&processes.front()) : detectLunarBuild();
    bool fingerprintOk = lunarBuild.isSupported();
    auto statusPath = latestStatusFile();
    std::string lastPhase = lastPhaseFromStatus(statusPath);
    const std::vector<ProcessInfo> processInventory = ProcessUtils::GetProcesses();
    MEMORYSTATUSEX memory{};
    memory.dwLength = sizeof(memory);
    GlobalMemoryStatusEx(&memory);
    SYSTEM_INFO systemInfo{};
    GetNativeSystemInfo(&systemInfo);

    std::wstringstream report;
    report << L"RazorClient diagnostics\n";
    report << L"Time: " << timestamp() << L"\n";
    report << L"EXE: " << currentExePath().wstring() << L"\n";
    report << L"EXE SHA-256: " << widen(sha256(currentExePath())) << L"\n";
    report << L"Config: " << Config::Instance().Path().wstring() << L" ("
           << (configLoaded ? L"loaded" : L"defaults") << L")\n";
    report << L"Effective manifest URL: " << effectiveManifestUrl << L"\n";
    report << L"Timeout: " << configuredTimeoutMs << L" ms\n";
    report << L"Architecture: " << (systemInfo.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64 ? L"x64" : L"other") << L"\n";
    report << L"Physical memory: " << (memory.ullTotalPhys / (1024ULL * 1024ULL)) << L" MiB\n";
    report << L"Password accepted: " << (passwordAccepted ? L"yes" : L"no") << L"\n";
    report << L"Payload resources: " << (embeddedPayloadAvailable() ? L"embedded OK" : L"embedded payload missing") << L"\n";
    report << L"Bootstrap extracted: " << (bootstrapOk ? L"yes" : L"no") << L"\n";
    report << L"Bootstrap SHA-256: " << widen(sha256(dll)) << L"\n";
    report << L"Agent extracted: " << (agentOk ? L"yes" : L"no") << L"\n";
    report << L"Agent SHA-256: " << widen(sha256(jar)) << L"\n";
    report << L"Expected bootstrap SHA-256: " << widen(RAZORCLIENT_EXPECTED_BOOTSTRAP_SHA256) << L"\n";
    report << L"Expected agent SHA-256: " << widen(RAZORCLIENT_EXPECTED_AGENT_SHA256) << L"\n";
    report << L"Process inventory count: " << processInventory.size() << L"\n";
    for (const ProcessInfo& info : processInventory) {
        if (_wcsicmp(info.name.c_str(), L"java.exe") == 0 || _wcsicmp(info.name.c_str(), L"javaw.exe") == 0) {
            report << L"  Java PID " << info.pid << L" parent=" << info.parentPid
                   << L" exe=" << info.exePath << L" modules=" << ProcessUtils::GetModules(info.pid).size() << L"\n";
        }
    }
    report << L"Lunar JVM count: " << processes.size() << L"\n";
    for (const auto& process : processes) {
        report << L"  PID " << process.pid << L" created=" << process.creationTime
               << L" x64=" << (process.x64 ? L"yes" : L"no")
               << L" exe=" << process.exePath << L" jvm=" << process.jvmPath << L"\n";
    }
    report << L"Fingerprint: " << (fingerprintOk ? L"OK" : L"unsupported or missing") << L"\n";
    report << L"Fingerprint detail: " << widen(fingerprintDetail) << L"\n";
    report << L"Remote manifest URL: " << effectiveManifestUrl << L"\n";
    report << L"Remote manifest status: " << widen(remoteCompatStatus) << L"\n";
    report << L"Remote manifest cache: " << compatCachePath().wstring() << L"\n";
    report << L"Detected bake path: " << (lunarBuild.bakePath.empty() ? L"(none)" : lunarBuild.bakePath.wstring()) << L"\n";
    report << L"Detected bake SHA-256: " << (lunarBuild.bakeHash.empty() ? L"(none)" : widen(lunarBuild.bakeHash)) << L"\n";
    report << L"Detected mappings path: " << lunarBuild.mappingsPath.wstring() << L"\n";
    report << L"Detected mappings SHA-256: " << (lunarBuild.mappingsHash.empty() ? L"(none)" : widen(lunarBuild.mappingsHash)) << L"\n";
    report << L"Supported build: " << (lunarBuild.isSupported() ? widen(lunarBuild.buildName()) : L"(none)") << L"\n";
    report << L"Adapter version: " << (lunarBuild.isSupported() ? widen(lunarBuild.adapterVersion()) : L"(none)") << L"\n";
    if (!lunarBuild.isSupported() && !lunarBuild.bakeHash.empty() && !lunarBuild.mappingsHash.empty()) {
        report << L"Addable fingerprint entry:\n";
        report << L"  bakeHash=" << widen(lunarBuild.bakeHash) << L"\n";
        report << L"  mappingsHash=" << widen(lunarBuild.mappingsHash) << L"\n";
    }
    report << L"Last status file: " << (statusPath.empty() ? L"(none)" : statusPath.wstring()) << L"\n";
    report << L"Last phase: " << (lastPhase.empty() ? L"(none)" : widen(lastPhase)) << L"\n";
    report << L"Bootstrap event: " << (processes.size() == 1
        ? L"Local\\RazorClient_Bootstrap_Loaded_" + std::to_wstring(processes.front().pid)
        : L"(none)") << L"\n";
    report << L"Launcher status: " << currentStatus() << L"\n";

    auto output = directory / L"diagnostics.txt";
    {
        std::wofstream out(output, std::ios::trunc);
        out << report.str();
    }
    std::filesystem::remove_all(checkDirectory, ec);
    setDetails(report.str());
    copyToClipboard(windowHandle, report.str());
    writeLauncherLogLine(L"Wrote diagnostics to " + output.wstring());
    return embeddedPayloadAvailable() && bootstrapOk && agentOk ? 0 : 7;
}

enum class AckStatus { Success, AlreadyInjected, Failed, Timeout };

AckStatus waitForAck(const std::filesystem::path& statusPath, std::wstring& detail) {
    const ULONGLONG start = GetTickCount64();
    std::string lastPhase;
    while (GetTickCount64() - start < configuredTimeoutMs) {
        std::ifstream input(statusPath);
        std::string text((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
        if (!text.empty()) {
            if (text.find("\"phase\":\"ALREADY_INJECTED\"") != std::string::npos) {
                detail = L"Already injected";
                return AckStatus::AlreadyInjected;
            }
            if (text.find("\"phase\":\"JAVA_STARTED\"") != std::string::npos ||
                text.find("\"phase\":\"FIRST_CLIENT_PULSE\"") != std::string::npos) {
                detail = L"Java payload confirmed startup";
                return AckStatus::Success;
            }
            size_t pos = text.rfind("\"phase\":\"");
            if (pos != std::string::npos) {
                pos += 9;
                size_t end = text.find('"', pos);
                if (end != std::string::npos) {
                    lastPhase = text.substr(pos, end - pos);
                }
            }
            if (text.find("\"phase\":\"FAILED\"") != std::string::npos) {
                detail = L"Bootstrap failed. Last phase=" + widen(lastPhase);
                return AckStatus::Failed;
            }
        }
        Sleep(250);
    }
    detail = lastPhase.empty()
        ? L"Bootstrap loaded, but Java payload did not confirm startup. See launcher/bootstrap logs."
        : L"Bootstrap loaded, but Java payload did not confirm startup. Last phase=" + widen(lastPhase);
    return AckStatus::Timeout;
}

void performInjection() {
    injectionExitCode = 1;
    if (!passwordAccepted) {
        setStatus(L"Enter the configured password to enable injection.");
        injectionExitCode = 10;
        writeLauncherLog();
        redraw();
        return;
    }
    auto processes = lunarProcesses();
    if (processes.size() != 1) { setStatus(processes.empty() ? L"Launch Lunar 1.8.9 first, then press Inject." : L"Multiple Lunar JVMs found; close extra instances."); writeLauncherLog(); redraw(); return; }
    ProcessCandidate target = processes.front();
    DWORD pid = target.pid;
    targetWatcher.Stop();
    const HWND watcherWindow = windowHandle;
    static_cast<void>(targetWatcher.Start(pid, [watcherWindow] {
        if (watcherWindow) PostMessageW(watcherWindow, WM_TARGET_EXITED, 0, 0);
    }));
    const std::wstring injectionMutexName = L"Local\\RazorClient.Inject." + std::to_wstring(pid);
    HandleGuard injectionMutex(CreateMutexW(nullptr, FALSE, injectionMutexName.c_str()));
    const DWORD mutexWait = injectionMutex ? WaitForSingleObject(injectionMutex.get(), 0) : WAIT_FAILED;
    if (mutexWait != WAIT_OBJECT_0 && mutexWait != WAIT_ABANDONED) {
        setStatus(L"Another RazorClient injection is already in progress for this Lunar instance.");
        writeLauncherLogLine(L"Injection lock unavailable for pid=" + std::to_wstring(pid));
        writeLauncherLog();
        redraw();
        return;
    }
    struct InjectionMutexGuard {
        HANDLE value;
        ~InjectionMutexGuard() { if (value) ReleaseMutex(value); }
    } injectionMutexGuard{injectionMutex.get()};

    writeLauncherLogLine(L"Selected Lunar JVM pid=" + std::to_wstring(pid) + L" exe=" + target.exePath + L" jvm=" + target.jvmPath);
    setDetails(L"Selected Lunar JVM\r\nPID: " + std::to_wstring(pid) +
        L"\r\nExecutable: " + target.exePath + L"\r\nJVM: " + target.jvmPath);
    LunarBuildDetection lunarBuild = detectLunarBuild(&target);
    if (!lunarBuild.isSupported()) {
        writeLauncherLogLine(L"Unsupported Lunar build fingerprint " + widen(fingerprintDetail));
        setStatus(L"Unsupported Lunar build. This build needs a compatible RazorClient adapter.");
        writeLauncherLog();
        redraw();
        return;
    }
    writeLauncherLogLine(L"Fingerprint OK build=" + widen(lunarBuild.buildName()) + L" adapter=" + widen(lunarBuild.adapterVersion()) + L" " + widen(fingerprintDetail) + L" remote=" + widen(remoteCompatStatus));
    setDetails(currentDetails() + L"\r\nBuild: " + widen(lunarBuild.buildName()) +
        L"\r\nAdapter: " + widen(lunarBuild.adapterVersion()));
    wchar_t local[MAX_PATH]{}; GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    std::filesystem::path directory = std::filesystem::path(local) / L"RazorClient";
    std::filesystem::create_directories(directory);
    std::wstring suffix = std::to_wstring(pid) + L"-" + std::to_wstring(GetTickCount64());
    auto dll = directory / (L"razorclient-bootstrap-live-" + suffix + L".dll");
    auto jar = directory / (L"razorclient-agent-live-" + suffix + L".jar");
    auto statusPath = directory / (L"razorclient-status-live-" + suffix + L".jsonl");
    auto metadataPath = directory / (L"razorclient-build-live-" + suffix + L".properties");
    setStatus(L"Injecting into Lunar JVM PID " + std::to_wstring(pid) + L"...");
    writeLauncherLog();
    redraw();
    if (!extract(IDR_BOOTSTRAP, L"razorclient-bootstrap.dll", dll)) {
        setStatus(L"Failed to extract embedded payload: bootstrap DLL.");
    } else if (!extract(IDR_AGENT, L"razorclient-agent.jar", jar)) {
        setStatus(L"Failed to extract embedded payload: agent JAR.");
    } else {
        writeLauncherLogLine(payloadSummary(L"Extracted bootstrap", dll));
        writeLauncherLogLine(payloadSummary(L"Extracted agent", jar));
        setDetails(currentDetails() + L"\r\nBootstrap: " + dll.wstring() +
            L"\r\nAgent: " + jar.wstring() + L"\r\nStatus: " + statusPath.wstring());
        writeLauncherLogLine(L"Status file " + statusPath.wstring());
        {
            std::ofstream metadata(metadataPath, std::ios::trunc);
            metadata << "buildName=" << lunarBuild.buildName() << "\n";
            metadata << "adapterVersion=" << lunarBuild.adapterVersion() << "\n";
            metadata << "bakeHash=" << lunarBuild.bakeHash << "\n";
            metadata << "mappingsHash=" << lunarBuild.mappingsHash << "\n";
        }
        writeLauncherLogLine(L"Build metadata " + metadataPath.wstring());
        bool reusedBootstrap = false;
        if (!inject(pid, dll, jar, statusPath, reusedBootstrap)) {
            setStatus(L"Injection failed. Error " + std::to_wstring(lastInjectError) + L". See %LOCALAPPDATA%\\RazorClient\\bootstrap.log");
        } else {
            setStatus(reusedBootstrap
                ? L"Bootstrap restarted; waiting for Java payload confirmation..."
                : L"Bootstrap loaded; waiting for Java payload confirmation...");
            writeLauncherLog();
            redraw();
            std::wstring ackDetail;
            AckStatus ack = waitForAck(statusPath, ackDetail);
            setDetails(currentDetails() + L"\r\nAcknowledgement: " + ackDetail);
            if (ack == AckStatus::Success) {
                setStatus(L"Injected. Press Right Shift in game.");
                injectionExitCode = 0;
            } else if (ack == AckStatus::AlreadyInjected) {
                setStatus(L"Already injected.");
                injectionExitCode = 0;
            } else {
                setStatus(ackDetail);
            }
        }
    }
    writeLauncherLog();
    redraw();
}

void paintLauncher(HWND hwnd, HDC dc) {
    RECT client{};
    GetClientRect(hwnd, &client);
    fillRoundRect(dc, client, 0, rgb(5, 8, 5));

    RECT glow{18, 18, client.right - 18, client.bottom - 18};
    fillRoundRect(dc, glow, 26, rgb(8, 17, 10));
    frameRoundRect(dc, glow, 26, rgb(26, 83, 45));

    RECT card{28, 28, client.right - 28, client.bottom - 28};
    fillRoundRect(dc, card, 22, rgb(9, 14, 10));
    frameRoundRect(dc, card, 22, rgb(45, 150, 86));

    RECT logo{46, 43, 82, 79};
    fillRoundRect(dc, logo, 14, rgb(15, 32, 20));
    frameRoundRect(dc, logo, 14, rgb(45, 150, 86));
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, rgb(69, 200, 131));
    HFONT oldFont = static_cast<HFONT>(SelectObject(dc, titleFont));
    DrawTextW(dc, L"R", -1, &logo, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

    RECT title{94, 38, client.right - 46, 64};
    SetTextColor(dc, rgb(236, 239, 234));
    DrawTextW(dc, L"RAZOR", -1, &title, DT_LEFT | DT_VCENTER | DT_SINGLELINE);
    RECT titleClient{154, 38, client.right - 46, 64};
    SetTextColor(dc, rgb(45, 150, 86));
    DrawTextW(dc, L"CLIENT", -1, &titleClient, DT_LEFT | DT_VCENTER | DT_SINGLELINE);

    SelectObject(dc, bodyFont);
    SetTextColor(dc, rgb(116, 132, 121));
    RECT subtitle{96, 64, client.right - 46, 86};
    DrawTextW(dc, L"EXHIBIT A - LUNAR 1.8.9 LIVE INJECTOR", -1, &subtitle, DT_LEFT | DT_VCENTER | DT_SINGLELINE);

    RECT pill{46, 98, client.right - 46, 132};
    fillRoundRect(dc, pill, 16, rgb(10, 18, 13));
    frameRoundRect(dc, pill, 16, rgb(24, 64, 36));
    SetTextColor(dc, rgb(226, 235, 228));
    RECT statusText{60, 98, client.right - 60, 132};
    const std::wstring displayedStatus = currentStatus();
    DrawTextW(dc, displayedStatus.c_str(), -1, &statusText, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, embeddedPayloadAvailable() ? rgb(69, 200, 131) : rgb(255, 112, 112));
    RECT payloadText{60, 136, client.right - 60, 158};
    DrawTextW(dc, payloadStatus.c_str(), -1, &payloadText, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

    SetTextColor(dc, passwordAccepted ? rgb(69, 200, 131) : rgb(116, 132, 121));
    RECT passwordLabel{60, 166, 126, 194};
    DrawTextW(dc, L"Password", -1, &passwordLabel, DT_RIGHT | DT_VCENTER | DT_SINGLELINE);

    SelectObject(dc, oldFont);
}

void drawButton(const DRAWITEMSTRUCT* item, const wchar_t* text, bool primary) {
    HDC dc = item->hDC;
    RECT rect = item->rcItem;
    bool pressed = (item->itemState & ODS_SELECTED) != 0;
    bool focused = (item->itemState & ODS_FOCUS) != 0;
    bool disabled = (item->itemState & ODS_DISABLED) != 0;
    COLORREF fill = disabled ? rgb(26, 32, 27) : primary
        ? (pressed ? rgb(30, 127, 72) : rgb(45, 150, 86))
        : (pressed ? rgb(12, 28, 17) : rgb(8, 16, 10));
    COLORREF border = disabled ? rgb(50, 60, 52) : (focused ? rgb(69, 200, 131) : (primary ? rgb(69, 200, 131) : rgb(31, 84, 47)));
    fillRoundRect(dc, rect, 18, fill);
    frameRoundRect(dc, rect, 18, border);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, disabled ? rgb(105, 116, 108) : rgb(236, 239, 234));
    HFONT oldFont = static_cast<HFONT>(SelectObject(dc, buttonFont));
    DrawTextW(dc, text, -1, &rect, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(dc, oldFont);
}

void refreshInjectButton() {
    const bool busy = injectionInProgress.load(std::memory_order_acquire) || diagnosticsInProgress;
    if (injectButton) {
        EnableWindow(injectButton, !busy && passwordAccepted && embeddedPayloadAvailable());
        InvalidateRect(injectButton, nullptr, TRUE);
    }
    if (diagnosticsButton) EnableWindow(diagnosticsButton, !busy);
    if (unlockButton) EnableWindow(unlockButton, !busy);
    if (passwordEdit) EnableWindow(passwordEdit, !busy);
}

void unlockPasswordFromUi() {
    wchar_t value[128]{};
    GetWindowTextW(passwordEdit, value, 128);
    if (configuredPassword == value) {
        passwordAccepted = true;
        setStatus(L"Password accepted. Launch Lunar 1.8.9 first, then press Inject.");
    } else {
        passwordAccepted = false;
        setStatus(L"Invalid password.");
    }
    refreshInjectButton();
    writeLauncherLog();
    redraw();
}

DWORD WINAPI diagnosticsWorker(void*) {
    const int result = writeDiagnostics();
    if (windowHandle) PostMessageW(windowHandle, WM_DIAGNOSTICS_FINISHED,
        static_cast<WPARAM>(result), 0);
    return 0;
}

void beginDiagnostics() {
    if (diagnosticsInProgress || injectionInProgress.load(std::memory_order_acquire)) return;
    diagnosticsInProgress = true;
    setStatus(L"Collecting diagnostics...");
    setDetails(L"Inspecting configuration, payload resources, processes, JVM modules, fingerprints, and compatibility manifest...");
    refreshInjectButton();
    diagnosticsWorkerHandle = ThreadGuard(CreateThread(nullptr, 0, diagnosticsWorker, nullptr, 0, nullptr));
    if (!diagnosticsWorkerHandle) {
        diagnosticsInProgress = false;
        setStatus(L"Unable to start diagnostics worker. Error " + std::to_wstring(GetLastError()) + L".");
        refreshInjectButton();
    }
}

DWORD WINAPI injectionWorker(void*) {
    performInjection();
    if (windowHandle) PostMessageW(windowHandle, WM_INJECTION_FINISHED, 0, 0);
    return 0;
}

void beginInjection() {
    bool expected = false;
    if (!injectionInProgress.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) return;
    setStatus(L"Discovering Lunar 1.8.9 runtime...");
    refreshInjectButton();
    injectionWorkerHandle = ThreadGuard(CreateThread(nullptr, 0, injectionWorker, nullptr, 0, nullptr));
    if (!injectionWorkerHandle) {
        injectionInProgress.store(false, std::memory_order_release);
        setStatus(L"Unable to start injection worker. Error " + std::to_wstring(GetLastError()) + L".");
        refreshInjectButton();
        return;
    }
}

LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_CREATE) {
        titleFont = makeFont(18, FW_SEMIBOLD);
        bodyFont = makeFont(10, FW_NORMAL);
        buttonFont = makeFont(11, FW_SEMIBOLD);
        editBrush = solidBrush(rgb(8, 16, 10));
        payloadStatus = embeddedPayloadAvailable() ? L"Payload: encrypted resources embedded" : L"Invalid release build: embedded payload missing";
        if (!embeddedPayloadAvailable()) {
            setStatus(L"Invalid release build: embedded payload missing.");
        }
        passwordEdit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_PASSWORD | ES_AUTOHSCROLL,
            132, 166, 160, 28, hwnd, reinterpret_cast<HMENU>(4), nullptr, nullptr);
        unlockButton = CreateWindowW(L"BUTTON", L"Unlock", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            306, 160, 98, 40, hwnd, reinterpret_cast<HMENU>(3), nullptr, nullptr);
        injectButton = CreateWindowW(L"BUTTON", L"Inject", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            95, 224, 140, 42, hwnd, reinterpret_cast<HMENU>(1), nullptr, nullptr);
        diagnosticsButton = CreateWindowW(L"BUTTON", L"Diagnostics", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            255, 224, 150, 42, hwnd, reinterpret_cast<HMENU>(2), nullptr, nullptr);
        detailsEdit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"Ready.",
            WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_LEFT | ES_MULTILINE |
            ES_AUTOVSCROLL | ES_READONLY,
            46, 282, 408, 116, hwnd, reinterpret_cast<HMENU>(5), nullptr, nullptr);
        SendMessageW(passwordEdit, WM_SETFONT, reinterpret_cast<WPARAM>(bodyFont), TRUE);
        SendMessageW(passwordEdit, EM_SETPASSWORDCHAR, static_cast<WPARAM>(0x25CF), 0);
        SendMessageW(injectButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        SendMessageW(diagnosticsButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        SendMessageW(unlockButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        SendMessageW(detailsEdit, WM_SETFONT, reinterpret_cast<WPARAM>(bodyFont), TRUE);
        refreshInjectButton();
        BOOL dark = TRUE;
        DwmSetWindowAttribute(hwnd, 20, &dark, sizeof(dark));
        return 0;
    }
    if (message == WM_COMMAND && LOWORD(wParam) == 1) { beginInjection(); return 0; }
    if (message == WM_COMMAND && LOWORD(wParam) == 2) { beginDiagnostics(); return 0; }
    if (message == WM_COMMAND && LOWORD(wParam) == 3) { unlockPasswordFromUi(); return 0; }
    if (message == WM_DRAWITEM && wParam == 1) { drawButton(reinterpret_cast<DRAWITEMSTRUCT*>(lParam), L"Inject", true); return TRUE; }
    if (message == WM_DRAWITEM && wParam == 2) { drawButton(reinterpret_cast<DRAWITEMSTRUCT*>(lParam), L"Diagnostics", false); return TRUE; }
    if (message == WM_DRAWITEM && wParam == 3) { drawButton(reinterpret_cast<DRAWITEMSTRUCT*>(lParam), L"Unlock", false); return TRUE; }
    if (message == WM_CTLCOLOREDIT || message == WM_CTLCOLORSTATIC) {
        HDC dc = reinterpret_cast<HDC>(wParam);
        SetBkColor(dc, rgb(8, 16, 10));
        SetTextColor(dc, rgb(236, 239, 234));
        return reinterpret_cast<LRESULT>(editBrush ? editBrush : GetStockObject(BLACK_BRUSH));
    }
    if (message == WM_CTLCOLORBTN) { return reinterpret_cast<LRESULT>(GetStockObject(NULL_BRUSH)); }
    if (message == WM_APP) {
        if (detailsEdit) SetWindowTextW(detailsEdit, currentDetails().c_str());
        redraw();
        return 0;
    }
    if (message == WM_INJECTION_FINISHED) {
        injectionWorkerHandle = ThreadGuard{};
        injectionInProgress.store(false, std::memory_order_release);
        refreshInjectButton();
        writeLauncherLog();
        redraw();
        return 0;
    }
    if (message == WM_DIAGNOSTICS_FINISHED) {
        diagnosticsWorkerHandle = ThreadGuard{};
        diagnosticsInProgress = false;
        const bool ok = static_cast<int>(wParam) == 0;
        setStatus(ok ? L"Diagnostics complete. Report copied and saved." : L"Diagnostics completed with validation failures.");
        refreshInjectButton();
        MessageBoxW(hwnd, L"Diagnostics copied and saved to %LOCALAPPDATA%\\RazorClient\\diagnostics.txt",
            L"RazorClient Diagnostics", MB_OK | (ok ? MB_ICONINFORMATION : MB_ICONWARNING));
        return 0;
    }
    if (message == WM_TARGET_EXITED) {
        setStatus(L"Lunar exited. Launch Lunar 1.8.9 before injecting again.");
        setDetails(L"The selected Lunar JVM exited. Runtime monitoring stopped.");
        return 0;
    }
    if (message == WM_ERASEBKGND) { return TRUE; }
    if (message == WM_PAINT) {
        PAINTSTRUCT paint{};
        HDC dc = BeginPaint(hwnd, &paint);
        paintLauncher(hwnd, dc);
        EndPaint(hwnd, &paint);
        return 0;
    }
    if (message == WM_DESTROY) {
        windowHandle = nullptr;
        targetWatcher.Stop();
        if (injectionWorkerHandle) static_cast<void>(injectionWorkerHandle.wait(INFINITE));
        if (diagnosticsWorkerHandle) static_cast<void>(diagnosticsWorkerHandle.wait(INFINITE));
        injectionWorkerHandle = ThreadGuard{};
        diagnosticsWorkerHandle = ThreadGuard{};
        if (titleFont) DeleteObject(titleFont);
        if (bodyFont) DeleteObject(bodyFont);
        if (buttonFont) DeleteObject(buttonFont);
        if (editBrush) DeleteObject(editBrush);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd,message,wParam,lParam);
}

struct CommandLineOptions {
    bool selfCheck{};
    bool cryptoSelfCheck{};
    bool diagnose{};
    bool inject{};
    bool malformed{};
    std::wstring password;
    bool passwordSubmitted{};
};

CommandLineOptions parseCommandLine() {
    CommandLineOptions result;
    int count = 0;
    LPWSTR* arguments = CommandLineToArgvW(GetCommandLineW(), &count);
    if (!arguments) {
        result.malformed = true;
        return result;
    }
    for (int index = 1; index < count; ++index) {
        const std::wstring argument(arguments[index]);
        if (argument == L"--self-check") result.selfCheck = true;
        else if (argument == L"--crypto-self-check") result.cryptoSelfCheck = true;
        else if (argument == L"--diagnose") result.diagnose = true;
        else if (argument == L"--inject") result.inject = true;
        else if (argument.rfind(L"--password=", 0) == 0) {
            result.passwordSubmitted = true;
            result.password = argument.substr(11);
        } else {
            result.malformed = true;
        }
    }
    LocalFree(arguments);
    return result;
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    appInstance = instance;
    initializeSharedSystems();
    const CommandLineOptions options = parseCommandLine();
    if (options.malformed) {
        writeLauncherLogLine(L"Malformed or unsupported command-line argument");
        return 64;
    }
    passwordAccepted = options.passwordSubmitted && options.password == configuredPassword;
    if (options.cryptoSelfCheck) { return cryptoSelfCheck() ? 0 : 20; }
    if (options.selfCheck) { return selfCheck(); }
    if (options.diagnose) { return writeDiagnostics(); }
    if (options.inject) { performInjection(); writeLauncherLog(); return injectionExitCode; }
    WNDCLASSW type{}; type.lpfnWndProc=windowProc; type.hInstance=instance; type.lpszClassName=L"RazorClientLauncher"; type.hCursor=LoadCursor(nullptr,IDC_ARROW); type.hbrBackground=solidBrush(rgb(5,8,5)); RegisterClassW(&type);
    windowHandle=CreateWindowW(type.lpszClassName,L"\x00AE" L"\xFE0F" L"azorClient",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,CW_USEDEFAULT,CW_USEDEFAULT,500,455,nullptr,nullptr,instance,nullptr);
    ShowWindow(windowHandle,show); UpdateWindow(windowHandle); MSG message{}; while(GetMessageW(&message,nullptr,0,0)){TranslateMessage(&message);DispatchMessageW(&message);} return static_cast<int>(message.wParam);
}
