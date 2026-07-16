param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Authenticode', 'Jar')]
    [string]$ArtifactType,

    [string]$Path,

    [switch]$Preflight,

    [string]$SignerMetadataPath
)

$ErrorActionPreference = 'Stop'
if (!$Preflight -and !(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Signing target does not exist: $Path" }

$temp = Join-Path ([IO.Path]::GetTempPath()) ('RazorClient-sign-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null

function Write-SecretFile([string]$name, [string]$value) {
    $bytes = [Convert]::FromBase64String(($value -replace '\s', ''))
    $path = Join-Path $temp $name
    [IO.File]::WriteAllBytes($path, $bytes)
    return $path
}

function Find-SigningTool([string]$name, [string[]]$fallbacks) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    foreach ($candidate in $fallbacks) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

try {
    if ($ArtifactType -eq 'Authenticode') {
        $sdkTools = @(Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | ForEach-Object FullName)
        $signtool = Find-SigningTool 'signtool.exe' $sdkTools
        $pfx = [Environment]::GetEnvironmentVariable('RAZORCLIENT_AUTHENTICODE_PFX_B64')
        $password = [Environment]::GetEnvironmentVariable('RAZORCLIENT_AUTHENTICODE_PASSWORD')
        if (!$signtool) { throw 'signtool.exe is required for Authenticode signing.' }
        if ([string]::IsNullOrWhiteSpace($pfx) -or [string]::IsNullOrWhiteSpace($password)) {
            throw 'Authenticode signing secrets are missing.'
        }
        try { [void][Convert]::FromBase64String(($pfx -replace '\s', '')) } catch { throw 'Authenticode PFX secret is not valid Base64.' }
        if ($Preflight) { return }
        $pfxPath = Write-SecretFile 'release.pfx' $pfx
        $timestamp = [Environment]::GetEnvironmentVariable('RAZORCLIENT_TIMESTAMP_URL')
        if ([string]::IsNullOrWhiteSpace($timestamp)) { $timestamp = 'https://timestamp.digicert.com' }
        & $signtool sign /fd SHA256 /f $pfxPath /p $password /tr $timestamp /td SHA256 $Path
        if ($LASTEXITCODE) { throw "Authenticode signing failed for $Path" }
    } else {
        $jarsigner = Find-SigningTool 'jarsigner.exe' @("${env:ProgramFiles}\Java\jdk-21.0.10\bin\jarsigner.exe")
        $keytool = Find-SigningTool 'keytool.exe' @("${env:ProgramFiles}\Java\jdk-21.0.10\bin\keytool.exe")
        $keystore = [Environment]::GetEnvironmentVariable('RAZORCLIENT_JAR_KEYSTORE_B64')
        $password = [Environment]::GetEnvironmentVariable('RAZORCLIENT_JAR_KEYSTORE_PASSWORD')
        $alias = [Environment]::GetEnvironmentVariable('RAZORCLIENT_JAR_ALIAS')
        $storeType = [Environment]::GetEnvironmentVariable('RAZORCLIENT_JAR_KEYSTORE_TYPE')
        if (!$jarsigner) { throw 'jarsigner.exe is required for JAR signing.' }
        if (!$keytool) { throw 'keytool.exe is required for JAR signer pinning.' }
        if ([string]::IsNullOrWhiteSpace($keystore) -or [string]::IsNullOrWhiteSpace($password)) {
            throw 'JAR signing secrets are missing.'
        }
        try { [void][Convert]::FromBase64String(($keystore -replace '\s', '')) } catch { throw 'JAR keystore secret is not valid Base64.' }
        if ([string]::IsNullOrWhiteSpace($alias)) { $alias = 'razorclient-release' }
        if ([string]::IsNullOrWhiteSpace($storeType)) { $storeType = 'PKCS12' }
        if ($Preflight) { return }
        $keystorePath = Write-SecretFile 'release-keystore.p12' $keystore
        & $jarsigner -keystore $keystorePath -storetype $storeType -storepass $password `
            -sigalg SHA256withRSA -digestalg SHA-256 $Path $alias
        if ($LASTEXITCODE) { throw "JAR signing failed for $Path" }
        & $jarsigner -verify -strict $Path
        if ($LASTEXITCODE) { throw "JAR signature verification failed for $Path" }
        if (![string]::IsNullOrWhiteSpace($SignerMetadataPath)) {
            $certificatePath = Join-Path $temp 'release-signer.cer'
            & $keytool -exportcert -keystore $keystorePath -storetype $storeType -storepass $password `
                -alias $alias -file $certificatePath
            if ($LASTEXITCODE -or !(Test-Path -LiteralPath $certificatePath -PathType Leaf)) {
                throw 'Unable to export the JAR signing certificate.'
            }
            $fingerprint = (Get-FileHash -Algorithm SHA256 -LiteralPath $certificatePath).Hash.ToUpperInvariant()
            $metadataDirectory = Split-Path -Parent $SignerMetadataPath
            if ($metadataDirectory) { New-Item -ItemType Directory -Path $metadataDirectory -Force | Out-Null }
            Set-Content -LiteralPath $SignerMetadataPath -Value $fingerprint -Encoding ASCII
        }
    }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
