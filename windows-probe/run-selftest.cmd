@echo off
rem Runs the bridge self-test: the same checks the macOS demo runs, on Windows.
rem
rem It opens a window on the desktop it runs in, so it must be launched from an interactive
rem session (RDP or the console) - over SSH it lands in session 0, where WebView2 does not
rem run. The verdict prints here and lands in selftest-result.txt beside this file, so a run
rem nobody watched can still be judged afterwards.
rem
rem The exit code is the verdict: 0 when every check passed, 1 otherwise.
cd /d %~dp0
where mvn >nul 2>nul || set "PATH=C:\tools\apache-maven-3.9.16\bin;%PATH%"
rem GPU acceleration on an RDP desktop is one more thing that can stall the browser's boot;
rem software rendering is plenty for a test.
set "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--disable-gpu"
rem "call" is not decoration: mvn.cmd is a batch file, and a plain invocation hands
rem control to it and never comes back - the exit code below would be a lie.
call mvn compile exec:exec -Dmain.class=io.github.lihuu.panamaview.probe.WindowsSelfTest
echo self-test exit code %ERRORLEVEL%
