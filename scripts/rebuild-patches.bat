@echo off
setlocal
rem Usage: rebuild-patches.bat [-Fixup] [-DryRun]
rem Runs gradlew rebuildPatches (fixupSourcePatches first with -Fixup).
rem All arguments are forwarded unchanged to rebuild-patches.ps1; see Get-Help .\scripts\rebuild-patches.ps1 -Full.

set "SCRIPT=%~dp0rebuild-patches.ps1"
if not exist "%SCRIPT%" (
    echo rebuild-patches.ps1 not found next to %~nx0 1>&2
    exit /b 1
)

where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%