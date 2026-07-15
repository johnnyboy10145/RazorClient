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
    'No Jump Delay',
    'Hit Select',
    'Sprint Reset',
    'Auto Weapon',
    'Teams',
    'No Hit Delay',
    'Criticals',
    'Fast Place',
    'Auto Tool',
    'Item Physics',
    'Fullbright',
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
foreach ($packetCoreMarker in @('OwnedQueuedPacket', 'ownerGeneration', 'markOwnerReady', 'MAX_OUTBOUND_RELEASES_PER_TICK', 'MAX_INBOUND_RELEASE_NANOS', 'OUTBOUND_OVERFLOW_HIGH_WATER', 'INBOUND_OVERFLOW_HIGH_WATER', 'ChannelPromise', 'onInboundPacketProcessed')) {
    if ($packetDelayManager -notmatch $packetCoreMarker) { throw "Packet-delay core marker is missing: $packetCoreMarker" }
}
if ($packetDelayManager -notmatch 'closeForUnload' -or $packetDelayManager -notmatch 'closed = true' -or $packetDelayManager -notmatch 'instance == this') { throw 'Packet transport unload detachment is missing.' }
if ($packetDelayManager -notmatch 'getArbitrationStatus') { throw 'Packet-delay ownership diagnostics are missing.' }
$liveEntrypoint = Get-Content (Join-Path $root 'payload\src\com\razorclient\inject\LiveEntrypoint.java') -Raw
if ($liveEntrypoint -notmatch 'Keep lifecycle and packet-flush hooks active' -or $manager -notmatch 'pollLifecycleState') { throw 'World, focus, and GUI lifecycle pulse is missing.' }
$targetServicePath = Join-Path $project 'src\main\java\com\razorclient\combat\CombatTargetService.java'
if (!(Test-Path $targetServicePath)) { throw 'Combat target service is missing.' }
$targetService = Get-Content $targetServicePath -Raw
$aimAssist = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\AimAssistModule.java') -Raw
$rotationHelper = Get-Content (Join-Path $project 'src\main\java\com\razorclient\combat\ClientRotationHelper.java') -Raw
$autoClicker = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\AutoClickerModule.java') -Raw
if ($targetService -notmatch 'isValid' -or $aimAssist -notmatch 'CombatTargetService' -or $aimAssist -notmatch 'SILENT') { throw 'Combat target or silent-aim integration is missing.' }
if ($rotationHelper -notmatch 'requestRotations' -or $autoClicker -notmatch 'Click Pattern' -or $autoClicker -notmatch 'System.nanoTime') { throw 'Combat rotation or monotonic click scheduling is missing.' }
$clickGuiScreen = Get-Content (Join-Path $project 'src\main\java\com\razorclient\gui\ClickGuiScreen.java') -Raw
if ($clickGuiScreen -notmatch 'GuiTextField' -or $clickGuiScreen -notmatch 'updatePanelFilters' -or $clickGuiScreen -notmatch 'drawProfileDropdown') { throw 'Vape-style ClickGUI search/profile controls are missing.' }
if ($clickGuiScreen -notmatch 'scrollbarAnimation' -or $clickGuiScreen -notmatch 'settingToggleAnimations') { throw 'ClickGUI interaction animations are missing.' }
$moduleBase = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\Module.java') -Raw
$configManagerSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\config\ConfigManager.java') -Raw
$payloadClientSource = Get-Content (Join-Path $root 'payload\src\com\razorclient\RazorClient.java') -Raw
if ($moduleBase -notmatch 'onSessionReset' -or $moduleBase -notmatch 'onInputContextLost' -or $moduleBase -notmatch 'ModuleScope' -or $manager -notmatch 'updateLifecycleState') { throw 'Shared module lifecycle callbacks or isolated module scopes are missing.' }
$runtimeCoreSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\RuntimeCore.java') -Raw
$snapshotServiceSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\EntitySnapshotService.java') -Raw
$resourceArbiterSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\ResourceArbiter.java') -Raw
if ($runtimeCoreSource -notmatch 'beginRealTick' -or $runtimeCoreSource -notmatch 'beginFrame' -or $snapshotServiceSource -notmatch 'SnapshotFrame') { throw 'Tick/frame context or shared entity snapshots are missing.' }
$ownedJavaSource = (Get-ChildItem (Join-Path $project 'src\main\java\com\razorclient') -Recurse -Filter '*.java' |
    ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
if (([regex]::Matches($ownedJavaSource, 'loadedEntityList')).Count -ne 1) { throw 'Loaded entities must be traversed only by EntitySnapshotService.' }
if ($snapshotServiceSource -notmatch 'ProjectileSnapshot' -or $snapshotServiceSource -notmatch 'getDroppedItemCount') { throw 'Shared projectile or dropped-item snapshot lanes are missing.' }
$frameContextSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\FrameContext.java') -Raw
if ($frameContextSource -notmatch 'ProjectionBacking\[\]' -or $frameContextSource -notmatch 'isProjectionValid') { throw 'Reusable validated frame projection capture is missing.' }
foreach ($resource in @('SERVER_ROTATION', 'MODEL_ROTATION', 'ATTACK_ACTION', 'USE_ACTION', 'SNEAK_INPUT', 'SPRINT_INPUT', 'HOTBAR_SLOT', 'CLIENT_TIMER')) {
    if ($resourceArbiterSource -notmatch $resource) { throw "Resource lease type is missing: $resource" }
}
foreach ($utility in @('FastPlaceModule', 'AutoToolModule', 'ItemPhysicsModule', 'FullbrightModule')) {
    if ($manager -notmatch $utility) { throw "Utility module is not registered: $utility" }
}
if ($configManagerSource -notmatch '"Fullbright", true' -or $configManagerSource -notmatch '"Auto Tool", true' -or $configManagerSource -notmatch 'ensureUtilityProfileEntries') { throw 'Built-in utility profile defaults or non-destructive migration are missing.' }
if ($configManagerSource -notmatch 'bedwars-legit' -or $configManagerSource -notmatch 'bedwars-aggressive' -or $configManagerSource -notmatch 'createBedwarsProfile') { throw 'Revamped BedWars profile library is missing.' }
if ($configManagerSource -notmatch 'bedwars-aggressive-v2' -or $configManagerSource -notmatch 'createBedwarsAggressiveV2') { throw 'Coordinated BedWars aggressive v2 profile is missing.' }
if ($configManagerSource -notmatch 'SAVE_DEBOUNCE_NANOS' -or $configManagerSource -notmatch 'flushPendingSaveNow' -or $configManagerSource -notmatch 'ATOMIC_MOVE') { throw 'Debounced atomic config persistence is missing.' }
if ($configManagerSource -notmatch 'stageConfig' -or $configManagerSource -notmatch 'captureRuntimeState' -or $configManagerSource -notmatch 'restoreRuntimeState') { throw 'Transactional config validation or rollback is missing.' }
if ($payloadClientSource -notmatch 'saveCurrent\(\);\s*client\.packetDelayManager\.closeForUnload\(\);\s*client\.moduleManager\.shutdownForUnload') { throw 'Unload must persist the active profile before transport close and module teardown.' }
foreach ($addition in @('HitSelectModule', 'SprintResetModule', 'AutoWeaponModule', 'TeamsModule', 'NoHitDelayModule', 'CriticalsModule')) {
    if ($manager -notmatch $addition) { throw "Combat addition is not registered: $addition" }
}
$clickGuiModuleSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\ClickGuiModule.java') -Raw
$guiThemeSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\gui\GuiTheme.java') -Raw
if ($clickGuiModuleSource -notmatch 'getThemeBackgroundColor' -or $clickGuiModuleSource -notmatch '"Amethyst"' -or $guiThemeSource -notmatch 'getThemeSurfaceColor') { throw 'Full GUI theme palettes are missing.' }
$launcherSource = Get-Content (Join-Path $root 'native\launcher\main.cpp') -Raw
if ($launcherSource -notmatch 'genericLunar189Compatible' -or $launcherSource -notmatch 'knownMappingsAdapter' -or $launcherSource -notmatch 'ignoredOlderBakeCandidates') { throw 'Generic Lunar 1.8.9 structural compatibility validation is missing.' }
if ($launcherSource -notmatch 'wait != WAIT_TIMEOUT') { throw 'Remote LoadLibrary timeout memory guard is missing.' }
$actionCoordinator = Get-Content (Join-Path $project 'src\main\java\com\razorclient\combat\CombatActionCoordinator.java') -Raw
$mouseHelper = Get-Content (Join-Path $project 'src\main\java\com\razorclient\util\MouseButtonHelper.java') -Raw
if ($moduleBase -notmatch 'ModuleResetReason' -or $manager -notmatch 'GUI_OPENED' -or $manager -notmatch 'FOCUS_LOSS') { throw 'Reasoned module lifecycle resets are missing.' }
if ($actionCoordinator -notmatch 'tryAcquire' -or $targetService -notmatch 'candidates' -or $mouseHelper -notmatch 'releaseAllSynthetic') { throw 'Combat action, target snapshot, or synthetic-input cleanup is missing.' }
if ($packetDelayManager -notmatch 'getQueueStatus' -or $packetDelayManager -notmatch 'lastFlushReason') { throw 'Packet queue ownership diagnostics are missing.' }
if ($packetDelayManager -notmatch 'outboundFastTrack' -or $packetDelayManager -notmatch 'isReleasingOutbound' -or $packetDelayManager -notmatch 'compactMovementRuns') { throw 'Packet bypass isolation or overflow-only movement compaction is missing.' }
$blinkSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\BlinkModule.java') -Raw
if ($blinkSource -notmatch 'shouldHoldOutboundPacket' -or $blinkSource -notmatch 'shouldHoldInboundPacket' -or $blinkSource -match 'HOLD_DELAY_MS') { throw 'Blink indefinite direction hold behavior is missing.' }
if ($manager -notmatch 'isIndefinite' -or $packetDelayManager -notmatch 'Long\.MAX_VALUE' -or $packetDelayManager -notmatch 'onPacketDelayOverflow') { throw 'Indefinite packet hold or overflow recovery is missing.' }
foreach ($blinkKey in @('Direction', 'Maximum Duration', 'Allow Keep Alives', 'Disable On Attack', 'Disable On Block Interact', 'Disable On Block Dig')) {
    if ($blinkSource -notmatch [regex]::Escape($blinkKey)) { throw "Blink setting is missing: $blinkKey" }
}
$clutchSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\ClutchModule.java') -Raw
if ($clutchSource -notmatch 'RenderPlayerEvent\$Pre' -or $clutchSource -notmatch 'updateSilentModelRotation' -or $clutchSource -notmatch 'requestRotations\("Clutch", 95') { throw 'Clutch silent model rotations are missing.' }
if ($clutchSource -notmatch 'restoreModelRenderSwap' -or $clutchSource -notmatch 'rotationYawHead' -or $clutchSource -notmatch 'renderYawOffset') { throw 'Clutch silent model rotation cleanup is incomplete.' }
if ($clutchSource -match 'static final ClutchModule INSTANCE' -or $manager -notmatch 'new ClutchModule\(\)') { throw 'Clutch must be recreated per client lifecycle.' }
$espSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\PlayerEspModule.java') -Raw
$bootstrapSource = Get-Content (Join-Path $root 'native\bootstrap\bootstrap.cpp') -Raw
foreach ($espKey in @('Projection Mode', 'Target Type', 'Through Walls', 'Health Bar', 'Held Item', 'Target Highlight')) {
    if ($espSource -notmatch [regex]::Escape($espKey)) { throw "PlayerESP setting is missing: $espKey" }
}
if ($espSource -notmatch 'SnapshotFrame' -or $espSource -notmatch 'GLU\.gluProject' -or $targetService -notmatch 'publishTarget') { throw 'PlayerESP shared snapshot, projection, or target publication integration is missing.' }
if ($liveEntrypoint -notmatch 'installNativeRenderBridge' -or $liveEntrypoint -notmatch 'renderNativeFrame' -or $liveEntrypoint -match 'renderLiveBridge\(\)') { throw 'Render-phase live bridge integration is missing or tick rendering remains active.' }
if ($liveEntrypoint -notmatch 'pendingManager' -or $liveEntrypoint -notmatch '!running \|\| !isCurrentEpoch' -or $liveEntrypoint -notmatch 'ChannelInboundHandlerAdapter implements ChannelOutboundHandler' -or $liveEntrypoint -notmatch 'addBefore\(anchor, PACKET_HANDLER_NAME' -or $liveEntrypoint -notmatch 'razorclient_live_transport') { throw 'Single Netty transport handler installation or lifecycle guard is missing.' }
if ($bootstrapSource -notmatch 'hookedSwapBuffers' -or $bootstrapSource -notmatch 'uninstallRenderBridge' -or $bootstrapSource -notmatch 'renderInstalled' -or $bootstrapSource -notmatch 'renderThreadId' -or $bootstrapSource -notmatch 'wglGetCurrentContext' -or $bootstrapSource -notmatch 'renderWindow') { throw 'Native render bridge install/uninstall or render-surface guards are missing.' }
$buildSource = Get-Content (Join-Path $root 'build.ps1') -Raw
if ($buildSource -notmatch '-lopengl32') { throw 'Native render bridge OpenGL linkage is missing.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($agent)
try {
    $entries = @($zip.Entries | ForEach-Object FullName)
    foreach ($entry in @(
        'com/razorclient/inject/LiveEntrypoint.class',
        'com/razorclient/inject/InjectionStatus.class',
        'com/razorclient/inject/ChildFirstPayloadLoader.class',
        'com/razorclient/feature/module/impl/SelfDestructModule.class',
        'com/razorclient/feature/module/impl/FastPlaceModule.class',
        'com/razorclient/feature/module/impl/AutoToolModule.class',
        'com/razorclient/feature/module/impl/ItemPhysicsModule.class',
        'com/razorclient/feature/module/impl/FullbrightModule.class',
        'com/razorclient/feature/module/impl/HitSelectModule.class',
        'com/razorclient/feature/module/impl/SprintResetModule.class',
        'com/razorclient/feature/module/impl/AutoWeaponModule.class',
        'com/razorclient/feature/module/impl/TeamsModule.class',
        'com/razorclient/feature/module/impl/NoHitDelayModule.class',
        'com/razorclient/feature/module/impl/CriticalsModule.class',
        'com/razorclient/runtime/TickContext.class',
        'com/razorclient/runtime/FrameContext.class',
        'com/razorclient/runtime/EntitySnapshotService.class',
        'com/razorclient/runtime/ModuleScope.class',
        'com/razorclient/runtime/ResourceArbiter.class'
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
