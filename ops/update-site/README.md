# PaleInk RikkaHub update site

The production site is served by Caddy from `/srv/rikkahub-updates/public` at
`https://updates.paleink.cc`.

Publish an APK before atomically replacing `public/api/v1/stable.json`. Every
published APK entry must use HTTPS and include its SHA-256 digest. Never place
signing keys, Firebase files, or server credentials in this directory.

Use `publish-update.ps1` after producing a permanently signed release APK. The
script validates the version arguments, computes SHA-256, uploads into a staging
directory, verifies the remote digest, then replaces the stable manifest last.
Use `-WhatIf` to inspect a release without changing the server.

The current server keeps SSH public-key authentication disabled. Run the script
without `-IdentityFile` to enter the server password interactively. A restricted
deployment account/key can be provisioned later before CI-based publishing.
