@echo off
rem The scheduled-task wrapper for the self-test: the same run, with output captured so a run
rem nobody watches can still be judged afterwards. Interactive humans run run-selftest.cmd
rem instead - there the stages print live in the console.
call "%~dp0run-selftest.cmd" > "%~dp0selftest-result.txt" 2>&1
exit /b %ERRORLEVEL%
