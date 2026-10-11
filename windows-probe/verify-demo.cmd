@echo off
rem The scheduled-task wrapper for the demo: the same run, with output captured so a run
rem nobody watches can still be judged afterwards. Interactive humans run run-demo.cmd
rem instead - there the stages print live in the console.
call "%~dp0run-demo.cmd" > "%~dp0demo-run.log" 2>&1
