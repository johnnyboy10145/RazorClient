$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$dist = Join-Path $root 'dist'
$release = Join-Path $project 'release'
$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
$deps = Join-Path $root 'tools\deps'
$llvm = Join-Path $root 'tools\llvm-mingw'
$api = Join-Path $project 'build\lunar\lunar-runtime-api.jar'

$javac = "${env:ProgramFiles}\Java\jdk-21.0.10\bin\javac.exe"
$jar = "${env:ProgramFiles}\Java\jdk-21.0.10\bin\jar.exe"
if (!(Test-Path $javac)) { $javac = (Get-Command javac.exe).Source }
if (!(Test-Path $jar)) { $jar = (Get-Command jar.exe).Source }

$clang = Join-Path $llvm 'bin\x86_64-w64-mingw32-clang++.exe'
$windres = Join-Path $llvm 'bin\x86_64-w64-mingw32-windres.exe'
if (!(Test-Path $clang)) { throw "Missing native compiler: $clang" }
if (!(Test-Path $windres)) { throw "Missing windres: $windres" }
if (!(Test-Path $api)) { throw "Missing Lunar API jar: $api" }

Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item $dist -ItemType Directory -Force | Out-Null
Remove-Item $release -Recurse -Force -ErrorAction SilentlyContinue
New-Item $release -ItemType Directory -Force | Out-Null
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item $classes -ItemType Directory -Force | Out-Null

$sourceList = Join-Path $build 'sources.txt'
$payloadSources = Get-ChildItem (Join-Path $root 'payload\src') -Recurse -Filter '*.java' | ForEach-Object FullName
$clientSources = Get-ChildItem (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' |
    Where-Object {
        $_.FullName -notmatch '\\mixin\\' -and
        $_.FullName -notmatch '\\com\\razorclient\\RazorClient\.java$'
    } |
    ForEach-Object FullName
($payloadSources + $clientSources) | Set-Content -LiteralPath $sourceList -Encoding ASCII

$asmCp = @(
    Join-Path $deps 'asm-9.7.1.jar'
    Join-Path $deps 'asm-tree-9.7.1.jar'
    Join-Path $deps 'asm-commons-9.7.1.jar'
) -join ';'

& $javac -encoding UTF-8 --release 17 -cp "$api;$asmCp" -d $classes "@$sourceList"
if ($LASTEXITCODE) { throw 'Java payload compilation failed.' }

$resources = Join-Path $project 'src\main\resources'
if (Test-Path $resources) {
    Get-ChildItem $resources -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($resources.Length).TrimStart('\')
        $target = Join-Path $classes $relative
        New-Item (Split-Path -Parent $target) -ItemType Directory -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}

$agent = Join-Path $dist 'razorclient-agent.jar'
Remove-Item $agent -Force -ErrorAction SilentlyContinue
Push-Location $classes
try {
    & $jar cfm $agent (Join-Path $root 'payload\MANIFEST.MF') .
    if ($LASTEXITCODE) { throw 'Agent jar creation failed.' }
    foreach ($dep in @($asmCp -split ';')) {
        & $jar xf $dep
        if ($LASTEXITCODE) { throw "ASM shading failed: $dep" }
    }
    & $jar uf $agent org
    if ($LASTEXITCODE) { throw 'Agent jar ASM update failed.' }
} finally {
    Pop-Location
}

$jniInclude = "${env:ProgramFiles}\Java\jdk-21.0.10\include"
if (!(Test-Path $jniInclude)) { $jniInclude = Join-Path (Split-Path -Parent (Split-Path -Parent $javac)) 'include' }
$jniWin = Join-Path $jniInclude 'win32'

& $clang -std=c++17 -O2 -shared -static -DUNICODE -D_UNICODE `
    -I $jniInclude -I $jniWin `
    -o (Join-Path $dist 'razorclient-bootstrap.dll') `
    (Join-Path $root 'native\bootstrap\bootstrap.cpp') `
    -ladvapi32 -luser32
if ($LASTEXITCODE) { throw 'Bootstrap DLL build failed.' }

$rcTemplate = Get-Content -LiteralPath (Join-Path $root 'native\launcher\resources.rc.in') -Raw
$rc = $rcTemplate.
    Replace('@BOOTSTRAP_DLL@', (Join-Path $dist 'razorclient-bootstrap.dll').Replace('\','\\')).
    Replace('@AGENT_JAR@', (Join-Path $dist 'razorclient-agent.jar').Replace('\','\\'))
$rcPath = Join-Path $build 'native\resources.rc'
$resPath = Join-Path $build 'native\resources.o'
New-Item (Split-Path -Parent $rcPath) -ItemType Directory -Force | Out-Null
Set-Content -LiteralPath $rcPath -Value $rc -Encoding ASCII

& $windres -I (Join-Path $root 'native\launcher') $rcPath -O coff -o $resPath
if ($LASTEXITCODE) { throw 'Resource compilation failed.' }

& $clang -std=c++17 -O2 -static -mwindows -municode -DUNICODE -D_UNICODE `
    -o (Join-Path $dist 'RazorClient.exe') `
    (Join-Path $root 'native\launcher\main.cpp') $resPath `
    -luser32 -lshell32 -lshlwapi -lpsapi -lbcrypt -ladvapi32 -ldwmapi -lwinhttp
if ($LASTEXITCODE) { throw 'Launcher build failed.' }

Copy-Item -LiteralPath (Join-Path $dist 'RazorClient.exe') -Destination (Join-Path $release 'RazorClient.exe') -Force

Get-FileHash -Algorithm SHA256 (Join-Path $dist 'RazorClient.exe'), $agent, (Join-Path $dist 'razorclient-bootstrap.dll')
Get-FileHash -Algorithm SHA256 (Join-Path $release 'RazorClient.exe')
