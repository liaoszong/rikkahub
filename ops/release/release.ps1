[CmdletBinding()]
param(
    [string]$NotesPath = 'ops/update-site/RELEASE_NOTES.md',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\rikkahub-updates-ed25519",
    [string]$DeployTarget = 'rikkahub-deploy@updates.paleink.cc',
    [string]$GitRemote = 'origin',
    [switch]$SkipSymbolsUpload,
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
Require-Command gh
Require-Command ssh
Require-Command scp

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

Invoke-Checked { gh auth status *> $null } 'GitHub CLI is not authenticated.'
$repoSlug = Get-RepositorySlug -Remote $GitRemote

$buildFile = 'app/build.gradle.kts'
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
$nextCode = $currentCode + 1
$nextVersion = "$($paleMatch.Groups['base'].Value)-pale.$([int]$paleMatch.Groups['revision'].Value + 1)"
$tag = "v$nextVersion"
$archiveNotesPath = "ops/update-site/CHANGELOG-$nextVersion.md"

$live = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' `
    -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 15
if ([int]$live.versionCode -ne $currentCode) {
    throw "Local versionCode $currentCode does not match live versionCode $($live.versionCode)."
}
$localTag = (Invoke-CapturedChecked { git tag --list $tag } 'Unable to inspect local Git tags.' |
    Out-String).Trim()
if ($localTag) { throw "Tag already exists locally: $tag" }
$remoteTag = (Invoke-CapturedChecked {
    git ls-remote --tags $GitRemote "refs/tags/$tag"
} 'Unable to inspect remote Git tags. Check the network and GitHub authentication.' | Out-String).Trim()
if ($remoteTag) { throw "Tag already exists remotely: $tag" }
if (Test-Path -LiteralPath $archiveNotesPath) { throw "Archived notes already exist: $archiveNotesPath" }

if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
    throw "Deployment key is missing. Run ops/release/setup-release-access.ps1 first: $IdentityFile"
}
Invoke-Checked {
    ssh -i $IdentityFile -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 `
        $DeployTarget 'test -w /srv/rikkahub-updates/staging && test -w /srv/rikkahub-updates/public && test -w /srv/rikkahub-updates/public/api/v1'
} 'The restricted update-site deployment account is not ready.'

$status = @(Invoke-CapturedChecked {
    git status --porcelain=v1 --untracked-files=all
} 'Unable to inspect the Git working tree.')
$normalizedNotesPath = $NotesPath.Replace('\', '/').TrimStart('./')
$unexpectedChanges = @($status | ForEach-Object {
    $line = $_.ToString()
    if ($line.Length -lt 4) { return }
    $path = $line.Substring(3)
    if ($path.Contains(' -> ')) { $path = ($path -split ' -> ', 2)[1] }
    $path = $path.Trim('"').Replace('\', '/')
    if ($path -ne $normalizedNotesPath) { $line }
})
if ($unexpectedChanges.Count -gt 0) {
    $details = ($unexpectedChanges | ForEach-Object { "  $_" }) -join "`n"
    throw "Release requires a clean tree except for $NotesPath. Unexpected changes:`n$details"
}
Write-Host ''
Write-Host "Release plan: $currentVersion ($currentCode) -> $nextVersion ($nextCode)" -ForegroundColor Cyan
Write-Host "GitHub: https://github.com/$repoSlug/releases/tag/$tag"
Write-Host "Update site: https://updates.paleink.cc/"
Write-Host ''
Write-Host 'Allowed pre-release changes:' -ForegroundColor Yellow
if ($status.Count -gt 0) { $status | ForEach-Object { Write-Host "  $_" } } else { Write-Host '  (none)' }

if ($DryRun) {
    Write-Host ''
    Write-Host 'Dry run passed. No files, Git refs, releases, or servers were changed.' -ForegroundColor Green
    return
}

$confirmation = Read-Host "Type RELEASE $tag to continue"
if ($confirmation -ne "RELEASE $tag") { throw 'Release cancelled.' }

$newBuildText = [regex]::Replace($buildText, 'versionCode\s*=\s*\d+', "versionCode = $nextCode", 1)
$newBuildText = [regex]::Replace($newBuildText, 'versionName\s*=\s*"[^"]+"', "versionName = `"$nextVersion`"", 1)
[IO.File]::WriteAllText((Resolve-Path $buildFile), $newBuildText, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $NotesPath -Destination $archiveNotesPath

$sdkRoot = Get-LocalProperty -Name 'sdk.dir'
if (-not $sdkRoot -or -not (Test-Path -LiteralPath $sdkRoot -PathType Container)) {
    throw 'local.properties must contain a valid sdk.dir.'
}
$env:ANDROID_SDK_ROOT = $sdkRoot

Write-Host ''
Write-Host 'Running deterministic release gate...' -ForegroundColor Cyan
Invoke-Checked {
    & '.\gradlew.bat' 'verifyForkRelease' ':app:assembleRelease' `
        '-PpaleinkUniversalOnly=true' `
        '-x' ':app:uploadCrashlyticsMappingFileRelease' `
        '--parallel' '--configuration-cache'
} 'Full tests, Lint, or deterministic release build failed.'

$apk = (Resolve-Path 'app/build/outputs/apk/release/app-release.apk').Path
Invoke-Checked {
    & 'ops/release/verify-android-16kb.ps1' -ApkPath $apk -SdkRoot $sdkRoot
} 'Release APK failed the 16 KB page-alignment gate.'

$releaseTemp = Join-Path ([IO.Path]::GetTempPath()) "rikkahub-github-$nextCode"
$releaseAsset = Join-Path $releaseTemp "rikkahub-$nextVersion-universal.apk"
New-Item -ItemType Directory -Path $releaseTemp -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination $releaseAsset -Force

try {
    Write-Host ''
    Write-Host 'Committing and tagging the verified source...' -ForegroundColor Cyan
    Invoke-Checked {
        git add -- $buildFile $archiveNotesPath $NotesPath
    } 'Failed to stage the release allowlist.'
    $allowedStagePaths = @($buildFile, $archiveNotesPath, $normalizedNotesPath) |
        ForEach-Object { $_.Replace('\', '/') } |
        Sort-Object -Unique
    $stagedPaths = @(Invoke-CapturedChecked {
        git diff --cached --name-only
    } 'Unable to inspect staged release files.')
    $unexpectedStaged = @($stagedPaths | Where-Object { $_.ToString().Replace('\', '/') -notin $allowedStagePaths })
    if ($unexpectedStaged.Count -gt 0) {
        throw "Unexpected files reached the release index: $($unexpectedStaged -join ', ')"
    }
    Invoke-Checked { git commit -m "release: $tag" } 'Failed to create the release commit.'
    Invoke-Checked { git tag -a $tag -m "PaleInk RikkaHub $nextVersion" } 'Failed to create the release tag.'
    Invoke-Checked { git push $GitRemote $branch } 'Failed to push the release commit.'
    Invoke-Checked { git push $GitRemote $tag } 'Failed to push the release tag.'

    Write-Host ''
    Write-Host 'Creating a draft GitHub Release...' -ForegroundColor Cyan
    Invoke-Checked {
        gh release create $tag $releaseAsset --repo $repoSlug --title "PaleInk RikkaHub $nextVersion" `
            --notes-file $archiveNotesPath --draft --verify-tag
    } 'Failed to create the draft GitHub Release.'

    Write-Host ''
    Write-Host 'Publishing the signed APK and update feed...' -ForegroundColor Cyan
    & 'ops/update-site/publish-update.ps1' -ApkPath $apk -Version $nextVersion `
        -VersionCode $nextCode -Changelog $notes -Abi universal `
        -SshTarget $DeployTarget -IdentityFile $IdentityFile

    Invoke-Checked {
        gh release edit $tag --repo $repoSlug --draft=false
    } 'The update site is live, but the GitHub Release could not be promoted from draft.'

    if (-not $SkipSymbolsUpload) {
        Write-Host ''
        Write-Host 'Uploading Crashlytics symbols as an independent step...' -ForegroundColor Cyan
        try {
            Invoke-Checked {
                & '.\gradlew.bat' ':app:uploadCrashlyticsMappingFileRelease' '--configuration-cache'
            } 'Crashlytics symbol upload failed.'
        } catch {
            Write-Warning "Release artifacts are already valid; symbols can be retried later. $($_.Exception.Message)"
        }
    }

    $published = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' `
        -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 15
    if ([int]$published.versionCode -ne $nextCode) {
        throw "Post-release verification returned versionCode $($published.versionCode), expected $nextCode."
    }
    $downloadHead = Invoke-WebRequest -Uri $published.downloads[0].url -Method Head -TimeoutSec 20
    if ($downloadHead.StatusCode -ne 200) { throw 'Published APK is not publicly downloadable.' }

    Write-Host ''
    Write-Host "Released $tag successfully." -ForegroundColor Green
    Write-Host "GitHub: https://github.com/$repoSlug/releases/tag/$tag"
    Write-Host 'Update site: https://updates.paleink.cc/'
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $resolvedTemp = [IO.Path]::GetFullPath($releaseTemp)
    if ($resolvedTemp.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
