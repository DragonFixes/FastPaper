@echo off
setlocal
rem Usage: apply-patches.bat [-DryRun]
rem Runs gradlew applyPatches.
rem All arguments are forwarded unchanged to apply-patches.ps1; see Get-Help .\scripts\apply-patches.ps1 -Full.

set "SCRIPT=%~dp0apply-patches.ps1"
if not exist "%SCRIPT%" (
    echo apply-patches.ps1 not found next to %~nx0 1>&2
    exit /b 1
)

where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%