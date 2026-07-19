param(
    [ValidateSet('Debug', 'Release')]
    [string]$Mode = 'Debug'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$dist = Join-Path $root 'dist'
$release = Join-Path $project 'release'
$agent = Join-Path $dist 'razorclient-agent.jar'
$exe = Join-Path $dist 'RazorClient.exe'
$bootstrap = Join-Path $dist 'razorclient-bootstrap.dll'
$jar = Get-Command jar.exe -ErrorAction SilentlyContinue
if (!$jar -and (Test-Path 'C:\Program Files\Java\jdk-21.0.10\bin\jar.exe')) {
    $jar = Get-Item 'C:\Program Files\Java\jdk-21.0.10\bin\jar.exe'
}
$jarPath = if ($jar.Source) { $jar.Source } else { $jar.FullName }

if (!(Test-Path $exe) -or !(Test-Path $agent) -or !(Test-Path $bootstrap)) { throw 'Build artifacts are missing.' }
$metadata = Join-Path $dist 'build-metadata.properties'
if (!(Test-Path $metadata)) { throw 'Build metadata is missing.' }
$metadataText = Get-Content $metadata -Raw
foreach ($field in @('schemaVersion=', 'buildMode=', 'nativeToolchain=', 'launcherHash=', 'bootstrapHash=', 'agentHash=',
        'encryptedBootstrapHash=', 'encryptedAgentHash=', 'payloadEncryption=', 'jarSignerHash=')) {
    if ($metadataText -notmatch [regex]::Escape($field)) { throw "Build metadata field is missing: $field" }
}
$metadataValues = @{}
foreach ($line in Get-Content $metadata) {
    $separator = $line.IndexOf('=')
    if ($separator -gt 0) { $metadataValues[$line.Substring(0, $separator)] = $line.Substring($separator + 1).Trim() }
}
if ($metadataValues['buildMode'] -ne $Mode) { throw "Build metadata mode is $($metadataValues['buildMode']), expected $Mode." }
foreach ($artifact in @(
    @{ Key = 'launcherHash'; Path = $exe },
    @{ Key = 'bootstrapHash'; Path = $bootstrap },
    @{ Key = 'agentHash'; Path = $agent }
)) {
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifact.Path).Hash.ToUpperInvariant()
    if ($metadataValues[$artifact.Key].ToUpperInvariant() -ne $actualHash) {
        throw "Build metadata hash mismatch: $($artifact.Key)"
    }
}
if (!(Test-Path (Join-Path $root 'config\compat-public-key.b64'))) { throw 'Compatibility public key is missing.' }
$key = [Convert]::FromBase64String((Get-Content (Join-Path $root 'config\compat-public-key.b64') -Raw).Trim())
if ($key.Length -lt 32) { throw 'Compatibility public key is invalid.' }

$releaseFiles = @(Get-ChildItem $release -File)
if ($releaseFiles.Count -ne 1 -or $releaseFiles[0].Name -ne 'RazorClient.exe') { throw 'Release must contain only RazorClient.exe.' }
foreach ($forbidden in @('razorclient-agent.jar', 'razorclient-bootstrap.dll', '*.pdb', '*.map')) {
    if (Get-ChildItem $release -Filter $forbidden -File -ErrorAction SilentlyContinue) { throw "Forbidden release artifact present: $forbidden" }
}

$launcherSource = Get-Content (Join-Path $root 'native\launcher\main.cpp') -Raw
foreach ($marker in @('verifyManifestEnvelope', 'BCryptVerifySignature', 'sha256Bytes', 'digest.data()',
    'MAX_MANIFEST_ENVELOPE_BYTES', 'writeTextFileAtomic', 'manifestVersion', 'processCreationTime',
    'executableHash', 'jvmHash', 'ModuleLoader loader', 'RemoteMemoryGuard remoteStatus',
    'DataCrypto::DecryptResource', 'RazorClient_Bootstrap_Loaded_', 'WM_INJECTION_FINISHED',
    'findReusableBootstrap', 'RazorClientRestart', 'RazorClient.Inject.')) {
    if ($launcherSource -notmatch [regex]::Escape($marker)) { throw "Launcher security marker is missing: $marker" }
}
if ($launcherSource -notmatch '#ifndef RAZORCLIENT_RELEASE\s+std::string genericAdapter') { throw 'Generic Lunar compatibility must be Debug-only.' }
if ($launcherSource -notmatch 'https://') { throw 'HTTPS compatibility validation is missing.' }
if ($launcherSource -match 'ManualMap|manual.?mapping|ProxyDll|DriverEntry|NtMapViewOfSection') { throw 'Covert mapping/proxy/kernel loader code is present.' }
$bootstrapSource = Get-Content (Join-Path $root 'native\bootstrap\bootstrap.cpp') -Raw
$jarVerifierSource = Get-Content (Join-Path $root 'payload\src\com\razorclient\inject\JarSignatureVerifier.java') -Raw
foreach ($marker in @('RAZORCLIENT_EXPECTED_AGENT_SHA256', 'sha256File', 'RAZORCLIENT_EXPECTED_JAR_SIGNER_SHA256',
        'RazorClientRestart', 'initializationRunning', 'AGENT_SEARCH_REUSED', 'signalBootstrapLoaded')) {
    if ($bootstrapSource -notmatch $marker) { throw "Bootstrap payload integrity marker is missing: $marker" }
}
foreach ($marker in @('getCodeSigners', 'containsExpectedSigner', 'SHA-256', 'META-INF/')) {
    if ($jarVerifierSource -notmatch [regex]::Escape($marker)) { throw "JAR signer enforcement marker is missing: $marker" }
}
$buildSource = Get-Content (Join-Path $root 'build.ps1') -Raw
if ($buildSource -notmatch '-Preflight' -or $buildSource -notmatch 'staging-' -or $buildSource -notmatch 'Promote-Directory') {
    throw 'Release preflight or staged artifact promotion is missing.'
}
foreach ($marker in @('payload-encrypt.exe', 'razorclient-bootstrap.enc', 'razorclient-agent.enc',
        'RAZORCLIENT_PAYLOAD_KEY_LITERAL', 'nlohmann-json-3.11.3')) {
    if ($buildSource -notmatch [regex]::Escape($marker)) { throw "Encrypted resource build marker is missing: $marker" }
}

if (!$jar) { throw 'jar.exe is required for payload inventory checks.' }
$entries = & $jarPath tf $agent
foreach ($required in @(
    'com/razorclient/inject/LiveEntrypoint.class',
    'com/razorclient/inject/ClientHooks.class',
    'com/razorclient/inject/JarSignatureVerifier.class',
    'com/razorclient/runtime/SecureStringTable.class'
)) {
    if ($entries -notcontains $required) { throw "Payload entrypoint missing: $required" }
}
if ($Mode -eq 'Release') {
    if ($metadataValues['jarSignerHash'] -notmatch '^[0-9A-Fa-f]{64}$') { throw 'Release JAR signer pin is missing or invalid.' }
    if (Get-ChildItem $dist, $release -Filter '*.map' -File -ErrorAction SilentlyContinue) { throw 'Release mapping must not be shipped beside release artifacts.' }
    $signtool = Get-Command signtool.exe -ErrorAction SilentlyContinue
    if (!$signtool) {
        $signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
    }
    $jarsigner = Get-Command jarsigner.exe -ErrorAction SilentlyContinue
    if (!$jarsigner -and (Test-Path 'C:\Program Files\Java\jdk-21.0.10\bin\jarsigner.exe')) {
        $jarsigner = Get-Item 'C:\Program Files\Java\jdk-21.0.10\bin\jarsigner.exe'
    }
    if (!$signtool -or !$jarsigner) { throw 'Release verification requires signtool.exe and jarsigner.exe.' }
    $signtoolPath = if ($signtool.Source) { $signtool.Source } else { $signtool.FullName }
    $jarsignerPath = if ($jarsigner.Source) { $jarsigner.Source } else { $jarsigner.FullName }
    & $signtoolPath verify /pa /quiet (Join-Path $release 'RazorClient.exe')
    if ($LASTEXITCODE) { throw 'Launcher Authenticode verification failed.' }
    & $signtoolPath verify /pa /quiet $bootstrap
    if ($LASTEXITCODE) { throw 'Bootstrap Authenticode verification failed.' }
    & $jarsignerPath -verify -strict $agent
    if ($LASTEXITCODE) { throw 'Agent JAR signature verification failed.' }
}

$process = Start-Process -FilePath $exe -ArgumentList '--self-check' -Wait -PassThru -WindowStyle Hidden
if ($process.ExitCode -ne 0) { throw "One-file self-check failed with exit code $($process.ExitCode)." }
$cryptoProcess = Start-Process -FilePath $exe -ArgumentList '--crypto-self-check' -Wait -PassThru -WindowStyle Hidden
if ($cryptoProcess.ExitCode -ne 0) { throw "RSA-PSS known-good/tamper self-check failed with exit code $($cryptoProcess.ExitCode)." }
Write-Output "PASS security verification ($Mode)"
