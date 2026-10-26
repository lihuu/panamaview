@echo off
rem Builds the bridge self-test as a standalone Windows executable with GraalVM Native Image.
rem
rem The native binary is where the reachability metadata earns its keep: a native shape that
rem is not registered is invisible on the JVM, and fatal here on the first call that needs it.
rem Run the executable from this directory -- the loader is looked up at
rem loader\WebView2Loader.dll relative to the working directory.
setlocal
cd /d %~dp0

if not defined JAVA_HOME (
    echo build-native.cmd: set JAVA_HOME to a GraalVM installation 1>&2
    exit /b 1
)
if not exist "%JAVA_HOME%\bin\native-image.cmd" (
    echo build-native.cmd: no native-image in %JAVA_HOME% 1>&2
    exit /b 1
)

rem mvn.cmd is a batch file: without "call" this script would hand over control and never
rem return, and the exit code below would be whatever was last set.
call mvn -q compile || exit /b 1
call mvn -q dependency:build-classpath -Dmdep.outputFile=target\classpath.txt || exit /b 1

set /p DEPS=<target\classpath.txt

rem "selftest/.*" is this application's own asset tree. The framework's bridge arrives through
rem panamaview's own metadata, which is transitive; a consumer's pages are the consumer's to
rem declare. Same split as demo-app/build-native.sh.
call "%JAVA_HOME%\bin\native-image.cmd" ^
    --no-fallback ^
    -H:IncludeResources="selftest/.*" ^
    -H:+ReportExceptionStackTraces ^
    -J-Xmx4g ^
    --enable-native-access=ALL-UNNAMED ^
    -cp "target\classes;%DEPS%" ^
    -o target\panamaview-selftest ^
    io.github.lihuu.panamaview.probe.WindowsSelfTest
exit /b %ERRORLEVEL%
