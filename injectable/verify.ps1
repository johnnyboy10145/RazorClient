$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$api = Join-Path $project 'build\lunar\lunar-runtime-api.jar'
$agent = Join-Path $root 'dist\razorclient-agent.jar'
$release = Join-Path $project 'release'
$classes = Join-Path $root 'build\test-classes'
$javac = 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'

if (!(Test-Path $agent) -or !(Test-Path (Join-Path $root 'dist\RazorClient.exe'))) { throw 'Build artifacts are missing.' }
if (!(Test-Path (Join-Path $release 'RazorClient.exe'))) { throw 'One-file release artifact is missing.' }
if (Test-Path (Join-Path $release 'razorclient-agent.jar')) { throw 'Release folder must not contain adjacent agent JAR.' }
if (Test-Path (Join-Path $release 'razorclient-bootstrap.dll')) { throw 'Release folder must not contain adjacent bootstrap DLL.' }
$releaseFiles = @(Get-ChildItem $release -File)
if ($releaseFiles.Count -ne 1 -or $releaseFiles[0].Name -ne 'RazorClient.exe') { throw 'Release folder must contain exactly one file: RazorClient.exe' }

$expected = @(
    'AimAssist',
    'AntiBot',
    'AntiFireball',
    'LeftClicker',
    'BedPlates',
    'ClickGUI',
    'ClickRecorder',
    'Clutch',
    'Config',
    'HUD',
    'KillAura',
    'Knockback Delay',
    'LegitScaffold',
    'PlayerESP',
    'Reach',
    'RightClicker',
    'Sprint',
    'Trajectories',
    'Velocity',
    'Fake Lag',
    'Blink',
    'Backtrack',
    'Lag Range',
    'Ping Fix',
    'Self Destruct'
)
$manager = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\ModuleManager.java') -Raw
foreach ($name in $expected) {
    $needle = if ($name -eq 'LeftClicker') { 'AutoClickerModule' } elseif ($name -eq 'Knockback Delay') { 'KnockbackDelayModule' } else { $name.Replace(' ','') + 'Module' }
    if ($manager -notmatch [regex]::Escape($needle)) { throw "Missing module registration: $name" }
}
$packetDelayManager = Get-Content (Join-Path $project 'src\main\java\com\razorclient\network\PacketDelayManager.java') -Raw
if ($manager -notmatch 'selectOutboundPacketDelay' -or $manager -notmatch 'selectInboundPacketDelay') { throw 'Packet-delay arbitration is missing.' }
if ($packetDelayManager -notmatch 'inboundOverflowFlushRequested' -or $packetDelayManager -notmatch 'getArbitrationStatus') { throw 'Packet-delay lifecycle guards are missing.' }
$liveEntrypoint = Get-Content (Join-Path $root 'payload\src\com\razorclient\inject\LiveEntrypoint.java') -Raw
if ($liveEntrypoint -notmatch 'Keep lifecycle and packet-flush hooks active') { throw 'World-transition lifecycle pulse is missing.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($agent)
try {
    $entries = @($zip.Entries | ForEach-Object FullName)
    foreach ($entry in @(
        'com/razorclient/inject/LiveEntrypoint.class',
        'com/razorclient/inject/InjectionStatus.class',
        'com/razorclient/inject/ChildFirstPayloadLoader.class',
        'com/razorclient/feature/module/impl/SelfDestructModule.class'
    )) {
        if ($entries -notcontains $entry) { throw "Missing payload class: $entry" }
    }
    foreach ($legacy in @(
        'com/razorclient/inject/LunarTransformer.class',
        'com/razorclient/inject/NativeEntrypoint.class',
        'com/razorclient/inject/PinnedEntrypoint.class',
        'com/razorclient/feature/module/impl/DeobfuscationModule.class',
        'com/razorclient/feature/module/impl/DeobfuscationRegistry.class',
        'com/razorclient/gui/ModernClickGuiScreen.class'
    )) {
        if ($entries -contains $legacy) { throw "Legacy transformer class still packaged: $legacy" }
    }
} finally {
    $zip.Dispose()
}

$distHashes = Get-FileHash -Algorithm SHA256 (Join-Path $root 'dist\RazorClient.exe'), $agent, (Join-Path $root 'dist\razorclient-bootstrap.dll')
$distHashes

$oneFileCheck = Join-Path $project 'build\one-file-release-check'
if (Test-Path $oneFileCheck) { Remove-Item $oneFileCheck -Recurse -Force }
New-Item -ItemType Directory -Path $oneFileCheck | Out-Null
Copy-Item (Join-Path $release 'RazorClient.exe') (Join-Path $oneFileCheck 'RazorClient.exe')
$tempFiles = @(Get-ChildItem $oneFileCheck -File)
if ($tempFiles.Count -ne 1 -or $tempFiles[0].Name -ne 'RazorClient.exe') { throw 'One-file temp check must contain only RazorClient.exe' }

$selfCheck = Start-Process -FilePath (Join-Path $oneFileCheck 'RazorClient.exe') -ArgumentList '--self-check' -WorkingDirectory $oneFileCheck -Wait -PassThru -WindowStyle Hidden
if ($selfCheck.ExitCode -ne 0) { throw "One-file release self-check failed with exit code $($selfCheck.ExitCode)." }

$diagnose = Start-Process -FilePath (Join-Path $oneFileCheck 'RazorClient.exe') -ArgumentList '--diagnose' -WorkingDirectory $oneFileCheck -Wait -PassThru -WindowStyle Hidden
if ($diagnose.ExitCode -ne 0) { throw "One-file release diagnostics failed with exit code $($diagnose.ExitCode)." }
$diagnosticsPath = Join-Path $env:LOCALAPPDATA 'RazorClient\diagnostics.txt'
if (!(Test-Path $diagnosticsPath)) { throw 'Diagnostics output was not written.' }
$diagnostics = Get-Content $diagnosticsPath -Raw
$bootstrapHash = (Get-FileHash -Algorithm SHA256 (Join-Path $root 'dist\razorclient-bootstrap.dll')).Hash
$agentHash = (Get-FileHash -Algorithm SHA256 $agent).Hash
if ($diagnostics -notmatch [regex]::Escape("Payload resources: embedded OK")) { throw 'Diagnostics did not confirm embedded payload.' }
if ($diagnostics -notmatch [regex]::Escape("Bootstrap SHA-256: $bootstrapHash")) { throw 'Embedded bootstrap hash does not match dist payload.' }
if ($diagnostics -notmatch [regex]::Escape("Agent SHA-256: $agentHash")) { throw 'Embedded agent hash does not match dist payload.' }

Remove-Item $oneFileCheck -Recurse -Force
Get-FileHash -Algorithm SHA256 (Join-Path $release 'RazorClient.exe')
Write-Host 'PASS live module parity, live payload structure, JAR, DLL, and launcher artifacts'
