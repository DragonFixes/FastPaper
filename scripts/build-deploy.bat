@echo off
setlocal
rem Usage: build-deploy.bat [-SkipPatches] [-Clean] [-DryRun]
rem Clears deploy\*.jar, applies patches, builds the Paperclip jar, copies it to deploy\.
rem All arguments are forwarded unchanged to build-deploy.ps1; see Get-Help .\scripts\build-deploy.ps1 -Full.

set "SCRIPT=%~dp0build-deploy.ps1"
if not exist "%SCRIPT%" (
    echo build-deploy.ps1 not found next to %~nx0 1>&2
    exit /b 1
)

where pwsh >nul 2>nul
if %ERRORLEVEL% equ 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
)
exit /b %ERRORLEVEL%