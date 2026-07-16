$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$api = Join-Path $project 'build\lunar\lunar-runtime-api.jar'
$agent = Join-Path $root 'dist\razorclient-agent.jar'
$release = Join-Path $project 'release'
$classes = Join-Path $root 'build\test-classes'
$javac = 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
$runtimeTests = Join-Path $root 'runtime-tests.ps1'
$legacyForge = Join-Path $project 'legacy\forge-1.8.9'

if (!(Test-Path $agent) -or !(Test-Path (Join-Path $root 'dist\RazorClient.exe'))) { throw 'Build artifacts are missing.' }
if (!(Test-Path (Join-Path $release 'RazorClient.exe'))) { throw 'One-file release artifact is missing.' }
if (Test-Path (Join-Path $release 'razorclient-agent.jar')) { throw 'Release folder must not contain adjacent agent JAR.' }
if (Test-Path (Join-Path $release 'razorclient-bootstrap.dll')) { throw 'Release folder must not contain adjacent bootstrap DLL.' }
$releaseFiles = @(Get-ChildItem $release -File)
if ($releaseFiles.Count -ne 1 -or $releaseFiles[0].Name -ne 'RazorClient.exe') { throw 'Release folder must contain exactly one file: RazorClient.exe' }
foreach ($legacyPath in @('build.gradle', 'src\main\java\com\razorclient\mixin', 'src\main\resources\mixins.razorclient.json')) {
    if (!(Test-Path (Join-Path $legacyForge $legacyPath))) { throw "Archived Forge/Mixin baseline is incomplete: $legacyPath" }
}
if (Test-Path (Join-Path $project 'build.gradle')) { throw 'Forge/Mixin build must remain outside the active runtime root.' }

$contractPath = Join-Path $root 'fixtures\user-contract.json'
if (!(Test-Path $contractPath)) { throw 'User compatibility contract fixture is missing.' }
$contract = Get-Content $contractPath -Raw | ConvertFrom-Json
if ($contract.schemaVersion -ne 1) { throw 'Unsupported user compatibility contract schema.' }
$expected = @($contract.modules)
$legacyExpected = @(
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
if (@(Compare-Object $legacyExpected $expected).Count -ne 0) { throw 'Module contract fixture and verifier inventory disagree.' }
$manager = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\ModuleManager.java') -Raw
foreach ($name in $expected) {
    $needle = if ($name -eq 'LeftClicker') { 'AutoClickerModule' } elseif ($name -eq 'Knockback Delay') { 'KnockbackDelayModule' } else { $name.Replace(' ','') + 'Module' }
    if ($manager -notmatch [regex]::Escape($needle)) { throw "Missing module registration: $name" }
}
$packetDelayManager = Get-Content (Join-Path $project 'src\main\java\com\razorclient\network\PacketDelayManager.java') -Raw
if ($manager -notmatch 'captureOutboundPacketDecision' -or $manager -notmatch 'captureInboundPacketDecision') { throw 'Atomic packet-decision capture is missing.' }
$packetDecisionSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\network\PacketDecision.java') -Raw
$packetLaneSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\network\PacketLane.java') -Raw
foreach ($decision in @('PASS', 'MUTATE', 'CANCEL', 'HOLD', 'FLUSH_THEN_PASS', 'OwnerToken', 'PacketReleasePolicy', 'cancelOnRelease')) {
    if ($packetDecisionSource -notmatch $decision) { throw "Packet-decision contract marker is missing: $decision" }
}
foreach ($laneMarker in @('OwnerToken', 'Direction', 'dependencyDomain', 'equals', 'hashCode')) {
    if ($packetLaneSource -notmatch $laneMarker) { throw "Packet-lane isolation marker is missing: $laneMarker" }
}
foreach ($packetCoreMarker in @('OwnedQueuedPacket', 'OwnerToken ownerToken', 'markOwnerReady', 'MAX_OUTBOUND_RELEASES_PER_TICK', 'MAX_INBOUND_RELEASE_NANOS', 'OUTBOUND_OVERFLOW_HIGH_WATER', 'INBOUND_OVERFLOW_HIGH_WATER', 'ChannelPromise', 'onInboundPacketProcessed', 'blockedOutboundDomains', 'blockedInboundDomains', 'overflowPassThrough', 'shouldCancelOnRelease')) {
    if ($packetDelayManager -notmatch $packetCoreMarker) { throw "Packet-delay core marker is missing: $packetCoreMarker" }
}
if ($packetDelayManager -notmatch 'closeForUnload' -or $packetDelayManager -notmatch 'closed = true' -or $packetDelayManager -notmatch 'instance == this') { throw 'Packet transport unload detachment is missing.' }
if ($packetDelayManager -notmatch 'getArbitrationStatus') { throw 'Packet-delay ownership diagnostics are missing.' }
$liveEntrypoint = Get-Content (Join-Path $root 'payload\src\com\razorclient\inject\LiveEntrypoint.java') -Raw
if ($liveEntrypoint -notmatch 'Keep lifecycle and packet-flush hooks active' -or $manager -notmatch 'pollLifecycleState') { throw 'World, focus, and GUI lifecycle pulse is missing.' }
$targetServicePath = Join-Path $project 'src\main\java\com\razorclient\combat\CombatTargetService.java'
if (!(Test-Path $targetServicePath)) { throw 'Combat target service is missing.' }
$targetService = Get-Content $targetServicePath -Raw
$targetPublicationPath = Join-Path $project 'src\main\java\com\razorclient\combat\TargetPublicationService.java'
if (!(Test-Path $targetPublicationPath)) { throw 'Owner-scoped target publication service is missing.' }
$targetPublicationSource = Get-Content $targetPublicationPath -Raw
$aimAssist = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\AimAssistModule.java') -Raw
$killAura = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\KillAuraModule.java') -Raw
$rotationHelper = Get-Content (Join-Path $project 'src\main\java\com\razorclient\combat\ClientRotationHelper.java') -Raw
$autoClicker = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\AutoClickerModule.java') -Raw
if ($targetService -notmatch 'isValid' -or $aimAssist -notmatch 'CombatTargetService' -or $aimAssist -notmatch 'SILENT') { throw 'Combat target or silent-aim integration is missing.' }
if ($targetPublicationSource -notmatch 'Map<OwnerToken, Publication>' -or $targetPublicationSource -notmatch 'sessionGeneration' -or
    $aimAssist -notmatch 'getTargetPublications\(\)\.publish' -or $killAura -notmatch 'getTargetPublications\(\)\.publish') {
    throw 'Owner-scoped, generation-safe target publication is missing.'
}
if ($rotationHelper -notmatch 'requestRotations' -or $autoClicker -notmatch 'Click Pattern' -or $autoClicker -notmatch 'System.nanoTime') { throw 'Combat rotation or monotonic click scheduling is missing.' }
$clickGuiScreen = Get-Content (Join-Path $project 'src\main\java\com\razorclient\gui\ClickGuiScreen.java') -Raw
if ($clickGuiScreen -notmatch 'GuiTextField' -or $clickGuiScreen -notmatch 'updatePanelFilters' -or $clickGuiScreen -notmatch 'drawProfileDropdown') { throw 'Vape-style ClickGUI search/profile controls are missing.' }
$guiDirectory = Join-Path $project 'src\main\java\com\razorclient\gui'
foreach ($guiHelper in @('ClickGuiLayoutModel.java', 'ClickGuiCategoryPanel.java', 'ClickGuiProfileAdapter.java', 'ClickGuiRenderUtil.java', 'ClickGuiSliderControl.java')) {
    if (!(Test-Path (Join-Path $guiDirectory $guiHelper))) { throw "Split ClickGUI helper is missing: $guiHelper" }
}
$guiPanelSource = Get-Content (Join-Path $guiDirectory 'ClickGuiCategoryPanel.java') -Raw
if ($clickGuiScreen -notmatch 'ClickGuiLayoutModel' -or $clickGuiScreen -notmatch 'ClickGuiProfileAdapter' -or
    $guiPanelSource -notmatch 'scrollbarAnimation' -or $guiPanelSource -notmatch 'settingToggleAnimations') {
    throw 'Retained ClickGUI layout, profile adapter, or interaction animations are missing.'
}
$moduleBase = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\Module.java') -Raw
$configManagerSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\config\ConfigManager.java') -Raw
$profileNames = @($contract.builtInProfiles)
foreach ($profileName in $profileNames) {
    if ($configManagerSource -notmatch [regex]::Escape('"' + $profileName + '"')) { throw "Built-in profile contract is missing: $profileName" }
}
$payloadClientSource = Get-Content (Join-Path $root 'payload\src\com\razorclient\RazorClient.java') -Raw
if ($moduleBase -notmatch 'onSessionReset' -or $moduleBase -notmatch 'onInputContextLost' -or $moduleBase -notmatch 'ModuleScope' -or $manager -notmatch 'updateLifecycleState') { throw 'Shared module lifecycle callbacks or isolated module scopes are missing.' }
$runtimeCoreSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\RuntimeCore.java') -Raw
$snapshotServiceSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\EntitySnapshotService.java') -Raw
$resourceArbiterSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\ResourceArbiter.java') -Raw
$clientSessionSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\ClientSession.java') -Raw
$schedulerSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\ClientThreadScheduler.java') -Raw
$entityFrameSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\EntityFrame.java') -Raw
$entityRecordSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\EntityRecord.java') -Raw
$ownerTokenSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\OwnerToken.java') -Raw
if ($runtimeCoreSource -notmatch 'beginRealTick' -or $runtimeCoreSource -notmatch 'beginFrame' -or
    $runtimeCoreSource -notmatch 'ClientSession' -or $clientSessionSource -notmatch 'ClientThreadScheduler') {
    throw 'Instance-owned session, scheduler, or tick/frame context is missing.'
}
if ($clientSessionSource -notmatch 'sessionGeneration' -or $clientSessionSource -notmatch 'shutdown' -or
    $schedulerSource -notmatch 'discardOwner' -or $schedulerSource -notmatch 'advanceSession') {
    throw 'Session-generation lifecycle or owner-scoped scheduler cleanup is missing.'
}
if ($entityFrameSource -notmatch 'sessionGeneration' -or $entityFrameSource -notmatch 'unmodifiable' -or
    $entityRecordSource -match 'import\s+net\.minecraft|private\s+final\s+Entity\w*\s+' -or
    $entityFrameSource -match 'import\s+net\.minecraft') {
    throw 'EntityFrame must publish generation-owned immutable scalar records without live entities.'
}
if ($ownerTokenSource -notmatch 'activationGeneration' -or $ownerTokenSource -notmatch 'scopeId' -or
    $resourceArbiterSource -notmatch 'OwnerToken') { throw 'Typed generation-scoped resource ownership is missing.' }
foreach ($capability in @('TickListener.java', 'RenderListener.java', 'InputListener.java', 'PacketPolicy.java')) {
    if (!(Test-Path (Join-Path $project "src\main\java\com\razorclient\runtime\capability\$capability"))) {
        throw "Runtime capability interface is missing: $capability"
    }
}
$ownedJavaSource = (Get-ChildItem (Join-Path $project 'src\main\java\com\razorclient') -Recurse -Filter '*.java' |
    ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
foreach ($moduleProperty in $contract.protectedSettings.PSObject.Properties) {
    foreach ($settingName in @($moduleProperty.Value)) {
        if ($ownedJavaSource -notmatch [regex]::Escape('"' + $settingName + '"')) {
            throw "Protected setting contract is missing: $($moduleProperty.Name).$settingName"
        }
    }
}
if (([regex]::Matches($ownedJavaSource, 'loadedEntityList')).Count -ne 1) { throw 'Loaded entities must be traversed only by EntitySnapshotService.' }
if ($snapshotServiceSource -notmatch 'EntityRecord\.Kind\.FIREBALL' -or
    $snapshotServiceSource -notmatch 'EntityRecord\.Kind\.DROPPED_ITEM') {
    throw 'Shared immutable projectile or dropped-item records are missing.'
}
$frameContextSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\runtime\FrameContext.java') -Raw
if ($frameContextSource -notmatch 'ProjectionBacking' -or $frameContextSource -notmatch 'isProjectionValid' -or $frameContextSource -notmatch 'sessionGeneration') { throw 'Immutable validated frame projection capture is missing.' }
foreach ($resource in @('SERVER_ROTATION', 'MODEL_ROTATION', 'ATTACK_ACTION', 'USE_ACTION', 'SNEAK_INPUT', 'SPRINT_INPUT', 'HOTBAR_SLOT', 'CLIENT_TIMER')) {
    if ($resourceArbiterSource -notmatch $resource) { throw "Resource lease type is missing: $resource" }
}
foreach ($utility in @('FastPlaceModule', 'AutoToolModule', 'ItemPhysicsModule', 'FullbrightModule')) {
    if ($manager -notmatch $utility) { throw "Utility module is not registered: $utility" }
}
if ($configManagerSource -notmatch '"Fullbright", true' -or $configManagerSource -notmatch '"Auto Tool", true' -or $configManagerSource -notmatch 'ensureUtilityProfileEntries') { throw 'Built-in utility profile defaults or non-destructive migration are missing.' }
if ($configManagerSource -notmatch 'bedwars-legit' -or $configManagerSource -notmatch 'bedwars-aggressive' -or $configManagerSource -notmatch 'createBedwarsProfile') { throw 'Revamped BedWars profile library is missing.' }
if ($configManagerSource -notmatch 'bedwars-aggressive-v2' -or $configManagerSource -notmatch 'createBedwarsAggressiveV2') { throw 'Coordinated BedWars aggressive v2 profile is missing.' }
$settingCodecSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\config\SettingCodec.java') -Raw
$atomicFileStoreSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\config\AtomicFileStore.java') -Raw
if ($configManagerSource -notmatch 'SAVE_DEBOUNCE_NANOS' -or $configManagerSource -notmatch 'flushPendingSaveNow' -or
    $configManagerSource -notmatch 'AtomicFileStore\.write' -or $atomicFileStoreSource -notmatch 'ATOMIC_MOVE' -or
    $atomicFileStoreSource -notmatch 'getFD\(\)\.sync') { throw 'Debounced durable atomic config persistence is missing.' }
foreach ($codecMarker in @('decode\(', 'capture\(', 'apply\(', 'encode\(', 'IntRangeSetting', 'EnumSetting')) {
    if ($settingCodecSource -notmatch $codecMarker) { throw "Typed setting codec marker is missing: $codecMarker" }
}
if ($configManagerSource -notmatch 'stageConfig' -or $configManagerSource -notmatch 'captureRuntimeState' -or $configManagerSource -notmatch 'restoreRuntimeState') { throw 'Transactional config validation or rollback is missing.' }
if ($payloadClientSource -notmatch 'saveCurrent\(\);\s*client\.packetDelayManager\.closeForUnload\(\);\s*client\.moduleManager\.shutdownForUnload') { throw 'Unload must persist the active profile before transport close and module teardown.' }
foreach ($addition in @('HitSelectModule', 'SprintResetModule', 'AutoWeaponModule', 'TeamsModule', 'NoHitDelayModule', 'CriticalsModule')) {
    if ($manager -notmatch $addition) { throw "Combat addition is not registered: $addition" }
}
$clickGuiModuleSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\ClickGuiModule.java') -Raw
$guiThemeSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\gui\GuiTheme.java') -Raw
if ($clickGuiModuleSource -notmatch 'getThemeBackgroundColor' -or $clickGuiModuleSource -notmatch '"Amethyst"' -or $guiThemeSource -notmatch 'getThemeSurfaceColor') { throw 'Full GUI theme palettes are missing.' }
$launcherSource = Get-Content (Join-Path $root 'native\launcher\main.cpp') -Raw
if ($launcherSource -notmatch 'genericLunar189Compatible' -or $launcherSource -notmatch '#ifndef RAZORCLIENT_RELEASE' -or
    $launcherSource -notmatch 'executableHash' -or $launcherSource -notmatch 'jvmHash' -or
    $launcherSource -notmatch 'processCreationTime') { throw 'PID-bound Lunar compatibility validation or Debug-only generic fallback is missing.' }
if ($launcherSource -notmatch 'class RemoteAllocation' -or $launcherSource -notmatch 'remoteFunctionAddress' -or
    $launcherSource -notmatch 'wait != WAIT_OBJECT_0\) remote\.abandon') {
    throw 'Remote LoadLibrary address resolution or timeout memory guard is missing.'
}
if ($launcherSource -notmatch 'findReusableBootstrap' -or $launcherSource -notmatch 'RazorClientRestart' -or
    $launcherSource -notmatch 'currentDllHash' -or $launcherSource -notmatch 'currentJarHash' -or
    $launcherSource -notmatch 'RazorClient\.Inject\.') {
    throw 'Hash-matched bootstrap reuse or cross-launcher injection serialization is missing.'
}
$actionCoordinator = Get-Content (Join-Path $project 'src\main\java\com\razorclient\combat\CombatActionCoordinator.java') -Raw
$mouseHelper = Get-Content (Join-Path $project 'src\main\java\com\razorclient\util\MouseButtonHelper.java') -Raw
if ($moduleBase -notmatch 'ModuleResetReason' -or $clientSessionSource -notmatch 'GUI_OPENED' -or
    $clientSessionSource -notmatch 'FOCUS_LOSS' -or $manager -notmatch 'getInputReason') {
    throw 'Reasoned module lifecycle resets are missing.'
}
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
$clutchDirectory = Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\clutch'
foreach ($clutchHelper in @('ClutchPhase.java', 'ClutchSession.java', 'ClutchPredictor.java', 'ClutchCandidate.java',
        'ClutchCandidateScanner.java', 'ClutchConfirmationTracker.java', 'ClutchPlacementExecutor.java',
        'ClutchBridgePlanner.java', 'ClutchSilentRotationController.java')) {
    if (!(Test-Path (Join-Path $clutchDirectory $clutchHelper))) { throw "Split Clutch component is missing: $clutchHelper" }
}
$clutchPhaseSource = Get-Content (Join-Path $clutchDirectory 'ClutchPhase.java') -Raw
$clutchSessionSource = Get-Content (Join-Path $clutchDirectory 'ClutchSession.java') -Raw
$clutchExecutorSource = Get-Content (Join-Path $clutchDirectory 'ClutchPlacementExecutor.java') -Raw
$clutchRotationSource = Get-Content (Join-Path $clutchDirectory 'ClutchSilentRotationController.java') -Raw
if ($clutchRotationSource -notmatch 'requestRotations\("Clutch", 95' -or
    $clutchRotationSource -notmatch 'ResourceArbiter\.Resource\.MODEL_ROTATION' -or
    $clutchRotationSource -notmatch 'restoreRenderSwap' -or $clutchRotationSource -notmatch 'rotationYawHead' -or
    $clutchRotationSource -notmatch 'renderYawOffset') { throw 'Clutch silent model rotation ownership or cleanup is incomplete.' }
if ($clutchSource -match 'static final ClutchModule INSTANCE' -or $manager -notmatch 'new ClutchModule\(\)') { throw 'Clutch must be recreated per client lifecycle.' }
foreach ($phase in @('IDLE', 'ARMED', 'AIMING', 'PLACING', 'CONFIRMING', 'BRIDGING', 'CLEANUP')) {
    if ($clutchPhaseSource -notmatch "\b$phase\b") { throw "Clutch recovery phase is missing: $phase" }
}
foreach ($settingName in @('Recovery Mode', 'Prediction Ticks', 'Confirmation Ticks')) {
    if ($clutchSource -notmatch [regex]::Escape($settingName)) { throw "Clutch recovery setting is missing: $settingName" }
}
if ($clutchSource -notmatch 'PREDICTED_DANGER' -or $clutchSource -notmatch 'processConfirmation' -or
    $clutchSource -notmatch 'ResourceArbiter\.Resource\.USE_ACTION' -or
    $clutchSessionSource -notmatch 'NO_BLOCK_TIMEOUT_NANOS' -or $clutchSessionSource -notmatch 'SUPPRESSED_TIMEOUT_NANOS') {
    throw 'Clutch prediction, bounded aborts, placement confirmation, or action arbitration is missing.'
}
if ($clutchSource -match 'PrePlayerInputEvent|moveForward\s*=|moveStrafing\s*=') { throw 'Clutch must not modify player movement input.' }
if ($clutchExecutorSource -notmatch 'catch \(NoSuchMethodError \| AbstractMethodError unavailable\)' -or
    $clutchExecutorSource -notmatch 'confirmation decides success' -or
    $clutchExecutorSource -match 'if \(!accepted\).*fallback') { throw 'Clutch fallback placement policy is unsafe.' }
$espSource = Get-Content (Join-Path $project 'src\main\java\com\razorclient\feature\module\impl\PlayerEspModule.java') -Raw
$bootstrapSource = Get-Content (Join-Path $root 'native\bootstrap\bootstrap.cpp') -Raw
foreach ($espKey in @('Projection Mode', 'Target Type', 'Through Walls', 'Health Bar', 'Held Item', 'Target Highlight')) {
    if ($espSource -notmatch [regex]::Escape($espKey)) { throw "PlayerESP setting is missing: $espKey" }
}
if ($espSource -notmatch 'EntityFrame' -or $espSource -notmatch 'EntityRecord' -or
    $espSource -match 'SnapshotFrame|EntityLivingBase\[\]' -or $espSource -notmatch 'GLU\.gluProject' -or
    $espSource -notmatch 'getTargetPublications\(\)\.activeTarget') {
    throw 'PlayerESP immutable entity frame, projection, or target publication integration is missing.'
}
if ($liveEntrypoint -notmatch 'installNativeRenderBridge' -or $liveEntrypoint -notmatch 'renderNativeFrame' -or $liveEntrypoint -match 'renderLiveBridge\(\)') { throw 'Render-phase live bridge integration is missing or tick rendering remains active.' }
if ($liveEntrypoint -notmatch 'pendingManager' -or $liveEntrypoint -notmatch '!running \|\| !isCurrentEpoch' -or $liveEntrypoint -notmatch 'ChannelInboundHandlerAdapter implements ChannelOutboundHandler' -or $liveEntrypoint -notmatch 'addBefore\(anchor, PACKET_HANDLER_NAME' -or $liveEntrypoint -notmatch 'razorclient_live_transport') { throw 'Single Netty transport handler installation or lifecycle guard is missing.' }
if ($liveEntrypoint -notmatch 'installedPacketHandler' -or
    $liveEntrypoint -notmatch 'pipeline\.get\(PACKET_HANDLER_NAME\) == handler' -or
    $liveEntrypoint -notmatch 'pipeline\.remove\(handler\)') {
    throw 'Netty cleanup must remove only the handler instance owned by that injection epoch.'
}
if ($bootstrapSource -notmatch 'hookedSwapBuffers' -or $bootstrapSource -notmatch 'uninstallRenderBridge' -or $bootstrapSource -notmatch 'renderInstalled' -or $bootstrapSource -notmatch 'renderThreadId' -or $bootstrapSource -notmatch 'wglGetCurrentContext' -or $bootstrapSource -notmatch 'renderWindow') { throw 'Native render bridge install/uninstall or render-surface guards are missing.' }
if ($bootstrapSource -notmatch 'RazorClientRestart' -or $bootstrapSource -notmatch 'initializationRunning' -or
    $bootstrapSource -notmatch 'AGENT_SEARCH_REUSED' -or $bootstrapSource -notmatch 'systemSearchHash') {
    throw 'Bootstrap restart ownership or idempotent classpath search handling is missing.'
}
$buildSource = Get-Content (Join-Path $root 'build.ps1') -Raw
if ($buildSource -notmatch '-lopengl32') { throw 'Native render bridge OpenGL linkage is missing.' }
if ($buildSource -notmatch "ValidateSet\('Debug', 'Release'\)" -or $buildSource -notmatch 'proguard-base-7\.8\.1' -or $buildSource -notmatch 'Assert-Sha256') { throw 'Reproducible release obfuscation configuration is missing.' }
if ($buildSource -notmatch 'C4C05056FB035665CBE3128E64A8A6E3EC0A1BDF791A4B8A4BB9816B1296754F' -or
    $buildSource -notmatch 'Pinned JDK 21\.0\.10' -or
    $buildSource -notmatch [regex]::Escape('clang version 22\.1\.8')) {
    throw 'Pinned Lunar API, JDK, or LLVM build input validation is missing.'
}
if (!(Test-Path (Join-Path $root 'sign-release.ps1')) -or !(Test-Path (Join-Path $root 'security-verify.ps1'))) { throw 'Release signing or security verifier script is missing.' }
$launcherSecuritySource = Get-Content (Join-Path $root 'native\launcher\main.cpp') -Raw
if ($launcherSecuritySource -notmatch 'verifyAuthenticode' -or $launcherSecuritySource -notmatch 'BCryptVerifySignature' -or
    $launcherSecuritySource -notmatch 'sha256Bytes' -or $launcherSecuritySource -notmatch 'digest\.data\(\)' -or
    $launcherSecuritySource -notmatch 'RAZORCLIENT_COMPAT_PUBLIC_KEY_B64' -or
    $launcherSecuritySource -notmatch 'verifyExpectedPayloadHash') { throw 'Native release integrity or digest-based signature verification is missing.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($agent)
try {
    $entries = @($zip.Entries | ForEach-Object FullName)
    foreach ($legacyEntry in @('mcmod.info', 'mixins.razorclient.json')) {
        if ($entries -contains $legacyEntry) { throw "Legacy Forge/Mixin metadata leaked into the live payload: $legacyEntry" }
    }
    foreach ($entry in @(
        'com/razorclient/inject/LiveEntrypoint.class',
        'com/razorclient/inject/InjectionStatus.class',
        'com/razorclient/inject/ChildFirstPayloadLoader.class',
        'com/razorclient/inject/JarSignatureVerifier.class',
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
        'com/razorclient/runtime/ClientSession.class',
        'com/razorclient/runtime/ClientThreadScheduler.class',
        'com/razorclient/runtime/OwnerToken.class',
        'com/razorclient/runtime/ModuleContext.class',
        'com/razorclient/runtime/EntityFrame.class',
        'com/razorclient/runtime/EntityRecord.class',
        'com/razorclient/runtime/EntitySnapshotService.class',
        'com/razorclient/runtime/ModuleScope.class',
        'com/razorclient/runtime/ResourceArbiter.class',
        'com/razorclient/runtime/capability/TickListener.class',
        'com/razorclient/runtime/capability/RenderListener.class',
        'com/razorclient/runtime/capability/InputListener.class',
        'com/razorclient/runtime/capability/PacketPolicy.class',
        'com/razorclient/network/PacketDecision.class',
        'com/razorclient/network/PacketLane.class',
        'com/razorclient/network/PacketReleasePolicy.class',
        'com/razorclient/combat/TargetPublicationService.class',
        'com/razorclient/config/SettingCodec.class',
        'com/razorclient/config/AtomicFileStore.class',
        'com/razorclient/feature/module/impl/clutch/ClutchSession.class',
        'com/razorclient/feature/module/impl/clutch/ClutchPredictor.class',
        'com/razorclient/feature/module/impl/clutch/ClutchCandidateScanner.class',
        'com/razorclient/feature/module/impl/clutch/ClutchConfirmationTracker.class',
        'com/razorclient/feature/module/impl/clutch/ClutchPlacementExecutor.class',
        'com/razorclient/feature/module/impl/clutch/ClutchBridgePlanner.class',
        'com/razorclient/feature/module/impl/clutch/ClutchSilentRotationController.class',
        'com/razorclient/gui/ClickGuiLayoutModel.class',
        'com/razorclient/gui/ClickGuiCategoryPanel.class',
        'com/razorclient/gui/ClickGuiProfileAdapter.class',
        'com/razorclient/gui/ClickGuiSliderControl.class',
        'com/razorclient/runtime/BuildMetadata.class',
        'com/razorclient/runtime/SecureStringTable.class',
        'com/razorclient/runtime/ReleaseIntegrity.class',
        'com/razorclient/runtime/CompatibilityManifestVerifier.class'
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

if (!(Test-Path -LiteralPath $runtimeTests)) { throw 'Deterministic runtime architecture test runner is missing.' }
& $runtimeTests
if ($LASTEXITCODE) { throw 'Deterministic runtime architecture tests failed.' }

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
