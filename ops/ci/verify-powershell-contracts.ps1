[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$scripts = @(
    'ops/ci/verify-powershell-contracts.ps1'
    'ops/release/release.ps1'
    'ops/release/setup-release-access.ps1'
    'ops/release/verify-android-16kb.ps1'
    'ops/update-site/publish-update.ps1'
)

function Assert-Contract {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

foreach ($relativePath in $scripts) {
    $path = Join-Path $repoRoot $relativePath
    Assert-Contract (Test-Path -LiteralPath $path -PathType Leaf) "Required PowerShell script is missing: $relativePath"

    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile(
        $path,
        [ref]$tokens,
        [ref]$parseErrors
    )
    if ($parseErrors.Count -gt 0) {
        $details = ($parseErrors | ForEach-Object {
            "{0}:{1}:{2}: {3}" -f $relativePath, $_.Extent.StartLineNumber, $_.Extent.StartColumnNumber, $_.Message
        }) -join [Environment]::NewLine
        throw "PowerShell parser errors detected:$([Environment]::NewLine)$details"
    }

    Write-Host "PowerShell parse OK: $relativePath"
}

$releaseText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'ops/release/release.ps1')
$dryRunGuard = $releaseText.IndexOf('if ($DryRun)', [StringComparison]::Ordinal)
Assert-Contract ($dryRunGuard -ge 0) 'release.ps1 must expose a DryRun guard.'
Assert-Contract ($releaseText.Contains("'verifyForkRelease'")) 'release.ps1 must use the repository verification gate.'
Assert-Contract ($releaseText.Contains('[switch]$ConfirmRelease')) `
    'release.ps1 must expose an explicit non-interactive confirmation switch.'
Assert-Contract ($releaseText.Contains('if (-not $ConfirmRelease)')) `
    'release.ps1 must keep interactive confirmation unless explicitly bypassed.'
Assert-Contract ($releaseText.Contains("'--configuration-cache'")) `
    'release.ps1 must reuse the cache-safe repository verification graph.'
foreach ($mutation in @('git add --', 'git commit -m', 'git tag -a', 'git push')) {
    $mutationOffset = $releaseText.IndexOf($mutation, [StringComparison]::Ordinal)
    Assert-Contract ($mutationOffset -gt $dryRunGuard) "release.ps1 mutation must remain after the DryRun guard: $mutation"
}

$publisherText = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'ops/update-site/publish-update.ps1')
$supportsShouldProcess = $publisherText.IndexOf('[CmdletBinding(SupportsShouldProcess)]', [StringComparison]::Ordinal)
$shouldProcessGuard = $publisherText.IndexOf('$PSCmdlet.ShouldProcess(', [StringComparison]::Ordinal)
Assert-Contract ($supportsShouldProcess -ge 0) 'publish-update.ps1 must declare SupportsShouldProcess for -WhatIf.'
Assert-Contract ($shouldProcessGuard -ge 0) 'publish-update.ps1 must guard publication with ShouldProcess.'
foreach ($mutation in @('ssh ', 'scp ')) {
    $mutationOffset = $publisherText.IndexOf($mutation, $shouldProcessGuard, [StringComparison]::Ordinal)
    Assert-Contract ($mutationOffset -gt $shouldProcessGuard) "publish-update.ps1 remote mutation must remain after ShouldProcess: $mutation"
}

$dailyWorkflow = Get-Content -Raw -LiteralPath (Join-Path $repoRoot '.github/workflows/daily-build.yml')
Assert-Contract ($dailyWorkflow.Contains('gradlew verifyForkRelease')) 'Daily builds must use the repository verification gate.'
Assert-Contract ($dailyWorkflow.Contains('verifyForkRelease --stacktrace --no-daemon --configuration-cache')) `
    'Daily verification must reuse the cache-safe repository verification graph.'

$qualityWorkflow = Get-Content -Raw -LiteralPath (Join-Path $repoRoot '.github/workflows/quality-gate.yml')
Assert-Contract ($qualityWorkflow.Contains('verifyForkRelease --stacktrace --no-daemon --configuration-cache')) `
    'The required quality gate must reuse the cache-safe repository verification graph.'
$verifyJobOffset = $dailyWorkflow.IndexOf("  verify:`n", [StringComparison]::Ordinal)
$publishJobOffset = $dailyWorkflow.IndexOf("  publish:`n", [StringComparison]::Ordinal)
if ($verifyJobOffset -lt 0 -or $publishJobOffset -lt 0) {
    # Accommodate a Windows checkout with CRLF without weakening the contract.
    $verifyJobOffset = $dailyWorkflow.IndexOf("  verify:`r`n", [StringComparison]::Ordinal)
    $publishJobOffset = $dailyWorkflow.IndexOf("  publish:`r`n", [StringComparison]::Ordinal)
}
Assert-Contract ($verifyJobOffset -ge 0 -and $publishJobOffset -gt $verifyJobOffset) `
    'Daily builds must verify before entering the privileged publish job.'
$verifyJob = $dailyWorkflow.Substring($verifyJobOffset, $publishJobOffset - $verifyJobOffset)
$publishJob = $dailyWorkflow.Substring($publishJobOffset)
Assert-Contract (-not $verifyJob.Contains('secrets.')) 'The Daily Build verify job must not receive repository secrets.'
Assert-Contract ($verifyJob.Contains('contents: read')) 'The Daily Build verify job must be read-only.'
Assert-Contract ($publishJob.Contains('contents: write')) 'Only the Daily Build publish job may write release contents.'
Assert-Contract ($publishJob.Contains('needs: [check, verify]')) 'Nightly publication must depend on successful verification.'
Assert-Contract ($publishJob.Contains("github.ref == 'refs/heads/master'")) `
    'Nightly publication must be restricted to the master branch.'
Assert-Contract ($publishJob.Contains("github.event_name == 'schedule'") -and
    $publishJob.Contains("github.event_name == 'workflow_dispatch'")) `
    'Nightly publication must only run for schedule or explicit manual dispatch.'
$secretOffset = $dailyWorkflow.IndexOf('secrets.', [StringComparison]::Ordinal)
Assert-Contract ($secretOffset -gt $publishJobOffset) 'Daily Build secrets must only appear inside the publish job.'
Assert-Contract ($publishJob.Contains('KEY_BASE64: ${{ secrets.KEY_BASE64 }}')) `
    'Nightly keystore secret must enter the shell through the step environment.'
Assert-Contract ($publishJob.Contains('SIGNING_CONFIG: ${{ secrets.SIGNING_CONFIG }}')) `
    'Nightly signing config must enter the shell through the step environment.'
Assert-Contract ($publishJob.Contains('GOOGLE_SERVICES_JSON: ${{ secrets.GOOGLE_SERVICES_JSON }}')) `
    'Nightly Google Services JSON must enter the shell through the step environment.'
foreach ($safeWrite in @(
    'printf ''%s'' "$KEY_BASE64" | base64 -d > app/app.key'
    'printf ''%s'' "$SIGNING_CONFIG" > local.properties'
    'printf ''%s'' "$GOOGLE_SERVICES_JSON" > app/google-services.json'
)) {
    Assert-Contract ($publishJob.Contains($safeWrite)) `
        "Nightly build files must be written without shell re-interpretation: $safeWrite"
}

Write-Host 'PowerShell release contracts verified.' -ForegroundColor Green
