#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <bcrypt.h>
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
#include "resource.h"

namespace {
std::wstring status = L"Launch Lunar 1.8.9 first, then press Inject.";
std::wstring payloadStatus = L"Payload: checking...";
HWND windowHandle{};
HWND injectButton{};
HWND diagnosticsButton{};
HWND passwordEdit{};
HWND unlockButton{};
HINSTANCE appInstance{};
HFONT titleFont{};
HFONT bodyFont{};
HFONT buttonFont{};
HBRUSH editBrush{};
int injectionExitCode = 1;
std::string fingerprintDetail;
DWORD lastInjectError = 0;
bool passwordAccepted = false;
std::string remoteCompatStatus = "not checked";

bool processHasJvm(HANDLE process);
std::wstring jvmModulePath(HANDLE process);

const wchar_t* COMPAT_MANIFEST_URL = L"https://joisthegayest.com/razorclient/compat.json";

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

bool commandLineHasPassword() {
    const wchar_t* commandLine = GetCommandLineW();
    return wcsstr(commandLine, L"--password=NERVE") || wcsstr(commandLine, L"--password NERVE");
}

void writeLauncherLogLine(const std::wstring& line) {
    auto directory=localRazorDirectory(); std::filesystem::create_directories(directory);
    std::wofstream out(directory/L"launcher.log",std::ios::app); out<<timestamp()<<L" "<<line<<L'\n';
}

void writeLauncherLog() {
    writeLauncherLogLine(status);
}

void redraw() {
    if (windowHandle) {
        InvalidateRect(windowHandle, nullptr, TRUE);
        UpdateWindow(windowHandle);
    }
}

std::string sha256(const std::filesystem::path& path) {
    BCRYPT_ALG_HANDLE algorithm{}; BCRYPT_HASH_HANDLE hash{};
    DWORD objectSize=0,cb=0,hashSize=0;
    if (BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)!=0) return {};
    BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectSize),sizeof(objectSize),&cb,0);
    BCryptGetProperty(algorithm,BCRYPT_HASH_LENGTH,reinterpret_cast<PUCHAR>(&hashSize),sizeof(hashSize),&cb,0);
    std::vector<unsigned char> object(objectSize),digest(hashSize),buffer(1024*1024);
    if (BCryptCreateHash(algorithm,&hash,object.data(),objectSize,nullptr,0,0)!=0) { BCryptCloseAlgorithmProvider(algorithm,0); return {}; }
    std::ifstream input(path,std::ios::binary);
    while(input){input.read(reinterpret_cast<char*>(buffer.data()),buffer.size());auto count=input.gcount();if(count>0)BCryptHashData(hash,buffer.data(),static_cast<ULONG>(count),0);}
    BCryptFinishHash(hash,digest.data(),hashSize,0); BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm,0);
    static const char hex[]="0123456789ABCDEF"; std::string result; result.reserve(hashSize*2);
    for(unsigned char value:digest){result.push_back(hex[value>>4]);result.push_back(hex[value&15]);} return result;
}

bool embeddedResourceExists(WORD id) {
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(id), RT_RCDATA);
    return resource && SizeofResource(module, resource) > 0;
}

bool embeddedPayloadAvailable() {
    return embeddedResourceExists(IDR_BOOTSTRAP) && embeddedResourceExists(IDR_AGENT);
}

std::string readTextFile(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return std::string((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
}

bool writeTextFile(const std::filesystem::path& path, const std::string& text) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(text.data(), static_cast<std::streamsize>(text.size()));
    return output.good();
}

std::filesystem::path compatCachePath() {
    return localRazorDirectory() / L"compat-cache.json";
}

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
    if (!WinHttpCrackUrl(COMPAT_MANIFEST_URL, 0, 0, &parts)) {
        remoteCompatStatus = "invalid manifest URL";
        return false;
    }

    HINTERNET session = WinHttpOpen(L"RazorClient/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        remoteCompatStatus = "WinHttpOpen failed";
        return false;
    }
    WinHttpSetTimeouts(session, 3000, 3000, 5000, 5000);

    HINTERNET connect = WinHttpConnect(session, std::wstring(host, parts.dwHostNameLength).c_str(), parts.nPort, 0);
    if (!connect) {
        WinHttpCloseHandle(session);
        remoteCompatStatus = "WinHttpConnect failed";
        return false;
    }

    DWORD flags = parts.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0;
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
    }

    if (ok) {
        std::string body;
        DWORD available = 0;
        while (WinHttpQueryDataAvailable(request, &available) && available > 0) {
            std::vector<char> buffer(available);
            DWORD read = 0;
            if (!WinHttpReadData(request, buffer.data(), available, &read)) {
                ok = false;
                remoteCompatStatus = "WinHttpReadData failed";
                break;
            }
            body.append(buffer.data(), buffer.data() + read);
        }
        if (ok && !body.empty()) {
            manifest = body;
            writeTextFile(compatCachePath(), manifest);
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
        return manifest;
    }
    manifest = readTextFile(compatCachePath());
    if (!manifest.empty()) {
        remoteCompatStatus += "; using cached manifest";
    }
    return manifest;
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

bool remoteManifestApproves(const std::string& bakeHash, const std::string& mappingsHash, std::string& name, std::string& adapterVersion) {
    if (bakeHash.empty() || mappingsHash.empty()) {
        remoteCompatStatus = "missing local fingerprint";
        return false;
    }
    std::string manifest = loadCompatManifest();
    if (manifest.empty()) {
        return false;
    }

    size_t search = 0;
    while (true) {
        size_t bake = manifest.find(bakeHash, search);
        if (bake == std::string::npos) {
            break;
        }
        size_t start = manifest.rfind('{', bake);
        size_t end = manifest.find('}', bake);
        if (start != std::string::npos && end != std::string::npos && end > start) {
            std::string object = manifest.substr(start, end - start + 1);
            std::string objectMappings = extractJsonStringField(object, "mappingsHash");
            std::string objectAdapter = extractJsonStringField(object, "adapterVersion");
            if (objectMappings == mappingsHash && jsonBooleanFieldTrue(object, "approved") && isKnownAdapter(objectAdapter)) {
                name = extractJsonStringField(object, "name");
                adapterVersion = objectAdapter;
                if (name.empty()) {
                    name = "Remote Lunar 1.8.9 approved";
                }
                remoteCompatStatus += "; remote build approved";
                return true;
            }
        }
        search = bake + bakeHash.size();
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

LunarBuildDetection detectLunarBuild() {
    LunarBuildDetection detection;
    auto multiver = lunarMultiverDirectory();
    detection.mappingsPath = multiver / L"lunar-platform-mappings-v1_8.jar";
    detection.mappingsHash = sha256(detection.mappingsPath);

    std::vector<std::filesystem::path> bakeCandidates = findBakeCandidates(multiver);
    std::stringstream detail;
    detail << "mappings=" << detection.mappingsHash << " mappingsPath=" << narrow(detection.mappingsPath.wstring());

    if (!bakeCandidates.empty()) {
        const auto& bakePath = bakeCandidates.front();
        std::string bakeHash = sha256(bakePath);
        detail << " bakeCandidate=" << bakeHash << " path=" << narrow(bakePath.wstring());
        detail << " ignoredOlderBakeCandidates=" << (bakeCandidates.size() - 1);
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
        if (remoteManifestApproves(detection.bakeHash, detection.mappingsHash, remoteName, remoteAdapter)) {
            detection.remoteName = remoteName;
            detection.remoteAdapterVersion = remoteAdapter;
            fingerprintDetail = detail.str() + " remoteSupportedBuild=" + remoteName + " adapter=" + remoteAdapter;
            return detection;
        }
    }
    std::string genericAdapter;
    if (genericLunar189Compatible(detection, genericAdapter)) {
        detection.remoteName = "Lunar 1.8.9 generic-compatible";
        detection.remoteAdapterVersion = genericAdapter;
        remoteCompatStatus += "; generic structural match";
        fingerprintDetail = detail.str() + " genericSupportedBuild=" + detection.remoteName + " adapter=" + genericAdapter;
        return detection;
    }
    fingerprintDetail = detail.str();
    return detection;
}

bool validatePinnedBuild() {
    return detectLunarBuild().isSupported();
}

std::vector<ProcessCandidate> lunarProcesses() {
    std::vector<ProcessCandidate> result;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return result;
    PROCESSENTRY32W entry{sizeof(entry)};
    for (BOOL ok = Process32FirstW(snapshot, &entry); ok; ok = Process32NextW(snapshot, &entry)) {
        if (_wcsicmp(entry.szExeFile, L"javaw.exe") != 0 && _wcsicmp(entry.szExeFile, L"java.exe") != 0) continue;
        HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE, entry.th32ProcessID);
        wchar_t path[32768]{}; DWORD size = 32768;
        if (process && QueryFullProcessImageNameW(process, 0, path, &size)) {
            std::wstring value(path);
            std::wstring jvmPath = jvmModulePath(process);
            if (value.find(L"\\.lunarclient\\jre\\") != std::wstring::npos && !jvmPath.empty()) {
                result.push_back(ProcessCandidate{entry.th32ProcessID, value, jvmPath});
            } else if (!jvmPath.empty() && (value.find(L"\\.minecraft\\") != std::wstring::npos ||
                       value.find(L"Microsoft.4297127D64EC6") != std::wstring::npos ||
                       value.find(L"\\java-runtime-") != std::wstring::npos)) {
                writeLauncherLogLine(L"Ignored non-Lunar Minecraft JVM pid=" + std::to_wstring(entry.th32ProcessID) + L" exe=" + value + L" jvm=" + jvmPath);
            }
        }
        if (process) CloseHandle(process);
    }
    CloseHandle(snapshot);
    return result;
}

bool processHasJvm(HANDLE process) {
    return !jvmModulePath(process).empty();
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

std::filesystem::path exeDirectory() {
    wchar_t path[MAX_PATH]{};
    GetModuleFileNameW(appInstance ? appInstance : GetModuleHandleW(nullptr), path, MAX_PATH);
    return std::filesystem::path(path).parent_path();
}

bool copyAdjacentPayload(const wchar_t* name, const std::filesystem::path& target) {
    auto source = exeDirectory() / name;
    if (!std::filesystem::exists(source)) return false;
    std::error_code ec;
    std::filesystem::copy_file(source, target, std::filesystem::copy_options::overwrite_existing, ec);
    return !ec && std::filesystem::exists(target) && std::filesystem::file_size(target) > 0;
}

bool extract(WORD id, const wchar_t* adjacentName, const std::filesystem::path& target) {
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (!resource) return copyAdjacentPayload(adjacentName, target);
    HGLOBAL loaded = LoadResource(module, resource);
    DWORD size = SizeofResource(module, resource);
    void* bytes = LockResource(loaded);
    if (!loaded || !bytes || size == 0) return copyAdjacentPayload(adjacentName, target);
    std::ofstream out(target, std::ios::binary | std::ios::trunc);
    out.write(static_cast<const char*>(bytes), size);
    out.close();
    if (out.good() && std::filesystem::exists(target) && std::filesystem::file_size(target) > 0) return true;
    return copyAdjacentPayload(adjacentName, target);
}

bool inject(DWORD pid, const std::filesystem::path& dll) {
    lastInjectError = 0;
    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ, FALSE, pid);
    if (!process) { lastInjectError = GetLastError(); return false; }
    std::wstring path = dll.wstring();
    size_t bytes = (path.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    bool ok = remote && WriteProcessMemory(process, remote, path.c_str(), bytes, nullptr);
    if (!ok) lastInjectError = GetLastError();
    HANDLE thread = ok ? CreateRemoteThread(process, nullptr, 0, reinterpret_cast<LPTHREAD_START_ROUTINE>(GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW")), remote, 0, nullptr) : nullptr;
    DWORD wait = WAIT_FAILED;
    if (thread) {
        wait = WaitForSingleObject(thread, 15000);
        DWORD code = 0;
        GetExitCodeThread(thread, &code);
        ok = wait == WAIT_OBJECT_0 && code != 0;
        if (!ok) lastInjectError = wait == WAIT_TIMEOUT ? WAIT_TIMEOUT : GetLastError();
        CloseHandle(thread);
    } else {
        lastInjectError = GetLastError();
        ok = false;
    }
    // A timed-out LoadLibraryW thread may still be reading this path.
    if (remote && wait != WAIT_TIMEOUT) VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);
    return ok;
}

int selfCheck() {
    writeLauncherLogLine(L"Running one-file self-check");
    HMODULE module = appInstance ? appInstance : GetModuleHandleW(nullptr);
    if (!FindResourceW(module, MAKEINTRESOURCEW(IDR_BOOTSTRAP), RT_RCDATA)) {
        writeLauncherLogLine(L"Self-check failed: embedded bootstrap resource missing");
        return 2;
    }
    if (!FindResourceW(module, MAKEINTRESOURCEW(IDR_AGENT), RT_RCDATA)) {
        writeLauncherLogLine(L"Self-check failed: embedded agent resource missing");
        return 3;
    }

    wchar_t local[MAX_PATH]{};
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH)) {
        writeLauncherLogLine(L"Self-check failed: LOCALAPPDATA unavailable");
        return 4;
    }

    std::filesystem::path directory = std::filesystem::path(local) / L"RazorClient" / L"self-check";
    std::error_code ec;
    std::filesystem::remove_all(directory, ec);
    std::filesystem::create_directories(directory, ec);
    if (ec) {
        writeLauncherLogLine(L"Self-check failed: cannot create " + directory.wstring());
        return 5;
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
    writeLauncherLogLine(payloadSummary(L"Self-check bootstrap", dll));
    writeLauncherLogLine(payloadSummary(L"Self-check agent", jar));
    std::filesystem::remove_all(directory, ec);
    writeLauncherLogLine(ok ? L"Self-check passed" : L"Self-check failed: embedded payload extraction failed");
    return ok ? 0 : 6;
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

int writeDiagnostics(bool showMessage) {
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
    LunarBuildDetection lunarBuild = detectLunarBuild();
    bool fingerprintOk = lunarBuild.isSupported();
    auto statusPath = latestStatusFile();
    std::string lastPhase = lastPhaseFromStatus(statusPath);

    std::wstringstream report;
    report << L"RazorClient diagnostics\n";
    report << L"Time: " << timestamp() << L"\n";
    report << L"EXE: " << currentExePath().wstring() << L"\n";
    report << L"EXE SHA-256: " << widen(sha256(currentExePath())) << L"\n";
    report << L"Password accepted: " << (passwordAccepted ? L"yes" : L"no") << L"\n";
    report << L"Payload resources: " << (embeddedPayloadAvailable() ? L"embedded OK" : L"embedded payload missing") << L"\n";
    report << L"Bootstrap extracted: " << (bootstrapOk ? L"yes" : L"no") << L"\n";
    report << L"Bootstrap SHA-256: " << widen(sha256(dll)) << L"\n";
    report << L"Agent extracted: " << (agentOk ? L"yes" : L"no") << L"\n";
    report << L"Agent SHA-256: " << widen(sha256(jar)) << L"\n";
    report << L"Lunar JVM count: " << processes.size() << L"\n";
    for (const auto& process : processes) {
        report << L"  PID " << process.pid << L" exe=" << process.exePath << L" jvm=" << process.jvmPath << L"\n";
    }
    report << L"Fingerprint: " << (fingerprintOk ? L"OK" : L"unsupported or missing") << L"\n";
    report << L"Fingerprint detail: " << widen(fingerprintDetail) << L"\n";
    report << L"Remote manifest URL: " << COMPAT_MANIFEST_URL << L"\n";
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
    report << L"Launcher status: " << status << L"\n";

    auto output = directory / L"diagnostics.txt";
    {
        std::wofstream out(output, std::ios::trunc);
        out << report.str();
    }
    std::filesystem::remove_all(checkDirectory, ec);
    copyToClipboard(windowHandle, report.str());
    writeLauncherLogLine(L"Wrote diagnostics to " + output.wstring());
    if (showMessage) {
        MessageBoxW(windowHandle, (L"Diagnostics copied and saved to:\n" + output.wstring()).c_str(), L"RazorClient Diagnostics", MB_OK | MB_ICONINFORMATION);
    }
    return embeddedPayloadAvailable() && bootstrapOk && agentOk ? 0 : 7;
}

enum class AckStatus { Success, AlreadyInjected, Failed, Timeout };

AckStatus waitForAck(const std::filesystem::path& statusPath, std::wstring& detail) {
    const ULONGLONG start = GetTickCount64();
    std::string lastPhase;
    while (GetTickCount64() - start < 20000) {
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
    if (!passwordAccepted) {
        status = L"Enter password NERVE to enable injection.";
        injectionExitCode = 10;
        writeLauncherLog();
        redraw();
        return;
    }
    auto processes = lunarProcesses();
    if (processes.size() != 1) { status = processes.empty() ? L"Launch Lunar 1.8.9 first, then press Inject." : L"Multiple Lunar JVMs found; close extra instances."; writeLauncherLog(); redraw(); return; }
    ProcessCandidate target = processes.front();
    DWORD pid = target.pid;
    writeLauncherLogLine(L"Selected Lunar JVM pid=" + std::to_wstring(pid) + L" exe=" + target.exePath + L" jvm=" + target.jvmPath);
    LunarBuildDetection lunarBuild = detectLunarBuild();
    if (!lunarBuild.isSupported()) {
        writeLauncherLogLine(L"Unsupported Lunar build fingerprint " + widen(fingerprintDetail));
        status=L"Unsupported Lunar build. This build needs a compatible RazorClient adapter.";
        writeLauncherLog();
        redraw();
        return;
    }
    writeLauncherLogLine(L"Fingerprint OK build=" + widen(lunarBuild.buildName()) + L" adapter=" + widen(lunarBuild.adapterVersion()) + L" " + widen(fingerprintDetail) + L" remote=" + widen(remoteCompatStatus));
    wchar_t local[MAX_PATH]{}; GetEnvironmentVariableW(L"LOCALAPPDATA", local, MAX_PATH);
    std::filesystem::path directory = std::filesystem::path(local) / L"RazorClient";
    std::filesystem::create_directories(directory);
    std::wstring suffix = std::to_wstring(pid) + L"-" + std::to_wstring(GetTickCount64());
    auto dll = directory / (L"razorclient-bootstrap-live-" + suffix + L".dll");
    auto jar = directory / (L"razorclient-agent-live-" + suffix + L".jar");
    auto statusPath = directory / (L"razorclient-status-live-" + suffix + L".jsonl");
    auto metadataPath = directory / (L"razorclient-build-live-" + suffix + L".properties");
    status = L"Injecting into Lunar JVM PID " + std::to_wstring(pid) + L"...";
    writeLauncherLog();
    redraw();
    if (!extract(IDR_BOOTSTRAP, L"razorclient-bootstrap.dll", dll)) {
        status = L"Failed to extract embedded payload: bootstrap DLL.";
    } else if (!extract(IDR_AGENT, L"razorclient-agent.jar", jar)) {
        status = L"Failed to extract embedded payload: agent JAR.";
    } else {
        writeLauncherLogLine(payloadSummary(L"Extracted bootstrap", dll));
        writeLauncherLogLine(payloadSummary(L"Extracted agent", jar));
        writeLauncherLogLine(L"Status file " + statusPath.wstring());
        {
            std::ofstream metadata(metadataPath, std::ios::trunc);
            metadata << "buildName=" << lunarBuild.buildName() << "\n";
            metadata << "adapterVersion=" << lunarBuild.adapterVersion() << "\n";
            metadata << "bakeHash=" << lunarBuild.bakeHash << "\n";
            metadata << "mappingsHash=" << lunarBuild.mappingsHash << "\n";
        }
        writeLauncherLogLine(L"Build metadata " + metadataPath.wstring());
        if (!inject(pid, dll)) {
            status = L"Injection failed. Error " + std::to_wstring(lastInjectError) + L". See %LOCALAPPDATA%\\RazorClient\\bootstrap.log";
        } else {
            status = L"Bootstrap loaded; waiting for Java payload confirmation...";
            writeLauncherLog();
            redraw();
            std::wstring ackDetail;
            AckStatus ack = waitForAck(statusPath, ackDetail);
            if (ack == AckStatus::Success) {
                status = L"Injected. Press Right Shift in game.";
                injectionExitCode = 0;
            } else if (ack == AckStatus::AlreadyInjected) {
                status = L"Already injected.";
                injectionExitCode = 0;
            } else {
                status = ackDetail;
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
    DrawTextW(dc, status.c_str(), -1, &statusText, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);

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
    if (injectButton) {
        EnableWindow(injectButton, passwordAccepted && embeddedPayloadAvailable());
        InvalidateRect(injectButton, nullptr, TRUE);
    }
}

void unlockPasswordFromUi() {
    wchar_t value[128]{};
    GetWindowTextW(passwordEdit, value, 128);
    if (wcscmp(value, L"NERVE") == 0) {
        passwordAccepted = true;
        status = L"Password accepted. Launch Lunar 1.8.9 first, then press Inject.";
    } else {
        passwordAccepted = false;
        status = L"Invalid password.";
    }
    refreshInjectButton();
    writeLauncherLog();
    redraw();
}

LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_CREATE) {
        titleFont = makeFont(18, FW_SEMIBOLD);
        bodyFont = makeFont(10, FW_NORMAL);
        buttonFont = makeFont(11, FW_SEMIBOLD);
        editBrush = solidBrush(rgb(8, 16, 10));
        payloadStatus = embeddedPayloadAvailable() ? L"Payload: embedded OK" : L"Invalid release build: embedded payload missing";
        if (!embeddedPayloadAvailable()) {
            status = L"Invalid release build: embedded payload missing.";
        }
        passwordEdit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_PASSWORD | ES_AUTOHSCROLL,
            132, 166, 160, 28, hwnd, reinterpret_cast<HMENU>(4), nullptr, nullptr);
        unlockButton = CreateWindowW(L"BUTTON", L"Unlock", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            306, 160, 98, 40, hwnd, reinterpret_cast<HMENU>(3), nullptr, nullptr);
        injectButton = CreateWindowW(L"BUTTON", L"Inject", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            95, 224, 140, 42, hwnd, reinterpret_cast<HMENU>(1), nullptr, nullptr);
        diagnosticsButton = CreateWindowW(L"BUTTON", L"Diagnostics", WS_CHILD | WS_VISIBLE | BS_OWNERDRAW,
            255, 224, 150, 42, hwnd, reinterpret_cast<HMENU>(2), nullptr, nullptr);
        SendMessageW(passwordEdit, WM_SETFONT, reinterpret_cast<WPARAM>(bodyFont), TRUE);
        SendMessageW(passwordEdit, EM_SETPASSWORDCHAR, static_cast<WPARAM>(0x25CF), 0);
        SendMessageW(injectButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        SendMessageW(diagnosticsButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        SendMessageW(unlockButton, WM_SETFONT, reinterpret_cast<WPARAM>(buttonFont), TRUE);
        refreshInjectButton();
        BOOL dark = TRUE;
        DwmSetWindowAttribute(hwnd, 20, &dark, sizeof(dark));
        return 0;
    }
    if (message == WM_COMMAND && LOWORD(wParam) == 1) { performInjection(); writeLauncherLog(); return 0; }
    if (message == WM_COMMAND && LOWORD(wParam) == 2) { writeDiagnostics(true); return 0; }
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
    if (message == WM_ERASEBKGND) { return TRUE; }
    if (message == WM_PAINT) {
        PAINTSTRUCT paint{};
        HDC dc = BeginPaint(hwnd, &paint);
        paintLauncher(hwnd, dc);
        EndPaint(hwnd, &paint);
        return 0;
    }
    if (message == WM_DESTROY) {
        if (titleFont) DeleteObject(titleFont);
        if (bodyFont) DeleteObject(bodyFont);
        if (buttonFont) DeleteObject(buttonFont);
        if (editBrush) DeleteObject(editBrush);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd,message,wParam,lParam);
}
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int show) {
    appInstance = instance;
    passwordAccepted = commandLineHasPassword();
    if (wcsstr(GetCommandLineW(), L"--inject")) { performInjection(); writeLauncherLog(); return injectionExitCode; }
    if (wcsstr(GetCommandLineW(), L"--self-check")) { return selfCheck(); }
    if (wcsstr(GetCommandLineW(), L"--diagnose")) { return writeDiagnostics(false); }
    WNDCLASSW type{}; type.lpfnWndProc=windowProc; type.hInstance=instance; type.lpszClassName=L"RazorClientLauncher"; type.hCursor=LoadCursor(nullptr,IDC_ARROW); type.hbrBackground=solidBrush(rgb(5,8,5)); RegisterClassW(&type);
    windowHandle=CreateWindowW(type.lpszClassName,L"\x00AE" L"\xFE0F" L"azorClient",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX,CW_USEDEFAULT,CW_USEDEFAULT,500,345,nullptr,nullptr,instance,nullptr);
    ShowWindow(windowHandle,show); UpdateWindow(windowHandle); MSG message{}; while(GetMessageW(&message,nullptr,0,0)){TranslateMessage(&message);DispatchMessageW(&message);} return static_cast<int>(message.wParam);
}
