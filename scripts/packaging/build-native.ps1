# Build the app as a standalone Windows executable with GraalVM Native Image.
#
# The Windows counterpart of build-native.sh, and the same shape: compile, ask Maven for the
# dependency classpath, then hand both to native-image. The one difference is deliberate --
# there is no -XstartOnFirstThread here, because Windows does not require the process' first
# thread the way AppKit does.
#
# Resources are embedded, not shipped: "web/.*" is this application's own asset tree, and the
# framework's client bridge arrives through panamaview's native-image metadata, which travels
# with its jar.
#
# Usage: .\build-native.ps1 [-MainClass CLASS] [-Output NAME] [-IncludeResources GLOB]
#
# Requires JAVA_HOME to point at a GraalVM installation with native-image.
[CmdletBinding()]
param(
    [string]$MainClass = "",
    [string]$Output = "",
    [string]$IncludeResources = "web/.*"
)

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

function Fail([string]$message) {
    Write-Error "build-native.ps1: $message" -ErrorAction Continue
    exit 1
}

function PomValue([string]$element) {
    $text = Get-Content pom.xml -Raw
    $pattern = "<" + [regex]::Escape($element) + ">([^<]*)</" + [regex]::Escape($element) + ">"
    $match = [regex]::Match($text, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ""
}

if (-not (Test-Path pom.xml)) { Fail "no pom.xml beside this script" }
if (-not $env:JAVA_HOME) { Fail "set JAVA_HOME to a GraalVM installation" }
$nativeImage = Join-Path $env:JAVA_HOME "bin\native-image.cmd"
if (-not (Test-Path $nativeImage)) { Fail "no native-image in $env:JAVA_HOME" }

if (-not $MainClass) { $MainClass = PomValue "main.class" }
if (-not $MainClass) { Fail "no <main.class> in pom.xml; pass -MainClass" }

$artifact = PomValue "artifactId"
if (-not $artifact) { Fail "no <artifactId> in pom.xml" }
if (-not $Output) { $Output = $artifact }

Write-Output "==> compiling"
& mvn -q compile
if ($LASTEXITCODE -ne 0) { Fail "mvn compile failed ($LASTEXITCODE)" }

Write-Output "==> resolving the dependency classpath"
& mvn -q dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt"
if ($LASTEXITCODE -ne 0) { Fail "dependency:build-classpath failed ($LASTEXITCODE)" }
$deps = (Get-Content "target\classpath.txt" -Raw).Trim()
if (-not $deps) { Fail "target\classpath.txt is empty" }

Write-Output "==> building target\$Output.exe"
$nativeArgs = @(
    "--no-fallback"
    "-H:IncludeResources=$IncludeResources"
    "-H:+ReportExceptionStackTraces"
    "-J-Xmx4g"
    "--enable-native-access=ALL-UNNAMED"
    "-cp"
    "target\classes;$deps"
    "-o"
    "target\$Output"
    $MainClass
)
& $nativeImage @nativeArgs
if ($LASTEXITCODE -ne 0) { Fail "native-image failed ($LASTEXITCODE)" }

Write-Output "[build] target\$Output.exe"
