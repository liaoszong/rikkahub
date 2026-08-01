[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ApkPath,

    [Parameter(Mandatory)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$')]
    [string]$Version,

    [Parameter(Mandatory)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$VersionCode,

    [Parameter(Mandatory)]
    [string]$Changelog,

    [ValidateSet('arm64-v8a', 'x86_64', 'universal')]
    [string]$Abi = 'arm64-v8a',

    [string]$SshTarget = 'root@updates.paleink.cc',

    [int]$SshPort = 22,

    [ValidateScript({ -not $_ -or (Test-Path -LiteralPath $_ -PathType Leaf) })]
    [string]$IdentityFile
)

$ErrorActionPreference = 'Stop'
$expectedApplicationId = 'me.rerere.rikkahub'
$expectedSignerSha256 = 'df8c1f92039b19cfbdd72491e0058eb4682ff75f99cbbe32450fe9ea4d408520'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedIdentity = if ($IdentityFile) { (Resolve-Path -LiteralPath $IdentityFile).Path } else { $null }

$androidSdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if (-not $androidSdkRoot -or -not (Test-Path -LiteralPath $androidSdkRoot -PathType Container)) {
    throw 'ANDROID_SDK_ROOT or ANDROID_HOME must point to an installed Android SDK.'
}
$buildTools = Get-ChildItem -LiteralPath (Join-Path $androidSdkRoot 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw 'Android SDK Build Tools are required to verify the APK signature.' }
$apkSigner = Join-Path $buildTools.FullName 'apksigner.bat'
$apkAnalyzer = Join-Path $androidSdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
if (-not (Test-Path -LiteralPath $apkSigner -PathType Leaf)) { throw "apksigner not found: $apkSigner" }
if (-not (Test-Path -LiteralPath $apkAnalyzer -PathType Leaf)) { throw "apkanalyzer not found: $apkAnalyzer" }

function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory)]
        [string]$JavaExecutable
    )

    $versionOutput = (& $JavaExecutable -version 2>&1) -join "`n"
    $match = [regex]::Match($versionOutput, 'version "(?<major>\d+)')
    if (-not $match.Success) { return 0 }
    return [int]$match.Groups['major'].Value
}

function Get-ApkAnalyzerValue {
    param(
        [Parameter(Mandatory)]
        [string]$Property
    )

    $output = @(& $apkAnalyzer manifest $Property $resolvedApk 2>$null)
    if ($LASTEXITCODE -ne 0) {
        throw "apkanalyzer failed to read manifest property '$Property'."
    }
    $values = @(
        $output |
            ForEach-Object { $_.ToString().Trim() } |
            Where-Object { $_ }
    )
    if ($values.Count -ne 1) {
        throw "apkanalyzer returned an unexpected value for '$Property': $($values -join ', ')"
    }
    return $values[0]
}

$javaCandidates = @()
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
$pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
if ($pathJava) { $javaCandidates += $pathJava.Source }
$javaExecutable = $javaCandidates |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -Unique |
    Where-Object { (Get-JavaMajorVersion -JavaExecutable $_) -ge 17 } |
    Select-Object -First 1
if (-not $javaExecutable) {
    throw 'Java 17 or newer is required. Set JAVA_HOME or place a compatible java.exe on PATH.'
}

$originalJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaExecutable)

    $signatureReport = & $apkSigner verify --print-certs $resolvedApk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed.`n$signatureReport" }
    $signerMatch = [regex]::Match(($signatureReport -join "`n"), 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $signerMatch.Success -or $signerMatch.Groups[1].Value.ToLowerInvariant() -ne $expectedSignerSha256) {
        throw 'APK is not signed with the permanent PaleInk RikkaHub release certificate.'
    }

    $actualApplicationId = Get-ApkAnalyzerValue -Property 'application-id'
    $actualVersion = Get-ApkAnalyzerValue -Property 'version-name'
    $actualVersionCodeText = Get-ApkAnalyzerValue -Property 'version-code'
    if ($actualVersionCodeText -notmatch '^\d+$') {
        throw "apkanalyzer returned an invalid version code: $actualVersionCodeText"
    }
    $actualVersionCode = [int]$actualVersionCodeText
}
finally {
    $env:JAVA_HOME = $originalJavaHome
}
if ($actualApplicationId -ne $expectedApplicationId) {
    throw "Unexpected application ID: $actualApplicationId"
}
if ($actualVersion -ne $Version -or $actualVersionCode -ne $VersionCode) {
    throw "APK version is $actualVersion ($actualVersionCode), not $Version ($VersionCode)."
}

$fileName = "rikkahub-$Version-$Abi.apk"
$sha256 = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
$sizeBytes = (Get-Item -LiteralPath $resolvedApk).Length
$size = '{0:N2} MB' -f ($sizeBytes / 1MB)
$downloadUrl = "https://updates.paleink.cc/files/$fileName"
$remoteRoot = '/srv/rikkahub-updates'
$remoteStaging = "$remoteRoot/staging/$VersionCode"

$manifest = [ordered]@{
    schemaVersion = 1
    source = 'paleink/rikkahub'
    channel = 'stable'
    version = $Version
    versionCode = $VersionCode
    publishedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    changelog = $Changelog
    releaseUrl = 'https://updates.paleink.cc/'
    downloads = @(
        [ordered]@{
            name = $fileName
            url = $downloadUrl
            size = $size
            sizeBytes = $sizeBytes
            abi = $Abi
            sha256 = $sha256
        }
    )
}

$tempDirectory = Join-Path ([IO.Path]::GetTempPath()) "rikkahub-update-$VersionCode"
$manifestPath = Join-Path $tempDirectory 'stable.json'
New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null
try {
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

    Write-Host "APK: $fileName"
    Write-Host "Signer SHA-256: $expectedSignerSha256"
    Write-Host "SHA-256: $sha256"
    Write-Host "Size: $size"

    if (-not $PSCmdlet.ShouldProcess($SshTarget, "Publish RikkaHub $Version ($VersionCode)")) {
        return
    }

    $ssh = @('-p', $SshPort)
    $scp = @('-P', $SshPort)
    if ($resolvedIdentity) {
        $ssh += @('-i', $resolvedIdentity, '-o', 'IdentitiesOnly=yes')
        $scp += @('-i', $resolvedIdentity, '-o', 'IdentitiesOnly=yes')
    }
    $ssh += $SshTarget

    & ssh @ssh "install -d -m 755 '$remoteStaging' '$remoteRoot/public/files' '$remoteRoot/public/api/v1'"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to create remote staging directory.' }

    & scp @scp $resolvedApk "${SshTarget}:$remoteStaging/$fileName"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to upload APK.' }
    & scp @scp $manifestPath "${SshTarget}:$remoteStaging/stable.json"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to upload update manifest.' }

    $publishCommand = @"
set -eu
actual=`$(sha256sum '$remoteStaging/$fileName' | awk '{print `$1}')
test "`$actual" = '$sha256'
install -m 644 '$remoteStaging/$fileName' '$remoteRoot/public/files/$fileName'
install -m 644 '$remoteStaging/stable.json' '$remoteRoot/public/api/v1/stable.json.next'
mv '$remoteRoot/public/api/v1/stable.json.next' '$remoteRoot/public/api/v1/stable.json'
chown caddy:caddy '$remoteRoot/public/files/$fileName' '$remoteRoot/public/api/v1/stable.json'
"@
    & ssh @ssh $publishCommand
    if ($LASTEXITCODE -ne 0) { throw 'Remote verification or publication failed.' }

    $published = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' -Headers @{ 'Cache-Control' = 'no-cache' }
    if ($published.versionCode -ne $VersionCode -or $published.downloads[0].sha256 -ne $sha256) {
        throw 'Published manifest verification failed.'
    }
    Write-Host "Published and verified: https://updates.paleink.cc/api/v1/stable.json"
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}
