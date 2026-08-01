[CmdletBinding()]
param(
    [string]$Server = 'updates.paleink.cc',
    [string]$RootUser = 'root',
    [string]$DeployUser = 'rikkahub-deploy',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\rikkahub-updates-ed25519"
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([scriptblock]$Command, [string]$FailureMessage)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw $FailureMessage }
}

$identityDirectory = Split-Path -Parent $IdentityFile
New-Item -ItemType Directory -Path $identityDirectory -Force | Out-Null
if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) {
    Invoke-Checked {
        ssh-keygen -t ed25519 -a 64 -f $IdentityFile -N '' -C "rikkahub-update-deploy@$env:COMPUTERNAME"
    } 'Failed to generate the deployment key.'
}

$publicKeyPath = "$IdentityFile.pub"
if (-not (Test-Path -LiteralPath $publicKeyPath -PathType Leaf)) {
    throw "Deployment public key not found: $publicKeyPath"
}
$authorizedKey = "restrict $((Get-Content -Raw -LiteralPath $publicKeyPath).Trim())"
$authorizedKeyBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($authorizedKey))

$remoteScript = @"
set -eu
deploy_user='$DeployUser'
authorized_key_base64='$authorizedKeyBase64'
remote_root='/srv/rikkahub-updates'

if ! id -u "`$deploy_user" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "`$deploy_user"
fi
passwd -d "`$deploy_user" >/dev/null

deploy_home="`$(getent passwd "`$deploy_user" | cut -d: -f6)"
install -d -m 700 -o "`$deploy_user" -g "`$deploy_user" "`$deploy_home/.ssh"
echo "`$authorized_key_base64" | base64 -d > "`$deploy_home/.ssh/authorized_keys"
chown "`$deploy_user:`$deploy_user" "`$deploy_home/.ssh/authorized_keys"
chmod 600 "`$deploy_home/.ssh/authorized_keys"

install -d -m 755 -o "`$deploy_user" -g caddy \
    "`$remote_root/staging" \
    "`$remote_root/public" \
    "`$remote_root/public/files" \
    "`$remote_root/public/api/v1"
chown -R "`$deploy_user:caddy" \
    "`$remote_root/staging" \
    "`$remote_root/public" \
    "`$remote_root/public/files" \
    "`$remote_root/public/api/v1"
if [ -f "`$remote_root/public/index.html" ]; then
    chown "`$deploy_user:caddy" "`$remote_root/public/index.html"
fi

cat > /etc/ssh/sshd_config.d/90-rikkahub-deploy.conf <<'EOF'
Match User $DeployUser
    PubkeyAuthentication yes
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PermitTTY no
    AllowTcpForwarding no
    X11Forwarding no
EOF

sshd -t
if systemctl list-unit-files ssh.service >/dev/null 2>&1; then
    systemctl reload ssh
else
    systemctl reload sshd
fi
"@

$bootstrapName = "rikkahub-release-bootstrap-$PID.sh"
$localBootstrap = Join-Path ([IO.Path]::GetTempPath()) $bootstrapName
$remoteBootstrap = "/tmp/$bootstrapName"
[IO.File]::WriteAllText($localBootstrap, $remoteScript, [Text.UTF8Encoding]::new($false))
try {
    Write-Host 'Bootstrap step 1/2: upload the temporary script. Enter the root password.' -ForegroundColor Cyan
    Invoke-Checked {
        scp -o ConnectTimeout=15 $localBootstrap "${RootUser}@${Server}:$remoteBootstrap"
    } 'Failed to upload the server bootstrap script.'

    Write-Host 'Bootstrap step 2/2: configure and verify the deploy account. Enter the root password again.' -ForegroundColor Cyan
    Invoke-Checked {
        ssh -o ConnectTimeout=15 "$RootUser@$Server" `
            "bash '$remoteBootstrap'; status=`$?; rm -f '$remoteBootstrap'; exit `$status"
    } 'Failed to configure the restricted deployment account.'
}
finally {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $resolvedBootstrap = [IO.Path]::GetFullPath($localBootstrap)
    if ($resolvedBootstrap.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $resolvedBootstrap)) {
        Remove-Item -LiteralPath $resolvedBootstrap -Force
    }
}

Invoke-Checked {
    ssh -i $IdentityFile -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=15 `
        "$DeployUser@$Server" "test -w /srv/rikkahub-updates/staging && test -w /srv/rikkahub-updates/public && test -w /srv/rikkahub-updates/public/api/v1"
} 'Deployment-key verification failed. Password authentication remains available for recovery.'

Write-Host ''
Write-Host 'Release access is ready. Future releases no longer require the server password.' -ForegroundColor Green
Write-Host "Identity: $IdentityFile"
