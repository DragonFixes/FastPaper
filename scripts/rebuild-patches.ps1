#Requires -Version 5.1

<#
.SYNOPSIS
    Rebuilds the patch files from the patched source trees.

.DESCRIPTION
    Runs "gradlew rebuildPatches" from the repository root, which writes the
    commits and edits in the patched paper-api and paper-server trees back
    into the patch files. With -Fixup, "gradlew fixupSourcePatches" runs first
    to fold fixup commits into the source patches.

.PARAMETER Fixup
    Run fixupSourcePatches before rebuildPatches.

.PARAMETER DryRun
    Print the Gradle commands without running them.

.EXAMPLE
    PS> .\scripts\rebuild-patches.ps1
    Rebuilds all patches.

.EXAMPLE
    PS> .\scripts\rebuild-patches.ps1 -Fixup
    Folds fixup commits into the source patches, then rebuilds.
#>
[CmdletBinding()]
param(
    [switch]$Fixup,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradlew = Join-Path $root 'gradlew.bat'

$tasks = @()
if ($Fixup) { $tasks += 'fixupSourcePatches' }
$tasks += 'rebuildPatches'

Push-Location $root
try {
    foreach ($task in $tasks) {
        Write-Host "> gradlew $task" -ForegroundColor Cyan
        if ($DryRun) { continue }
        & $gradlew $task
        if ($LASTEXITCODE -ne 0) {
            Write-Error "gradlew $task failed with exit code $LASTEXITCODE"
            exit $LASTEXITCODE
        }
    }
    exit 0
} finally {
    Pop-Location
}
