@echo off

set /p PID=<pid

echo kill process %PID%

taskkill /F /PID %PID%

del pid