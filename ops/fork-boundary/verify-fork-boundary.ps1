[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")),
    [switch]$Describe
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$manifestPath = Join-Path $PSScriptRoot "boundary.json"
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json

if ($manifest.schemaVersion -ne 1) {
    throw "Unsupported fork-boundary schema: $($manifest.schemaVersion)"
}

$base = [string]$manifest.upstreamBase
& git -C $root cat-file -e "$base`^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Pinned upstream baseline is unavailable: $base"
}

$changedPaths = @(
    & git -C $root diff --name-only --diff-filter=ACDMRTUXB $base -- .
)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to enumerate fork changes from $base"
}

$ownedPrefixes = @($manifest.forkOwnedPrefixes | ForEach-Object { [string]$_ })
$ownedPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($path in $manifest.forkOwnedPaths) {
    [void]$ownedPaths.Add([string]$path)
}

$integrationPaths = [System.Collections.Generic.List[string]]::new()
foreach ($rawPath in $changedPaths) {
    $path = ([string]$rawPath).Replace("\", "/").Trim()
    if ([string]::IsNullOrWhiteSpace($path)) { continue }

    $isOwned = $ownedPaths.Contains($path)
    if (-not $isOwned) {
        foreach ($prefix in $ownedPrefixes) {
            if ($path.StartsWith($prefix, [System.StringComparison]::Ordinal)) {
                $isOwned = $true
                break
            }
        }
    }
    if (-not $isOwned) {
        $integrationPaths.Add($path)
    }
}

$integrationArray = $integrationPaths.ToArray()
[Array]::Sort($integrationArray, [System.StringComparer]::Ordinal)
$payload = [string]::Join("`n", $integrationArray)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $hashBytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($payload))
} finally {
    $sha256.Dispose()
}
$hash = ([System.BitConverter]::ToString($hashBytes)).Replace("-", "").ToLowerInvariant()

if ($Describe) {
    [pscustomobject]@{
        upstreamBase = $base
        integrationPathCount = $integrationArray.Count
        integrationPathSetSha256 = $hash
        integrationPaths = $integrationArray
    } | ConvertTo-Json -Depth 4
    exit 0
}

$expectedCount = [int]$manifest.knownIntegrationPathCount
$expectedHash = [string]$manifest.knownIntegrationPathSetSha256
if ($integrationArray.Count -ne $expectedCount -or $hash -ne $expectedHash) {
    $preview = ($integrationArray | Select-Object -First 25) -join "`n  "
    throw @"
Fork boundary changed without an explicit baseline update.
Expected integration paths: $expectedCount / $expectedHash
Actual integration paths:   $($integrationArray.Count) / $hash
Current integration touchpoints (first 25):
  $preview

If this is an intentional upstream sync or new adapter touchpoint, update
ops/fork-boundary/boundary.json in a dedicated boundary commit after review.
"@
}

Write-Host "Fork boundary verified: $expectedCount integration touchpoints; baseline $base"
