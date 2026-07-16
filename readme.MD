# RazorClient

RazorClient is built and distributed through the Windows x64 live-injection pipeline in `injectable`.

## Debug build

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\injectable\build.ps1 -Mode Debug
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\injectable\verify.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\injectable\security-verify.ps1 -Mode Debug
```

The portable one-file artifact is written to `release\RazorClient.exe`. Launch Lunar 1.8.9 first, start the launcher, unlock it, and press **Inject**.

Release mode requires configured Authenticode and JAR signing credentials and fails closed when they are unavailable. The old Forge/Mixin baseline is archived under `legacy\forge-1.8.9` and is not an active runtime.
