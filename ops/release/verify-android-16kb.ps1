[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
    throw 'Android SDK root is required to verify 16 KB compatibility.'
}

$buildTools = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object { $_.Name -match '^\d+(?:\.\d+)*$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw 'Android SDK build-tools were not found.'
}
$zipalign = if ($IsWindows) {
    Join-Path $buildTools.FullName 'zipalign.exe'
} else {
    Join-Path $buildTools.FullName 'zipalign'
}
if (-not (Test-Path -LiteralPath $zipalign -PathType Leaf)) {
    throw 'zipalign was not found in Android SDK build-tools.'
}

$ndk = Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'ndk') -Directory |
    Where-Object { $_.Name -match '^\d+(?:\.\d+)*$' } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $ndk) {
    throw 'An Android NDK installation is required to inspect ELF program headers.'
}
$hostTag = if ($IsWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
$readelfName = if ($IsWindows) { 'llvm-readelf.exe' } else { 'llvm-readelf' }
$readelf = if ($ndk) {
    Join-Path $ndk.FullName "toolchains/llvm/prebuilt/$hostTag/bin/$readelfName"
}
if (-not (Test-Path -LiteralPath $readelf -PathType Leaf)) {
    throw 'llvm-readelf was not found in the latest installed Android NDK.'
}

$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$workDir = Join-Path $systemTemp "rikkahub-16kb-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $workDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = $null
try {
    $archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
    $nativeEntries = @($archive.Entries | Where-Object {
        $_.FullName -like 'lib/arm64-v8a/*.so'
    })
    if ($nativeEntries.Count -eq 0) {
        throw 'Release APK does not contain any arm64 native libraries.'
    }

    $misaligned = @()
    foreach ($entry in $nativeEntries) {
        $libraryPath = Join-Path $workDir ([IO.Path]::GetFileName($entry.FullName))
        $input = $entry.Open()
        $output = [IO.File]::Create($libraryPath)
        try {
            $input.CopyTo($output)
        } finally {
            $output.Dispose()
            $input.Dispose()
        }

        $headers = @(& $readelf -lW $libraryPath 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "llvm-readelf failed for $($entry.FullName): $($headers -join [Environment]::NewLine)"
        }
        $loadLines = @($headers | Where-Object { $_.ToString() -match '^\s*LOAD\s' })
        $alignments = @($loadLines | ForEach-Object {
            if ($_.ToString() -match '0x(?<alignment>[0-9a-fA-F]+)\s*$') {
                [Convert]::ToInt64($Matches['alignment'], 16)
            }
        })
        if ($loadLines.Count -eq 0 -or $alignments.Count -ne $loadLines.Count) {
            throw "Unable to read every LOAD alignment from $($entry.FullName)."
        }
        $minimumAlignment = [long](($alignments | Measure-Object -Minimum).Minimum)
        if ($minimumAlignment -lt 16384) {
            $misaligned += "$($entry.FullName) (minimum LOAD alignment: $minimumAlignment bytes)"
        }
    }
    if ($misaligned.Count -gt 0) {
        throw "Native libraries are not 16 KB compatible:`n  $($misaligned -join "`n  ")"
    }
} finally {
    if ($archive) { $archive.Dispose() }
    $resolvedWorkDir = [IO.Path]::GetFullPath($workDir)
    if ($resolvedWorkDir.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedWorkDir)) {
        Remove-Item -LiteralPath $resolvedWorkDir -Recurse -Force
    }
}

& $zipalign -c -P 16 4 $resolvedApk
if ($LASTEXITCODE -ne 0) {
    throw 'APK failed the 16 KB ZIP alignment check.'
}
Write-Host "16 KB native and APK alignment verified: $resolvedApk" -ForegroundColor Green
