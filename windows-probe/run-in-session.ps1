# Runs a command in the Windows user's interactive session, from an SSH session.
#
# Why this is needed: SSH lands in session 0, and WebView2 cannot start a controller there --
# measured, the failure is `the WebView2 controller failed: 0x80070578`. The window opens fine in
# the session the user is actually sitting in. So a check that means anything has to be delivered
# into that session rather than run where the SSH connection put us.
#
# The mechanism is a scheduled task with -LogonType Interactive. What makes it work is the logon
# type: a task registered for an interacting user runs in that user's session, while a task
# registered for "run whether the user is logged on or not" would land back in session 0 and fail
# the same way.
#
# The session id is deliberately not a parameter. It changes with how the user connected -- measured
# on this machine, `console` was session 1 while nobody was at the keyboard and session 2 once an
# RDP connection took session 1. Naming a number would work until it did not.
#
# Usage:
#   .\run-in-session.ps1 -File .\some-script.ps1 [-TimeoutSeconds 90]
#
#   -File     a PowerShell script to run in the interactive session
#   -TimeoutSeconds   how long to wait for it to finish (default 120)
#
# The wrapped script's output goes to a log file beside it, named "<script>-session.log", and is
# echoed here when the task finishes. That matters more than it looks: the task's own stdout is not
# attached to anything, so a script that only printed would appear to do nothing.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$target = (Resolve-Path $File).Path
$log    = [IO.Path]::ChangeExtension($target, ".session.log")
$task   = "panamaview-session-run"

if (-not (Test-Path $target)) { throw "no such file: $target" }
Remove-Item $log -Force -ErrorAction SilentlyContinue

# An interactive task runs as the logged-on user, and that user is the one whose session has the
# desktop. $env:USERDOMAIN is the machine name for a local account, which is what this needs.
$userId = "$env:COMPUTERNAME\$env:USERNAME"

$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -Command `"& '$target' *> '$log'`""

# -LogonType Interactive is the whole point; see the header. Limited, because running a build does
# not need elevation and asking for it would put a UAC prompt on the user's screen.
$principal = New-ScheduledTaskPrincipal -UserId $userId -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $task -Action $action -Principal $principal -Force | Out-Null
try {
    Start-ScheduledTask -TaskName $task

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $state = (Get-ScheduledTask -TaskName $task).State
        # "Ready" is the state a task returns to when it has finished. Waiting for the log as well
        # covers the moment between the process exiting and the file being flushed.
        if ($state -ne "Running" -and (Test-Path $log)) { break }
    }

    $state = (Get-ScheduledTask -TaskName $task).State
    if ($state -eq "Running") {
        Write-Warning "still running after ${TimeoutSeconds}s; the log so far follows"
    }

    if (Test-Path $log) {
        Get-Content $log
    } else {
        Write-Warning "no log at $log — the task produced nothing, which is itself a result"
    }

    # The task's LastTaskResult is the wrapped script's exit code, which is what a caller should
    # branch on. Reported rather than thrown, so the log above is still visible.
    $result = (Get-ScheduledTaskInfo -TaskName $task).LastTaskResult
    Write-Host "exit code: $result"
    exit $result
}
finally {
    # Left registered on failure on purpose? No — a task that stays behind runs again on schedule
    # changes nobody made. Unregistered here, and the failure is already in the log.
    Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
}
