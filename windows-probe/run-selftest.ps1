# The self-test, wrapped so run-in-session.ps1 can deliver it into an interactive session.
#
# run-in-session.ps1 takes a PowerShell script and runs it in the logged-on user's session,
# where WebView2 starts a controller. This wrapper is what it is pointed at: it runs the
# self-test and leaves the verdict in a file beside it.
#
# $ErrorActionPreference is deliberately at its default. "Stop" turns a native command's
# stderr into a terminating error, and Maven writes to stderr on every build - so the
# diagnostic lines below would kill the script before the test ever started. Measured, not
# theorised.

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$where = "C:\tools\apache-maven-3.9.16\bin"
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $where = Split-Path (Get-Command mvn).Source
}
$env:PATH = "$where;$env:PATH"

# Software rendering: GPU acceleration on an RDP desktop is one more thing that can stall the
# browser's boot, and this is a test rather than a demo.
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--disable-gpu"

Write-Output "=== panamaview bridge self-test (Windows) ==="
& mvn -q compile exec:exec "-Dmain.class=io.github.lihuu.panamaview.probe.WindowsSelfTest" 2>&1 |
    ForEach-Object { Write-Output $_ }

Write-Output ("=== exit code: " + $LASTEXITCODE)
exit $LASTEXITCODE
