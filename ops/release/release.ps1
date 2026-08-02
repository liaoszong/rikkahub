[CmdletBinding()]
param(
    [string]$NotesPath = 'ops/update-site/RELEASE_NOTES.md',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\rikkahub-updates-ed25519",
    [string]$DeployTarget = 'rikkahub-deploy@updates.paleink.cc',
    [string]$GitRemote = 'origin',
    [ValidateSet('Full', 'Verify', 'Publish', 'Symbols')]
    [string]$Phase = 'Full',
    [switch]$UploadSymbols,
    [switch]$ConfirmRelease,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $repoRoot

function Invoke-Checked {
    param([scriptblock]$Command, [string]$FailureMessage)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

function Invoke-CapturedChecked {
    param([scriptblock]$Command, [string]$FailureMessage)
    $output = & $Command 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        $details = ($output | Out-String).Trim()
        if ($details) { throw "$FailureMessage`n$details" }
        throw $FailureMessage
    }
    return $output
}

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command is not available: $Name"
    }
}

function Invoke-TimedPhase {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [scriptblock]$Action
    )

    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $succeeded = $false
    Write-Host ''
    Write-Host "[$Name] Starting..." -ForegroundColor Cyan
    try {
        & $Action
        $succeeded = $true
    }
    finally {
        $stopwatch.Stop()
        $result = if ($succeeded) { 'Finished' } else { 'Failed' }
        $color = if ($succeeded) { 'DarkCyan' } else { 'Red' }
        Write-Host ("[$Name] $result in {0:mm\:ss}." -f $stopwatch.Elapsed) -ForegroundColor $color
    }
}

function Get-BuildInputFingerprint {
    $excludedPatterns = @(
        '^README(?:_[A-Z_]+)?\.md$',
        '^docs/',
        '^ops/update-site/RELEASE_NOTES\.md$',
        '^ops/update-site/CHANGELOG-[^/]+\.md$'
    )
    $trackedEntries = @(Invoke-CapturedChecked { git ls-files -s -z } 'Unable to enumerate tracked build inputs.' |
        ForEach-Object { $_ -split "`0" } |
        Where-Object { $_ } |
        ForEach-Object {
            $match = [regex]::Match($_, '^(?<mode>\d+)\s+(?<object>[0-9a-f]+)\s+\d+\t(?<path>.+)$')
            if (-not $match.Success) { throw "Unexpected git index entry: $_" }
            [pscustomobject]@{
                Mode = $match.Groups['mode'].Value
                Object = $match.Groups['object'].Value
                Path = $match.Groups['path'].Value.Replace('\', '/')
            }
        } |
        Where-Object {
            $candidate = $_.Path
            -not ($excludedPatterns | Where-Object { $candidate -match $_ })
        } |
        Sort-Object Path)
    $lines = foreach ($entry in $trackedEntries) {
        $relativePath = $entry.Path
        if ($relativePath -eq 'app/build.gradle.kts') {
            if (-not (Test-Path -LiteralPath $relativePath -PathType Leaf)) {
                throw "Tracked build input is missing: $relativePath"
            }
            # Version metadata changes the packaged artifact, but not whether the
            # source tree passed tests/Lint. Normalize only these two assignments
            # so a release bump does not invalidate a still-current quality gate.
            $normalizedBuild = Get-Content -Raw -LiteralPath $relativePath
            $normalizedBuild = [regex]::Replace($normalizedBuild, 'versionCode\s*=\s*\d+', 'versionCode = <release>')
            $normalizedBuild = [regex]::Replace(
                $normalizedBuild,
                'versionName\s*=\s*"[^"]+"',
                'versionName = "<release>"'
            )
            $hashBytes = [Text.Encoding]::UTF8.GetBytes($normalizedBuild)
            $contentIdentity = [Convert]::ToHexString(
                [Security.Cryptography.SHA256]::HashData($hashBytes)
            ).ToLowerInvariant()
        }
        else {
            # The index object identity covers normal files and gitlinks without
            # opening every file, making this both faster and submodule-safe.
            $contentIdentity = "$($entry.Mode):$($entry.Object)"
        }
        "$relativePath`0$contentIdentity"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Test-VerificationReceipt {
    param([string]$Path, [string]$Fingerprint)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        $receipt = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
        return $receipt.schemaVersion -eq 1 -and
            $receipt.buildInputSha256 -eq $Fingerprint -and
            $receipt.gate -eq 'verifyForkRelease'
    }
    catch {
        return $false
    }
}

function Get-LocalProperty {
    param([string]$Name)
    $line = Get-Content -LiteralPath 'local.properties' |
        Where-Object { $_ -match "^$([regex]::Escape($Name))=" } |
        Select-Object -First 1
    if (-not $line) { return $null }
    return (($line -split '=', 2)[1] -replace '\\:', ':' -replace '\\\\', '\')
}

function Get-RepositorySlug {
    param([string]$Remote)
    $url = (Invoke-CapturedChecked { git remote get-url $Remote } "Git remote not found: $Remote" |
        Out-String).Trim()
    $match = [regex]::Match($url, 'github\.com[/:](?<slug>[^/]+/[^/]+?)(?:\.git)?$')
    if (-not $match.Success) { throw "Cannot derive GitHub repository from remote URL: $url" }
    return $match.Groups['slug'].Value
}

Require-Command git

$buildFile = 'app/build.gradle.kts'
$receiptDirectory = '.release-cache'
$verificationReceiptPath = Join-Path $receiptDirectory 'verification.json'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$sdkRoot = Get-LocalProperty -Name 'sdk.dir'
if (-not $sdkRoot -or -not (Test-Path -LiteralPath $sdkRoot -PathType Container)) {
    throw 'local.properties must contain a valid sdk.dir.'
}
$env:ANDROID_SDK_ROOT = $sdkRoot

if ($Phase -eq 'Symbols') {
    Invoke-TimedPhase -Name 'Crashlytics symbols' -Action {
        Invoke-Checked {
            & '.\gradlew.bat' ':app:uploadCrashlyticsMappingFileRelease' '--configuration-cache'
        } 'Crashlytics symbol upload failed.'
    }
    return
}

if (-not (Test-Path -LiteralPath $NotesPath -PathType Leaf)) {
    throw "Release notes not found: $NotesPath"
}
$notes = Get-Content -Raw -LiteralPath $NotesPath
if ($notes -match '请在发布前|请填写' -or $notes.Trim().Length -lt 80) {
    throw "Release notes still contain template text: $NotesPath"
}

$branch = (Invoke-CapturedChecked { git branch --show-current } 'Unable to determine the current Git branch.' |
    Out-String).Trim()
if ($branch -ne 'master') { throw "Stable releases must run from master, current branch: $branch" }
if (Test-Path -LiteralPath '.git\MERGE_HEAD') { throw 'A merge is in progress.' }
if (Test-Path -LiteralPath '.git\rebase-merge') { throw 'A rebase is in progress.' }

$buildText = Get-Content -Raw -LiteralPath $buildFile
$versionCodeMatch = [regex]::Match($buildText, 'versionCode\s*=\s*(?<value>\d+)')
$versionNameMatch = [regex]::Match($buildText, 'versionName\s*=\s*"(?<value>[^"]+)"')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw 'Unable to read versionCode/versionName from app/build.gradle.kts.'
}
$currentCode = [int]$versionCodeMatch.Groups['value'].Value
$currentVersion = $versionNameMatch.Groups['value'].Value
$paleMatch = [regex]::Match($currentVersion, '^(?<base>\d+\.\d+\.\d+)-pale\.(?<revision>\d+)$')
if (-not $paleMatch.Success) {
    throw "Current version does not follow the PaleInk version scheme: $currentVersion"
}

$livePreview = $null
if ($Phase -eq 'Full') {
    $livePreview = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' `
        -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 15
    $resumeApk = Join-Path $receiptDirectory "rikkahub-$currentVersion-universal.apk"
    $resumeReceipt = Join-Path $receiptDirectory "prepared-$currentVersion.json"
    if ($currentCode -eq ([int]$livePreview.versionCode + 1) -and
        (Test-Path -LiteralPath $resumeApk -PathType Leaf) -and
        (Test-Path -LiteralPath $resumeReceipt -PathType Leaf)) {
        $Phase = 'Publish'
        Write-Host "Detected prepared $currentVersion after an interrupted release; resuming Publish without rebuilding." `
            -ForegroundColor Yellow
    }
}

$normalizedNotesPath = $NotesPath.Replace('\', '/').TrimStart('./')
$status = @(Invoke-CapturedChecked {
    git status --porcelain=v1 --untracked-files=all
} 'Unable to inspect the Git working tree.')
$allowedPreReleasePaths = @($normalizedNotesPath)
if ($Phase -eq 'Publish') {
    $allowedPreReleasePaths += @(
        $buildFile.Replace('\', '/'),
        "ops/update-site/CHANGELOG-$currentVersion.md"
    )
}
$unexpectedChanges = @($status | ForEach-Object {
    $line = $_.ToString()
    if ($line.Length -lt 4) { return }
    $path = $line.Substring(3)
    if ($path.Contains(' -> ')) { $path = ($path -split ' -> ', 2)[1] }
    $path = $path.Trim('"').Replace('\', '/')
    if ($path -notin $allowedPreReleasePaths) { $line }
})
if ($unexpectedChanges.Count -gt 0) {
    $details = ($unexpectedChanges | ForEach-Object { "  $_" }) -join "`n"
    throw "Release verification requires a clean code tree. Commit non-release changes first:`n$details"
}

$repoSlug = $null
if ($Phase -ne 'Verify') {
    Require-Command gh
    Require-Command ssh
    Require-Command scp
    Invoke-Checked { gh auth status *> $null } 'GitHub CLI is not authenticated.'
    $repoSlug = Get-RepositorySlug -Remote $GitRemote
    if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
        throw "Deployment key is missing. Run ops/release/setup-release-access.ps1 first: $IdentityFile"
    }
    Invoke-TimedPhase -Name 'Deployment preflight' -Action {
        Invoke-Checked {
            ssh -i $IdentityFile -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 `
                $DeployTarget 'test -w /srv/rikkahub-updates/staging && test -w /srv/rikkahub-updates/public && test -w /srv/rikkahub-updates/public/api/v1'
        } 'The restricted update-site deployment account is not ready.'
    }
}

$buildInputFingerprint = Get-BuildInputFingerprint
$hasValidVerification = Test-VerificationReceipt -Path $verificationReceiptPath -Fingerprint $buildInputFingerprint
if ($Phase -in @('Full', 'Verify')) {
    if ($hasValidVerification) {
        Write-Host 'Reusing the successful repository gate for unchanged build inputs.' -ForegroundColor Green
    }
    elseif (-not $DryRun) {
        Invoke-TimedPhase -Name 'Repository tests and Lint' -Action {
            Invoke-Checked {
                & '.\gradlew.bat' 'verifyForkRelease' '--parallel' '--configuration-cache'
            } 'Full tests or Lint failed.'
        }
        [IO.Directory]::CreateDirectory($receiptDirectory) | Out-Null
        $receipt = [ordered]@{
            schemaVersion = 1
            buildInputSha256 = $buildInputFingerprint
            gate = 'verifyForkRelease'
            verifiedAt = [DateTime]::UtcNow.ToString('o')
            gitCommit = (Invoke-CapturedChecked { git rev-parse HEAD } 'Unable to record the verified commit.' | Out-String).Trim()
        }
        [IO.File]::WriteAllText($verificationReceiptPath, ($receipt | ConvertTo-Json), $utf8NoBom)
    }
    else {
        Write-Host 'Dry run: repository verification is required for these build inputs.' -ForegroundColor Yellow
    }
    if ($Phase -eq 'Verify') {
        if ($DryRun -and -not $hasValidVerification) {
            Write-Host 'Verify dry run passed; the repository gate would run on a real invocation.' -ForegroundColor Green
        }
        else {
            Write-Host 'Repository verification is current. No release files or remote state were changed.' -ForegroundColor Green
        }
        return
    }
}

$live = if ($livePreview) {
    $livePreview
}
else {
    Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' `
        -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 15
}

if ($Phase -eq 'Full') {
    if ([int]$live.versionCode -ne $currentCode) {
        throw "Local versionCode $currentCode does not match live versionCode $($live.versionCode). Use -Phase Publish only to resume an already prepared release."
    }
    $targetCode = $currentCode + 1
    $targetVersion = "$($paleMatch.Groups['base'].Value)-pale.$([int]$paleMatch.Groups['revision'].Value + 1)"
    $archiveNotesPath = "ops/update-site/CHANGELOG-$targetVersion.md"
    if (Test-Path -LiteralPath $archiveNotesPath) { throw "Archived notes already exist: $archiveNotesPath" }
}
else {
    $targetCode = $currentCode
    $targetVersion = $currentVersion
    $archiveNotesPath = "ops/update-site/CHANGELOG-$targetVersion.md"
    if (-not (Test-Path -LiteralPath $archiveNotesPath -PathType Leaf)) {
        throw "Prepared release notes are missing: $archiveNotesPath"
    }
    $notes = Get-Content -Raw -LiteralPath $archiveNotesPath
}
$tag = "v$targetVersion"
$preparedApk = Join-Path $receiptDirectory "rikkahub-$targetVersion-universal.apk"
$preparedReceiptPath = Join-Path $receiptDirectory "prepared-$targetVersion.json"

Write-Host ''
Write-Host "Release phase: $Phase" -ForegroundColor Cyan
Write-Host "Target: $targetVersion ($targetCode)"
Write-Host "GitHub: https://github.com/$repoSlug/releases/tag/$tag"
Write-Host 'Update site: https://updates.paleink.cc/'

if ($DryRun) {
    Write-Host 'Dry run passed. No files, Git refs, releases, or servers were changed.' -ForegroundColor Green
    return
}

if (-not $ConfirmRelease) {
    $confirmation = Read-Host "Type RELEASE $tag to continue"
    if ($confirmation -ne "RELEASE $tag") { throw 'Release cancelled.' }
}
else {
    Write-Host "Release explicitly confirmed by -ConfirmRelease: $tag" -ForegroundColor Yellow
}

if ($Phase -eq 'Full') {
    $newBuildText = [regex]::Replace($buildText, 'versionCode\s*=\s*\d+', "versionCode = $targetCode", 1)
    $newBuildText = [regex]::Replace($newBuildText, 'versionName\s*=\s*"[^"]+"', "versionName = `"$targetVersion`"", 1)
    [IO.File]::WriteAllText((Resolve-Path $buildFile), $newBuildText, $utf8NoBom)
    Copy-Item -LiteralPath $NotesPath -Destination $archiveNotesPath
    try {
        Invoke-TimedPhase -Name 'Signed release APK' -Action {
            Invoke-Checked {
                & '.\gradlew.bat' ':app:assembleRelease' '-PpaleinkUniversalOnly=true' `
                    '-x' ':app:uploadCrashlyticsMappingFileRelease' '--configuration-cache'
            } 'Deterministic release build failed.'
        }
        $apk = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
        Invoke-TimedPhase -Name 'APK verification' -Action {
            Invoke-Checked {
                & 'ops/release/verify-android-16kb.ps1' -ApkPath $apk -SdkRoot $sdkRoot
            } 'Release APK failed the 16 KB page-alignment gate.'
        }
        [IO.Directory]::CreateDirectory($receiptDirectory) | Out-Null
        Copy-Item -LiteralPath $apk -Destination $preparedApk -Force
        $preparedReceipt = [ordered]@{
            schemaVersion = 1
            version = $targetVersion
            versionCode = $targetCode
            apkSha256 = (Get-FileHash -LiteralPath $preparedApk -Algorithm SHA256).Hash.ToLowerInvariant()
            buildInputSha256 = $buildInputFingerprint
            preparedAt = [DateTime]::UtcNow.ToString('o')
        }
        [IO.File]::WriteAllText($preparedReceiptPath, ($preparedReceipt | ConvertTo-Json), $utf8NoBom)
    }
    catch {
        [IO.File]::WriteAllText((Resolve-Path $buildFile), $buildText, $utf8NoBom)
        if (Test-Path -LiteralPath $archiveNotesPath -PathType Leaf) {
            Remove-Item -LiteralPath $archiveNotesPath -Force
        }
        if (Test-Path -LiteralPath $preparedApk -PathType Leaf) {
            Remove-Item -LiteralPath $preparedApk -Force
        }
        if (Test-Path -LiteralPath $preparedReceiptPath -PathType Leaf) {
            Remove-Item -LiteralPath $preparedReceiptPath -Force
        }
        throw
    }
}

if (-not (Test-Path -LiteralPath $preparedApk -PathType Leaf) -or
    -not (Test-Path -LiteralPath $preparedReceiptPath -PathType Leaf)) {
    throw "Prepared artifact is missing. Run the Full phase before Publish: $preparedApk"
}
$preparedReceipt = Get-Content -Raw -LiteralPath $preparedReceiptPath | ConvertFrom-Json
$actualPreparedHash = (Get-FileHash -LiteralPath $preparedApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($preparedReceipt.version -ne $targetVersion -or
    [int]$preparedReceipt.versionCode -ne $targetCode -or
    $preparedReceipt.apkSha256 -ne $actualPreparedHash) {
    throw 'Prepared release receipt does not match the requested version or APK.'
}

Invoke-TimedPhase -Name 'Git commit and tag' -Action {
    $releasePaths = @($buildFile, $archiveNotesPath, $normalizedNotesPath)
    git add -- $releasePaths
    if ($LASTEXITCODE -ne 0) { throw 'Failed to stage the release allowlist.' }
    $stagedPaths = @(Invoke-CapturedChecked { git diff --cached --name-only } 'Unable to inspect staged release files.')
    $allowedStagePaths = $releasePaths | ForEach-Object { $_.Replace('\', '/') } | Sort-Object -Unique
    $unexpectedStaged = @($stagedPaths | Where-Object { $_.ToString().Replace('\', '/') -notin $allowedStagePaths })
    if ($unexpectedStaged.Count -gt 0) { throw "Unexpected staged files: $($unexpectedStaged -join ', ')" }
    if ($stagedPaths.Count -gt 0) {
        Invoke-Checked { git commit -m "release: $tag" } 'Failed to create the release commit.'
    }
    $existingTag = (Invoke-CapturedChecked { git tag --list $tag } 'Unable to inspect the release tag.' | Out-String).Trim()
    if (-not $existingTag) {
        Invoke-Checked { git tag -a $tag -m "PaleInk RikkaHub $targetVersion" } 'Failed to create the release tag.'
    }
    else {
        $tagCommit = (Invoke-CapturedChecked { git rev-list -n 1 $tag } 'Unable to resolve the release tag.' | Out-String).Trim()
        $headCommit = (Invoke-CapturedChecked { git rev-parse HEAD } 'Unable to resolve HEAD.' | Out-String).Trim()
        if ($tagCommit -ne $headCommit) { throw "Existing tag $tag does not point to HEAD." }
    }
    Invoke-Checked { git push $GitRemote $branch } 'Failed to push the release commit.'
    Invoke-Checked { git push $GitRemote $tag } 'Failed to push the release tag.'
}

Invoke-TimedPhase -Name 'GitHub draft and asset' -Action {
    gh release view $tag --repo $repoSlug *> $null
    if ($LASTEXITCODE -eq 0) {
        Invoke-Checked { gh release upload $tag $preparedApk --repo $repoSlug --clobber } 'Failed to upload the GitHub Release asset.'
    }
    else {
        Invoke-Checked {
            gh release create $tag $preparedApk --repo $repoSlug --title "PaleInk RikkaHub $targetVersion" `
                --notes-file $archiveNotesPath --draft --verify-tag
        } 'Failed to create the draft GitHub Release.'
    }
}

Invoke-TimedPhase -Name 'Update site publication' -Action {
    & 'ops/update-site/publish-update.ps1' -ApkPath $preparedApk -Version $targetVersion `
        -VersionCode $targetCode -Changelog $notes -Abi universal `
        -SshTarget $DeployTarget -IdentityFile $IdentityFile
}
Invoke-TimedPhase -Name 'Public release verification' -Action {
    Invoke-Checked { gh release edit $tag --repo $repoSlug --draft=false } `
        'The update site is live, but the GitHub Release could not be promoted from draft.'
    $published = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' `
        -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 15
    if ([int]$published.versionCode -ne $targetCode -or $published.downloads[0].sha256 -ne $actualPreparedHash) {
        throw 'Post-release feed verification did not match the prepared artifact.'
    }
    $downloadHead = Invoke-WebRequest -Uri $published.downloads[0].url -Method Head -TimeoutSec 20
    if ($downloadHead.StatusCode -ne 200) { throw 'Published APK is not publicly downloadable.' }
}

Write-Host ''
Write-Host "Released $tag successfully." -ForegroundColor Green
Write-Host "GitHub: https://github.com/$repoSlug/releases/tag/$tag"
Write-Host 'Update site: https://updates.paleink.cc/'
if ($UploadSymbols) {
    Write-Host 'Uploading Crashlytics symbols because -UploadSymbols was requested.' -ForegroundColor Yellow
    & $PSCommandPath -Phase Symbols
}
else {
    Write-Host 'Crashlytics symbols were left for the independent Symbols phase.' -ForegroundColor DarkGray
}
