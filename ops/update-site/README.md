# PaleInk RikkaHub update site

The production site is served by Caddy from `/srv/rikkahub-updates/public` at
`https://updates.paleink.cc`.

Publish an APK before atomically replacing `public/api/v1/stable.json`. Every
published APK entry must use HTTPS and include its SHA-256 digest. Never place
signing keys, Firebase files, or server credentials in this directory.

Use `publish-update.ps1` after producing a permanently signed release APK. The
script verifies the application ID, embedded version, and permanent signing
certificate before it computes SHA-256, uploads the APK, site index, and
manifest into a staging directory, verifies the remote digests, then replaces
the stable manifest last. Use
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

Until the one-time setup below is completed, run the script without
`-IdentityFile` and enter the server password interactively. Routine releases
should use the restricted deployment account/key instead.

## One-click stable releases

The repository root `release.cmd` is the normal PaleInk stable-release entry
point. It intentionally uses credentials already stored by the operating
system instead of accepting plaintext passwords.

One-time workstation/server setup:

1. Keep the permanent Android signing configuration in `local.properties` as
   described above.
2. Sign in once with `gh auth login`; GitHub CLI stores the token in Windows
   Credential Manager.
3. Run `pwsh ops/release/setup-release-access.ps1`. Enter the server root
   password only for this bootstrap. The script creates a dedicated
   `rikkahub-deploy` account, installs a restricted SSH key, grants access only
   to the update-site tree, reloads SSH after `sshd -t`, and verifies key login.

For each later release:

1. Replace the template in `ops/update-site/RELEASE_NOTES.md` with the Markdown
   announcement. Emoji headings and bullet lists are supported by both the App
   dialog and update website.
2. Double-click `release.cmd`.
3. Review the displayed Git status and type the exact release confirmation.

The release runner then increments the PaleInk revision and version code,
archives the notes, runs tests and one universal signed build in a single
Gradle invocation, stages and commits the reviewed worktree, creates and pushes
an annotated tag, uploads a draft GitHub Release, atomically publishes the
update site, promotes the GitHub Release, and verifies the public feed and APK.
If site publication fails, the GitHub Release remains a draft rather than
advertising an unavailable update.

Use this read-only preflight when changing the release scripts:

```powershell
pwsh ops/release/release.ps1 -DryRun
```

Never paste the server password into chat, commit it, pass it as a command-line
argument, or store it in `RELEASE_NOTES.md`. After bootstrap, routine releases
do not need the server password at all.
