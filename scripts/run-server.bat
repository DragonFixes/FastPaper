@echo off
setlocal
rem Usage: run-server.bat [-Memory 4G] [-Gui] [-ServerDir <dir>] [-AcceptEula] [-Java <exe>] [-DryRun]
rem Runs the newest deploy\*.jar in deploy\server.
rem All arguments are forwarded unchanged to run-server.ps1; see Get-Help .\scripts\run-server.ps1 -Full.

set "SCRIPT=%~dp0run-server.ps1"
if not exist "%SCRIPT%" (
    echo run-server.ps1 not found next to %~nx0 1>&2
    exit /b 1
)

where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%