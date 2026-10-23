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

# The whole path speaks UTF-8, so the log carries the menu's Chinese titles as characters.
#
# The picture was always right; the log was not. The JVM's default output encoding is the
# machine's native one -- GBK on that Windows box -- while everything that reads the bytes
# on the way to the log expects UTF-8, so a title like "文件" came apart in between. And the
# log is the artefact a CI run reads, so "the log says the title is broken" was a false alarm
# in exactly the place where it would be noticed.
#
# Three settings, one encoding: the probe's own JVM writes UTF-8 (the exec plugin's
# arguments, in the probe's pom), Maven writes UTF-8 (here), and the console reads UTF-8
# (here). MAVEN_OPTS is the launcher's own way of saying it to Maven's JVM, and it is silent
# -- JAVA_TOOL_OPTIONS would work too, but the JVM announces that one on stderr, and the log
# is the evidence.
$env:MAVEN_OPTS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

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
