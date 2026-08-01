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
    [string]$IdentityFile,

    [string]$FeedSigningPrivateKey = (Join-Path $env:USERPROFILE '.paleink\signing\rikkahub\update-feed-rsa-3072.pem')
)

$ErrorActionPreference = 'Stop'
$expectedApplicationId = 'me.rerere.rikkahub'
$expectedSignerSha256 = 'df8c1f92039b19cfbdd72491e0058eb4682ff75f99cbbe32450fe9ea4d408520'
$feedSigningKeyId = 'paleink-update-feed-rsa-2026-01'
$expectedFeedPublicKeySha256 = '9b090329781afb63c9978e5deb6b0cfe6e1d007bedb16e9a1841f4f4d24c0119'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$resolvedIdentity = if ($IdentityFile) { (Resolve-Path -LiteralPath $IdentityFile).Path } else { $null }
$resolvedFeedSigningKey = if (Test-Path -LiteralPath $FeedSigningPrivateKey -PathType Leaf) {
    (Resolve-Path -LiteralPath $FeedSigningPrivateKey).Path
} else {
    throw "Update feed signing key not found: $FeedSigningPrivateKey"
}
$siteIndex = Join-Path $PSScriptRoot 'public\index.html'
if (-not (Test-Path -LiteralPath $siteIndex -PathType Leaf)) {
    throw "Update site index not found: $siteIndex"
}

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
$openSsl = (Get-Command openssl.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $openSsl) { throw 'OpenSSL is required to sign the update feed.' }

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
$siteIndexSha256 = (Get-FileHash -LiteralPath $siteIndex -Algorithm SHA256).Hash.ToLowerInvariant()
$sizeBytes = (Get-Item -LiteralPath $resolvedApk).Length
$size = '{0:N2} MB' -f ($sizeBytes / 1MB)
$downloadUrl = "https://updates.paleink.cc/files/$fileName"
$remoteRoot = '/srv/rikkahub-updates'
$remoteStaging = "$remoteRoot/staging/$VersionCode"

$payload = [ordered]@{
    schemaVersion = 2
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
$payloadPath = Join-Path $tempDirectory 'stable.payload.json'
$signaturePath = Join-Path $tempDirectory 'stable.payload.sig'
$publicKeyPath = Join-Path $tempDirectory 'stable.public.der'
$stagedApkPath = Join-Path $tempDirectory $fileName
[IO.Directory]::CreateDirectory($tempDirectory) | Out-Null
try {
    $utf8NoBom = [Text.UTF8Encoding]::new($false)
    $payloadJson = $payload | ConvertTo-Json -Depth 5
    [IO.File]::WriteAllText($payloadPath, $payloadJson, $utf8NoBom)
    & $openSsl pkey -in $resolvedFeedSigningKey -pubout -outform DER -out $publicKeyPath
    if ($LASTEXITCODE -ne 0) { throw 'Failed to derive the update feed public key.' }
    $actualFeedPublicKeySha256 = (Get-FileHash -LiteralPath $publicKeyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualFeedPublicKeySha256 -ne $expectedFeedPublicKeySha256) {
        throw 'The update feed private key does not match the public key embedded in the App.'
    }
    & $openSsl dgst -sha256 -sign $resolvedFeedSigningKey -out $signaturePath $payloadPath
    if ($LASTEXITCODE -ne 0) { throw 'Failed to sign the update feed payload.' }
    & $openSsl dgst -sha256 -verify $publicKeyPath -keyform DER -signature $signaturePath $payloadPath
    if ($LASTEXITCODE -ne 0) { throw 'Generated update feed signature could not be verified.' }

    # Keep the payload fields at the top level for old App versions and the public
    # website. New clients trust only signedPayload after signature verification.
    $manifest = [ordered]@{}
    foreach ($entry in $payload.GetEnumerator()) { $manifest[$entry.Key] = $entry.Value }
    $manifest['keyId'] = $feedSigningKeyId
    $manifest['signedPayload'] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($payloadPath))
    $manifest['signature'] = [Convert]::ToBase64String([IO.File]::ReadAllBytes($signaturePath))
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 5), $utf8NoBom)
    [IO.File]::Copy($resolvedApk, $stagedApkPath, $true)

    Write-Host "APK: $fileName"
    Write-Host "Signer SHA-256: $expectedSignerSha256"
    Write-Host "Feed signing key: $feedSigningKeyId ($actualFeedPublicKeySha256)"
    Write-Host "SHA-256: $sha256"
    Write-Host "Site index SHA-256: $siteIndexSha256"
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

    & scp @scp $stagedApkPath $manifestPath $siteIndex "${SshTarget}:$remoteStaging/"
    if ($LASTEXITCODE -ne 0) { throw 'Failed to upload APK, manifest, or update site index.' }

    $publishCommand = @"
set -eu
actual=`$(sha256sum '$remoteStaging/$fileName' | awk '{print `$1}')
test "`$actual" = '$sha256'
actual_site=`$(sha256sum '$remoteStaging/index.html' | awk '{print `$1}')
test "`$actual_site" = '$siteIndexSha256'
install -m 644 '$remoteStaging/$fileName' '$remoteRoot/public/files/$fileName'
install -m 644 '$remoteStaging/index.html' '$remoteRoot/public/index.html'
install -m 644 '$remoteStaging/stable.json' '$remoteRoot/public/api/v1/stable.json.next'
mv '$remoteRoot/public/api/v1/stable.json.next' '$remoteRoot/public/api/v1/stable.json'
"@
    & ssh @ssh $publishCommand
    if ($LASTEXITCODE -ne 0) { throw 'Remote verification or publication failed.' }

    $published = Invoke-RestMethod -Uri 'https://updates.paleink.cc/api/v1/stable.json' -Headers @{ 'Cache-Control' = 'no-cache' }
    $publishedPayload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($published.signedPayload)) |
        ConvertFrom-Json
    if ($published.versionCode -ne $VersionCode -or
        $published.downloads[0].sha256 -ne $sha256 -or
        $published.keyId -ne $feedSigningKeyId -or
        $publishedPayload.versionCode -ne $VersionCode -or
        $publishedPayload.downloads[0].sha256 -ne $sha256) {
        throw 'Published manifest verification failed.'
    }
    Write-Host "Published and verified: https://updates.paleink.cc/api/v1/stable.json"
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        $resolvedTempDirectory = (Resolve-Path -LiteralPath $tempDirectory).Path
        $expectedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedTempDirectory.StartsWith($expectedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove unexpected staging path: $resolvedTempDirectory"
        }
        [IO.Directory]::Delete($resolvedTempDirectory, $true)
    }
}
