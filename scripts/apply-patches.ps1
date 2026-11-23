#Requires -Version 5.1

<#
.SYNOPSIS
    Applies the FastPaper patches to the API and server source trees.

.DESCRIPTION
    Runs "gradlew applyPatches" from the repository root, which sets up the
    patched paper-api and paper-server source trees. Run it after cloning,
    after switching branches and after pulling patch changes.

.PARAMETER DryRun
    Print the Gradle command without running it.

.EXAMPLE
    PS> .\scripts\apply-patches.ps1
    Applies all patches.

.EXAMPLE
    PS> .\scripts\apply-patches.ps1 -DryRun
    Prints the command that would run.
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

Write-Host '> gradlew applyPatches' -ForegroundColor Cyan
if ($DryRun) { exit 0 }

Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') applyPatches
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
