@echo off
rem Runs the WebView2 smoke: it opens a real window, so it must be launched from an
rem interactive desktop session (RDP or the console) - over SSH it lands in session 0,
rem where WebView2 does not run. The run ends by itself; the stage log and the verdict
rem print here, and also land in smoke-result.txt beside this file.
cd /d %~dp0
where mvn >nul 2>nul || set "PATH=C:\tools\apache-maven-3.9.16\bin;%PATH%"
rem GPU acceleration on an RDP desktop is one more thing that can stall the browser's boot;
rem software rendering is plenty for a smoke.
set "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--disable-gpu"
rem "call" is not decoration: mvn.cmd is a batch file, and a plain invocation hands
rem control to it and never comes back - the exit code below would be a lie.
call mvn compile exec:exec -Dmain.class=io.github.lihuu.panamaview.probe.Smoke
echo smoke exit code %ERRORLEVEL%
