# ®️azorClient Injectable

Windows x64 injector and Java instrumentation payload for the pinned Lunar Client 1.8.9 bake `72c13d03/a1efafdf`.

## Build

Run `powershell -ExecutionPolicy Bypass -File .\build.ps1`. The script compiles the Java 17 payload against the pinned generated Lunar API JAR and builds the native launcher plus bootstrap DLL with the portable compiler under `tools/`.

## Runtime

Launch Lunar Client 1.8.9 normally first. After the Minecraft game window is running, run `dist\RazorClient.exe` and click `Inject`. The default UI performs true post-launch DLL injection into the existing Lunar JVM and starts the live Java entrypoint; it does not pre-hook the Lunar launcher or insert a startup `-javaagent`.

Right Shift opens the original in-game ClickGUI. Runtime hooks use a client-thread pulse plus a Netty packet handler because the pinned Lunar JVM does not expose the JVMTI breakpoint/local-variable/retransform capabilities needed for post-live bytecode hooks.

Only the fingerprint in `config/lunar-1.8.9.json` is accepted.
