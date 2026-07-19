# Building RazorClient on Windows

The supported full build is orchestrated by `injectable/build.ps1`. Visual Studio 2022/MSVC is the default native toolchain; the pinned LLVM-MinGW toolchain remains available as a fallback.

## Requirements

- Windows x64.
- Visual Studio 2022 or newer with:
  - Desktop development with C++.
  - MSVC x64/x86 build tools.
  - CMake tools for Windows.
  - Windows SDK 10.0.20348.0 or newer.
- JDK 21.0.10 installed under `C:\Program Files\Java\jdk-21.0.10`.
- vcpkg with `VCPKG_ROOT` pointing to its installation directory.
- PowerShell 5.1 or newer.

Install the header-only JSON dependency from a Visual Studio Developer PowerShell:

```powershell
vcpkg install nlohmann-json:x64-windows-static
vcpkg integrate install
```

The build prefers the vcpkg package when its toolchain is available. A checksum-pinned nlohmann/json 3.11.3 header is retained as an offline fallback.

## Debug build

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\injectable\build.ps1 `
    -Mode Debug `
    -Toolchain MSVC
```

The native targets use the Visual Studio 2022 x64 generator, C++17, Unicode, and the static MSVC runtime (`/MT`) in every configuration.

## Signed Release build

Configure the signing environment before starting a Release build:

```powershell
$env:RAZORCLIENT_AUTHENTICODE_PFX_B64 = '<base64-pfx>'
$env:RAZORCLIENT_AUTHENTICODE_PASSWORD = '<pfx-password>'
$env:RAZORCLIENT_JAR_KEYSTORE_B64 = '<base64-pkcs12-keystore>'
$env:RAZORCLIENT_JAR_KEYSTORE_PASSWORD = '<keystore-password>'
$env:RAZORCLIENT_JAR_ALIAS = 'razorclient-release'       # optional
$env:RAZORCLIENT_JAR_KEYSTORE_TYPE = 'PKCS12'            # optional
$env:RAZORCLIENT_TIMESTAMP_URL = 'https://timestamp.digicert.com' # optional

powershell -ExecutionPolicy Bypass -File .\injectable\build.ps1 `
    -Mode Release `
    -Toolchain MSVC
```

Release preflight validates all signing tools and credentials before staged artifacts are created or promoted.

## LLVM-MinGW fallback

```powershell
powershell -ExecutionPolicy Bypass -File .\injectable\build.ps1 `
    -Mode Debug `
    -Toolchain LLVM
```

The LLVM path uses the checksum-pinned compiler under `injectable/tools/llvm-mingw` and produces the same payload inventory and one-file launcher format.

## Native libraries

The launcher links `user32`, `kernel32`, `gdi32`, `winhttp`, `psapi`, `crypt32`, `advapi32`, `shell32`, `shlwapi`, `bcrypt`, `wintrust`, and `dwmapi`. The bootstrap additionally links `opengl32`.

## Output and verification

Successful builds atomically publish:

- `injectable/dist/RazorClient.exe`
- `injectable/dist/razorclient-bootstrap.dll`
- `injectable/dist/razorclient-agent.jar`
- `release/RazorClient.exe`

Run the validation chain:

```powershell
powershell -ExecutionPolicy Bypass -File .\injectable\verify.ps1
powershell -ExecutionPolicy Bypass -File .\injectable\security-verify.ps1 -Mode Debug

$selfCheck = Start-Process .\release\RazorClient.exe -ArgumentList '--self-check' -Wait -PassThru
if ($selfCheck.ExitCode -ne 0) { throw "Self-check failed: $($selfCheck.ExitCode)" }
```

Release verification uses `-Mode Release`. The release directory must contain only `RazorClient.exe`.
