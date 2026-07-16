$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Split-Path -Parent $root
$agent = Join-Path $root 'dist\razorclient-agent.jar'
$api = Join-Path $project 'build\lunar\lunar-runtime-api.jar'
$testSources = Join-Path $root 'tests\src'
$testClasses = Join-Path $root 'build\runtime-test-classes'
$sourceList = Join-Path $root 'build\runtime-test-sources.txt'
$javac = 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe'
$java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'

if (!(Test-Path -LiteralPath $javac)) { $javac = (Get-Command javac.exe).Source }
if (!(Test-Path -LiteralPath $java)) { $java = (Get-Command java.exe).Source }
if (!(Test-Path -LiteralPath $agent)) { throw "Missing Debug agent artifact: $agent" }
if (!(Test-Path -LiteralPath $api)) { throw "Missing Lunar API artifact: $api" }

Remove-Item -LiteralPath $testClasses -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath $testSources -Recurse -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
if ($sources.Count -eq 0) { throw 'Runtime architecture test sources are missing.' }
$sources | Set-Content -LiteralPath $sourceList -Encoding ASCII

& $javac -encoding UTF-8 --release 17 -cp "$agent;$api" -d $testClasses "@$sourceList"
if ($LASTEXITCODE) { throw 'Runtime architecture test compilation failed.' }

& $java -ea -cp "$testClasses;$agent;$api" com.razorclient.runtime.RuntimeArchitectureTest
if ($LASTEXITCODE) { throw 'Runtime architecture tests failed.' }
