# PaleInk RikkaHub update site

The production site is served by Caddy from `/srv/rikkahub-updates/public` at
`https://updates.paleink.cc`.

Publish an APK before atomically replacing `public/api/v1/stable.json`. Every
published APK entry must use HTTPS and include its SHA-256 digest. Never place
signing keys, Firebase files, or server credentials in this directory.

Use `publish-update.ps1` after producing a permanently signed release APK. The
script verifies the application ID, embedded version, and permanent signing
certificate before it computes SHA-256, uploads into a staging directory,
verifies the remote digest, then replaces the stable manifest last. Use
`-WhatIf` to inspect a release without changing the server.

The stable direct-download signing identity is:

- Alias: `paleink-rikkahub-release`
- Certificate SHA-256: `DF:8C:1F:92:03:9B:19:CF:BD:D7:24:91:E0:05:8E:B4:68:2F:F7:5F:99:CB:BE:32:45:0F:E9:EA:4D:40:85:20`

The certificate fingerprint is public and intentionally documented here. The
private key and passwords must remain outside the repository and be backed up
separately in encrypted storage.

On the primary Windows release workstation, the permanent keystore is kept at
`%USERPROFILE%\.paleink\signing\rikkahub\paleink-rikkahub-release.p12`.
Gradle reads its path, alias, and passwords from the repository root
`local.properties`, which is intentionally Git-ignored. If either file is
missing, stop the release: locate the existing signing identity instead of
generating a replacement key. The expected certificate SHA-256 above is the
authority for confirming that the correct key was recovered.

The current server keeps SSH public-key authentication disabled. Run the script
without `-IdentityFile` to enter the server password interactively. A restricted
deployment account/key can be provisioned later before CI-based publishing.
