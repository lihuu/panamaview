@echo off
rem The interactive demo: a real window with buttons, input, a cancellable slow task and
rem Java-to-page pushes. Same rule as the smoke - launch it from an interactive desktop
rem session (RDP or the console), never from SSH. Close the window when you are done:
rem that is the quit path, and it exercises it.
cd /d %~dp0
where mvn >nul 2>nul || set "PATH=C:\tools\apache-maven-3.9.16\bin;%PATH%"
rem GPU acceleration on an RDP desktop is one more thing that can stall the browser's boot;
rem software rendering is plenty for a demo.
set "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--disable-gpu"
rem "call" is not decoration: mvn.cmd is a batch file, and a plain invocation hands
rem control to it and never comes back - the exit code below would be a lie.
call mvn compile exec:exec -Dmain.class=io.github.lihuu.panamaview.probe.WindowsDemo
echo demo exit code %ERRORLEVEL%
