#Requires -Version 5.1

<#
.SYNOPSIS
    Fetches the upstream remotes and reports how far FastPaper's branches have drifted.

.DESCRIPTION
    Fetches the "paper" (PaperMC/Paper) and "upstream" (Swift-ality/Paper)
    remotes, then prints ahead/behind counts for master against
    upstream/swift-invasive and main against paper/main. With -Merge, merges
    the given ref into the current branch after a confirmation prompt.
    Nothing is ever pushed.

.PARAMETER Merge
    A ref to merge into the current branch after fetching, e.g. paper/main.

.PARAMETER NoFetch
    Skip fetching and report against the remote-tracking refs already present.

.PARAMETER Force
    Skip the confirmation prompt before merging.

.PARAMETER DryRun
    Print the fetch and merge commands without running them; the drift report
    uses the refs already present.

.EXAMPLE
    PS> .\scripts\sync-upstream.ps1
    Fetches both remotes and prints the drift report.

.EXAMPLE
    PS> .\scripts\sync-upstream.ps1 -Merge upstream/swift-invasive
    Fetches, reports, then merges the Swift branch into the current branch after confirming.

.EXAMPLE
    PS> .\scripts\sync-upstream.ps1 -Merge paper/main -DryRun
    Shows what would be fetched and merged.
#>
[CmdletBinding()]
param(
    [string]$Merge,
    [switch]$NoFetch,
    [switch]$Force,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Invoke-Git([string[]]$GitArgs) {
    & git -C $root @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') failed with exit code $LASTEXITCODE" }
}

function Test-Ref([string]$Ref) {
    & git -C $root rev-parse --verify --quiet "$Ref^{commit}" *> $null
    return $LASTEXITCODE -eq 0
}

function Write-Drift([string]$Local, [string]$Remote) {
    if (-not (Test-Ref $Local)) { Write-Host ("{0,-10} vs {1,-26} {2}" -f $Local, $Remote, '(local branch missing)'); return }
    if (-not (Test-Ref $Remote)) { Write-Host ("{0,-10} vs {1,-26} {2}" -f $Local, $Remote, '(remote ref missing)'); return }
    $counts = (& git -C $root rev-list --left-right --count "$Local...$Remote") -split '\s+'
    Write-Host ("{0,-10} vs {1,-26} ahead {2,5}  behind {3,5}" -f $Local, $Remote, $counts[0], $counts[1])
}

try {
    if (-not $NoFetch) {
        foreach ($remote in 'paper', 'upstream') {
            Write-Host "> git fetch $remote" -ForegroundColor Cyan
            if (-not $DryRun) { Invoke-Git @('fetch', $remote) }
        }
    }

    Write-Host ''
    Write-Drift 'master' 'upstream/swift-invasive'
    Write-Drift 'main' 'paper/main'
    Write-Host ''

    if ($Merge) {
        if (-not (Test-Ref $Merge)) { throw "Ref '$Merge' does not exist." }
        $branch = (& git -C $root rev-parse --abbrev-ref HEAD).Trim()
        $dirty = & git -C $root status --porcelain --untracked-files=no
        if ($dirty -and -not $DryRun) { throw 'The working tree has uncommitted changes; commit or stash them before merging.' }
        if ($dirty) { Write-Warning 'The working tree has uncommitted changes; a real merge would refuse to start.' }

        Write-Host "> git merge $Merge   (into $branch)" -ForegroundColor Cyan
        if ($DryRun) { exit 0 }
        if (-not $Force) {
            $answer = Read-Host "Merge $Merge into $branch? [y/N]"
            if ($answer -notmatch '^(y|yes)$') { Write-Host 'Merge cancelled.'; exit 0 }
        }
        Invoke-Git @('merge', '--no-edit', $Merge)
        Write-Host "Merged $Merge into $branch. Run scripts\apply-patches next." -ForegroundColor Green
    }
    exit 0
} catch {
    Write-Error $_
    exit 1
}
