@echo off
rem Runs the WebView2 smoke: it opens a real window, so it must be launched from an
rem interactive desktop session (RDP or the console) - over SSH it lands in session 0,
rem where WebView2 does not run. The run ends by itself; stages and the verdict land
rem in smoke-result.txt beside this file.
cd /d %~dp0
where mvn >nul 2>nul || set "PATH=C:\tools\apache-maven-3.9.16\bin;%PATH%"
mvn -q compile exec:exec -Dmain.class=panamaview.probe.Smoke
echo smoke exit code %ERRORLEVEL%
