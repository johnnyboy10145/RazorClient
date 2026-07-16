param(
    [ValidateSet('Debug', 'Release')]
    [string]$Mode = 'Debug'
)

$ErrorActionPreference = 'Stop'
$releaseBuild = $Mode -eq 'Release'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$build = Join-Path $root 'build'
$distFinal = Join-Path $root 'dist'
$releaseFinal = Join-Path $project 'release'
$stageRoot = Join-Path $build ("staging-" + $Mode.ToLowerInvariant() + '-' + $PID + '-' + [Guid]::NewGuid().ToString('N'))
$dist = Join-Path $stageRoot 'dist'
$release = Join-Path $stageRoot 'release'
$classes = Join-Path $stageRoot 'classes'
$deps = Join-Path $root 'tools\deps'
$llvm = Join-Path $root 'tools\llvm-mingw'
$api = Join-Path $project 'build\lunar\lunar-runtime-api.jar'
$obfuscatorBase = Join-Path $deps 'proguard-base-7.8.1.jar'
$obfuscatorCore = Join-Path $deps 'proguard-core-9.2.0.jar'
$kotlinStdlib = Join-Path $deps 'kotlin-stdlib-2.2.0.jar'
$gson = Join-Path $deps 'gson-2.11.0.jar'
$log4jApi = Join-Path $deps 'log4j-api-2.24.2.jar'
$log4jCore = Join-Path $deps 'log4j-core-2.24.2.jar'
$json = Join-Path $deps 'json-20231013.jar'
$compatPublicKey = Join-Path $root 'config\compat-public-key.b64'

function Assert-Sha256([string]$path, [string]$expected) {
    if (!(Test-Path -LiteralPath $path)) { throw "Missing pinned dependency: $path" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToUpperInvariant()
    if ($actual -ne $expected.ToUpperInvariant()) { throw "SHA-256 mismatch for $path. Expected $expected, got $actual" }
}

Assert-Sha256 $api 'C4C05056FB035665CBE3128E64A8A6E3EC0A1BDF791A4B8A4BB9816B1296754F'
Assert-Sha256 (Join-Path $deps 'asm-9.7.1.jar') '8CADD43AC5EB6D09DE05FAECCA38B917A040BB9139C7EDEB4CC81C740B713281'
Assert-Sha256 (Join-Path $deps 'asm-tree-9.7.1.jar') '9929881F59EB6B840E86D54570C77B59CE721D104E6DFD7A40978991C2D3B41F'
Assert-Sha256 (Join-Path $deps 'asm-commons-9.7.1.jar') '9A579B54D292AD9BE171D4313FD4739C635592C2B5AC3A459BBD1049CDDEC6A0'

if ($releaseBuild) {
    Assert-Sha256 $obfuscatorBase '72928B9C43DD1D4ABDB906F612BC53E57521CC629FD05A6543EDC21F89A5F340'
    Assert-Sha256 $obfuscatorCore '485FFF6CD365FFCD60A433B2108405DAB44999901FAB6BE3197A797EEED1E989'
    Assert-Sha256 $kotlinStdlib '65D12D85A3B865C160DB9147851712A64B10DADD68B22EEA22A95BF8A8670DCA'
    Assert-Sha256 $gson '57928D6E5A6EDEB2ABD3770A8F95BA44DCE45F3B23B7A9DC2B309C581552A78B'
    Assert-Sha256 $log4jApi '0CA3ECBD4C315BDD5F2EF6AF127712DF718C78334CCE5BF6FB7B4AA17FDAB126'
    Assert-Sha256 $log4jCore '7A7B90DB866C86A1093B3FDE758CA28398EBB2A533DA0D79A30CC0A78506B9DF'
    Assert-Sha256 $json '0F18192DF289114E17AA1A0D0A7F8372CC9F5C7E4F7E39ADCF8906FE714FA7D3'
    if (!(Test-Path -LiteralPath $compatPublicKey)) { throw "Missing compatibility manifest public key: $compatPublicKey" }
    $publicKeyText = (Get-Content -LiteralPath $compatPublicKey -Raw).Trim()
    if ($publicKeyText.Length -lt 32 -or $publicKeyText -match 'REPLACE_ME') { throw 'Compatibility manifest public key is not configured.' }
}

$javac = "${env:ProgramFiles}\Java\jdk-21.0.10\bin\javac.exe"
$jar = "${env:ProgramFiles}\Java\jdk-21.0.10\bin\jar.exe"
if (!(Test-Path $javac) -or !(Test-Path $jar)) { throw 'Pinned JDK 21.0.10 is required.' }
if ((Get-Item -LiteralPath $javac).VersionInfo.ProductVersion -notlike '21.0.10*') {
    throw 'Unexpected javac version; pinned JDK 21.0.10 is required.'
}
$archiveTimestamp = '2020-01-01T00:00:00Z'

$clang = Join-Path $llvm 'bin\x86_64-w64-mingw32-clang++.exe'
$windres = Join-Path $llvm 'bin\x86_64-w64-mingw32-windres.exe'
if (!(Test-Path $clang)) { throw "Missing native compiler: $clang" }
if (!(Test-Path $windres)) { throw "Missing windres: $windres" }
if (!(Test-Path $api)) { throw "Missing Lunar API jar: $api" }
$clangVersion = (& $clang --version | Select-Object -First 1)
if ($clangVersion -notmatch '^clang version 22\.1\.8\b') { throw "Unexpected LLVM toolchain: $clangVersion" }

if ($releaseBuild) {
    & (Join-Path $root 'sign-release.ps1') -ArtifactType Jar -Preflight
    & (Join-Path $root 'sign-release.ps1') -ArtifactType Authenticode -Preflight
}

Remove-Item $stageRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item $dist -ItemType Directory -Force | Out-Null
New-Item $release -ItemType Directory -Force | Out-Null
Remove-Item $classes -Recurse -Force -ErrorAction SilentlyContinue
New-Item $classes -ItemType Directory -Force | Out-Null

$obfuscatedJar = Join-Path $stageRoot 'razorclient-agent-obfuscated.jar'
$mapping = Join-Path $stageRoot 'razorclient-release.map'
$proguardConfig = Join-Path $stageRoot 'proguard-release.pro'
Remove-Item $obfuscatedJar, $mapping, $proguardConfig -Force -ErrorAction SilentlyContinue

$sourceList = Join-Path $stageRoot 'sources.txt'
$payloadSources = Get-ChildItem (Join-Path $root 'payload\src') -Recurse -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName
$clientSources = Get-ChildItem (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' |
    Where-Object {
        $_.FullName -notmatch '\\mixin\\' -and
        $_.FullName -notmatch '\\com\\razorclient\\RazorClient\.java$'
    } | Sort-Object FullName |
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
    Get-ChildItem $resources -Recurse -File |
        Where-Object { $_.Name -notin @('mcmod.info', 'mixins.razorclient.json') } |
        ForEach-Object {
        $relative = $_.FullName.Substring($resources.Length).TrimStart('\')
        $target = Join-Path $classes $relative
        New-Item (Split-Path -Parent $target) -ItemType Directory -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $target -Force
    }
}

$agent = Join-Path $dist 'razorclient-agent.jar'
Remove-Item $agent -Force -ErrorAction SilentlyContinue
if ($releaseBuild) {
    $javaHome = Split-Path -Parent (Split-Path -Parent $javac)
    $libraryJars = @(
        $api,
        (Join-Path $deps 'asm-9.7.1.jar'),
        (Join-Path $deps 'asm-tree-9.7.1.jar'),
        (Join-Path $deps 'asm-commons-9.7.1.jar'),
        (Join-Path $javaHome 'jmods\java.base.jmod')
    )
    $proguardLines = @(
        "-injars `"$classes`"",
        "-outjars `"$obfuscatedJar`"",
        ($libraryJars | ForEach-Object { "-libraryjars `"$_`"" }),
        '-dontshrink',
        '-dontoptimize',
        '-dontpreverify',
        '-dontwarn **',
        '-ignorewarnings',
        '-dontusemixedcaseclassnames',
        '-keepattributes Exceptions,InnerClasses,Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault',
        "-printmapping `"$mapping`"",
        '-keep public class com.razorclient.RazorClient { public *; }',
        '-keep public class com.razorclient.inject.LiveEntrypoint { public *; }',
        '-keep public class com.razorclient.inject.ClientHooks { public *; }',
        '-keep public class com.razorclient.inject.LunarAccess { public *; }',
        '-keep class com.razorclient.inject.** { *; }',
        '-keep class com.razorclient.event.** { *; }',
        '-keep class com.razorclient.feature.module.ModuleManager { *; }',
        '-keep class com.razorclient.feature.module.Module { *; }',
        '-keepnames class com.razorclient.runtime.**',
        '-keep public class com.razorclient.feature.module.impl.** { public <init>(...); }',
        '-keep class net.minecraft.** { *; }',
        '-keep class net.minecraftforge.** { *; }',
        '-keep class org.lwjgl.** { *; }',
        '-keep class org.objectweb.asm.** { *; }',
        '-keepnames class com.razorclient.feature.module.impl.**',
        '-adaptclassstrings'
    )
    $proguardLines | Set-Content -LiteralPath $proguardConfig -Encoding ASCII
    $proguardCp = @($obfuscatorBase, $obfuscatorCore, $kotlinStdlib, $gson, $log4jApi, $log4jCore, $json) -join ';'
    $java = $javac -replace 'javac\.exe$', 'java.exe'
    & $java -cp $proguardCp proguard.ProGuard ("@$proguardConfig")
    if ($LASTEXITCODE) { throw 'Release Java obfuscation failed.' }
    if (!(Test-Path -LiteralPath $obfuscatedJar) -or !(Test-Path -LiteralPath $mapping)) { throw 'Release obfuscation output is incomplete.' }
    Copy-Item -LiteralPath $obfuscatedJar -Destination $agent -Force
    & $jar --update --file $agent --manifest (Join-Path $root 'payload\MANIFEST.MF') "--date=$archiveTimestamp"
    if ($LASTEXITCODE) { throw 'Obfuscated agent manifest update failed.' }
} else {
    Push-Location $classes
    try {
        & $jar --create --file $agent --manifest (Join-Path $root 'payload\MANIFEST.MF') "--date=$archiveTimestamp" .
        if ($LASTEXITCODE) { throw 'Agent jar creation failed.' }
    } finally {
        Pop-Location
    }
}

Push-Location $classes
try {
    foreach ($dep in @($asmCp -split ';')) {
        & $jar xf $dep
        if ($LASTEXITCODE) { throw "ASM shading failed: $dep" }
    }
    & $jar --update --file $agent "--date=$archiveTimestamp" org
    if ($LASTEXITCODE) { throw 'Agent jar ASM update failed.' }
} finally {
    Pop-Location
}
if ($releaseBuild) {
    $jarSignerMetadata = Join-Path $stageRoot 'jar-signer.sha256'
    & (Join-Path $root 'sign-release.ps1') -ArtifactType Jar -Path $agent -SignerMetadataPath $jarSignerMetadata
    if ($LASTEXITCODE) { throw 'Signed agent JAR creation failed.' }
}

$jniInclude = "${env:ProgramFiles}\Java\jdk-21.0.10\include"
if (!(Test-Path $jniInclude)) { $jniInclude = Join-Path (Split-Path -Parent (Split-Path -Parent $javac)) 'include' }
$jniWin = Join-Path $jniInclude 'win32'
$nativeBuild = Join-Path $stageRoot 'native'
New-Item $nativeBuild -ItemType Directory -Force | Out-Null
$publicKeyText = if (Test-Path -LiteralPath $compatPublicKey) { (Get-Content -LiteralPath $compatPublicKey -Raw).Trim() } else { '' }
$headerLines = @(
    '#pragma once',
    ('static constexpr const char* RAZORCLIENT_COMPAT_PUBLIC_KEY_B64 = R"KEY(' + $publicKeyText + ')KEY";')
)
$headerLines | Set-Content -LiteralPath (Join-Path $nativeBuild 'compat_public_key.h') -Encoding ASCII

$agentHash = (Get-FileHash -Algorithm SHA256 $agent).Hash.ToUpperInvariant()
$jarSignerHash = if ($releaseBuild) { (Get-Content -LiteralPath $jarSignerMetadata -Raw).Trim().ToUpperInvariant() } else { '' }
if ($releaseBuild -and $jarSignerHash -notmatch '^[0-9A-F]{64}$') { throw 'JAR signer fingerprint metadata is invalid.' }
@(
    '#pragma once',
    ('static constexpr const char* RAZORCLIENT_EXPECTED_AGENT_SHA256 = "' + $agentHash + '";'),
    ('static constexpr const char* RAZORCLIENT_EXPECTED_JAR_SIGNER_SHA256 = "' + $jarSignerHash + '";')
) | Set-Content -LiteralPath (Join-Path $nativeBuild 'payload_security.h') -Encoding ASCII

& $clang -std=c++17 -O2 -ffunction-sections -fdata-sections -shared -static -DUNICODE -D_UNICODE `
    $(if ($releaseBuild) { '-DRAZORCLIENT_RELEASE' } else { '' }) `
    -I $jniInclude -I $jniWin -I $nativeBuild `
    -o (Join-Path $dist 'razorclient-bootstrap.dll') `
    (Join-Path $root 'native\bootstrap\bootstrap.cpp') `
    '-Wl,--gc-sections,--no-insert-timestamp' $(if ($releaseBuild) { '-s' } else { '' }) `
    -ladvapi32 -luser32 -lgdi32 -lopengl32 -lbcrypt
if ($LASTEXITCODE) { throw 'Bootstrap DLL build failed.' }
if ($releaseBuild) {
    & (Join-Path $root 'sign-release.ps1') -ArtifactType Authenticode -Path (Join-Path $dist 'razorclient-bootstrap.dll')
    if ($LASTEXITCODE) { throw 'Signed bootstrap DLL creation failed.' }
}
$bootstrapHash = (Get-FileHash -Algorithm SHA256 (Join-Path $dist 'razorclient-bootstrap.dll')).Hash.ToUpperInvariant()
$hashHeader = @(
    '#pragma once',
    ('static constexpr const char* RAZORCLIENT_EXPECTED_BOOTSTRAP_SHA256 = "' + $bootstrapHash + '";'),
    ('static constexpr const char* RAZORCLIENT_EXPECTED_AGENT_SHA256 = "' + $agentHash + '";')
)
$hashHeader | Set-Content -LiteralPath (Join-Path $nativeBuild 'payload_hashes.h') -Encoding ASCII

$rcTemplate = Get-Content -LiteralPath (Join-Path $root 'native\launcher\resources.rc.in') -Raw
$rc = $rcTemplate.
    Replace('@BOOTSTRAP_DLL@', (Join-Path $dist 'razorclient-bootstrap.dll').Replace('\','\\')).
    Replace('@AGENT_JAR@', (Join-Path $dist 'razorclient-agent.jar').Replace('\','\\'))
$rcPath = Join-Path $nativeBuild 'resources.rc'
$resPath = Join-Path $nativeBuild 'resources.o'
New-Item (Split-Path -Parent $rcPath) -ItemType Directory -Force | Out-Null
Set-Content -LiteralPath $rcPath -Value $rc -Encoding ASCII

& $windres -I (Join-Path $root 'native\launcher') $rcPath -O coff -o $resPath
if ($LASTEXITCODE) { throw 'Resource compilation failed.' }

& $clang -std=c++17 -O2 -ffunction-sections -fdata-sections -static -mwindows -municode -DUNICODE -D_UNICODE `
    $(if ($releaseBuild) { '-DRAZORCLIENT_RELEASE' } else { '' }) `
    -I $nativeBuild `
    -o (Join-Path $dist 'RazorClient.exe') `
    (Join-Path $root 'native\launcher\main.cpp') $resPath `
    '-Wl,--gc-sections,--no-insert-timestamp' $(if ($releaseBuild) { '-s' } else { '' }) `
    -luser32 -lshell32 -lshlwapi -lpsapi -lbcrypt -lcrypt32 -lwintrust -ladvapi32 -ldwmapi -lwinhttp
if ($LASTEXITCODE) { throw 'Launcher build failed.' }

if ($releaseBuild) {
    & (Join-Path $root 'sign-release.ps1') -ArtifactType Authenticode -Path (Join-Path $dist 'RazorClient.exe')
    if ($LASTEXITCODE) { throw 'Signed launcher creation failed.' }
}

Copy-Item -LiteralPath (Join-Path $dist 'RazorClient.exe') -Destination (Join-Path $release 'RazorClient.exe') -Force

@(
    "schemaVersion=1",
    "buildMode=$Mode",
    "obfuscatorVersion=$(if ($releaseBuild) { 'proguard-7.8.1' } else { 'none' })",
    "launcherHash=$((Get-FileHash -Algorithm SHA256 (Join-Path $dist 'RazorClient.exe')).Hash)",
    "bootstrapHash=$((Get-FileHash -Algorithm SHA256 (Join-Path $dist 'razorclient-bootstrap.dll')).Hash)",
    "agentHash=$((Get-FileHash -Algorithm SHA256 $agent).Hash)",
    "jarSignerHash=$jarSignerHash"
) | Set-Content -LiteralPath (Join-Path $dist 'build-metadata.properties') -Encoding ASCII

function Promote-Directory([string]$staged, [string]$destination) {
    if (!(Test-Path -LiteralPath $staged -PathType Container)) { throw "Staged output is missing: $staged" }
    $backup = $destination + '.previous'
    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $destination) { Move-Item -LiteralPath $destination -Destination $backup }
    try {
        Move-Item -LiteralPath $staged -Destination $destination
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        Remove-Item -LiteralPath $destination -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $backup) { Move-Item -LiteralPath $backup -Destination $destination }
        throw
    }
}

$promotionMutex = New-Object Threading.Mutex($false, 'Local\RazorClientBuildPromotion')
$promotionLockTaken = $false
try {
    $promotionLockTaken = $promotionMutex.WaitOne([TimeSpan]::FromMinutes(2))
    if (!$promotionLockTaken) { throw 'Timed out waiting to promote RazorClient build artifacts.' }
    Promote-Directory $dist $distFinal
    Promote-Directory $release $releaseFinal
} finally {
    if ($promotionLockTaken) { $promotionMutex.ReleaseMutex() }
    $promotionMutex.Dispose()
}
Remove-Item -LiteralPath $stageRoot -Recurse -Force -ErrorAction SilentlyContinue

$finalArtifacts = @(
    (Join-Path $distFinal 'RazorClient.exe'),
    (Join-Path $distFinal 'razorclient-agent.jar'),
    (Join-Path $distFinal 'razorclient-bootstrap.dll')
)
Get-FileHash -Algorithm SHA256 -LiteralPath $finalArtifacts
Get-FileHash -Algorithm SHA256 (Join-Path $releaseFinal 'RazorClient.exe')
