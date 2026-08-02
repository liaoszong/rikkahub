# PaleInk RikkaHub update site

The production site is served by Caddy from `/srv/rikkahub-updates/public` at
`https://updates.paleink.cc`.

Publish an APK before atomically replacing `public/api/v1/stable.json`. Every
published APK entry must use HTTPS and include its SHA-256 digest. The feed is
also a signed envelope: current clients verify `signedPayload` with the public
key embedded in the App before trusting any version, URL, or digest. Top-level
fields mirror that payload only for old clients and the public website. Never
place signing keys, Firebase files, or server credentials in this directory.

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

The update-feed signing identity is independent from both APK signing and SSH
deployment:

- Key ID: `paleink-update-feed-rsa-2026-01`
- Algorithm: RSA-3072 with SHA-256
- Public-key DER SHA-256: `9B:09:03:29:78:1A:FB:63:C9:97:8E:5D:EB:6B:0C:FE:6E:1D:00:7B:ED:B1:6E:9A:18:41:F4:F4:D2:4C:01:19`
- Private key: `%USERPROFILE%\.paleink\signing\rikkahub\update-feed-rsa-3072.pem`

Back up that private key in encrypted storage. Losing it requires an App update
signed by the existing Android certificate to rotate the embedded feed public
key; replacing it only on the server will be rejected by installed clients.

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
2. Confirm that the update-feed private key above exists and that its public
   DER SHA-256 matches the documented fingerprint. The publisher refuses a
   mismatched key before uploading anything.
3. Sign in once with `gh auth login`; GitHub CLI stores the token in Windows
   Credential Manager.
4. Run `pwsh ops/release/setup-release-access.ps1`. Enter the server root
   password only for this bootstrap. The script creates a dedicated
   `rikkahub-deploy` account, installs a restricted SSH key, grants access only
   to the update-site tree, reloads SSH after `sshd -t`, and verifies key login.

For each later release:

1. Replace the template in `ops/update-site/RELEASE_NOTES.md` with the Markdown
   announcement. Emoji headings and bullet lists are supported by both the App
   dialog and update website.
2. Double-click `release.cmd`.
3. Review the displayed Git status and type the exact release confirmation.

The release runner first fingerprints the tracked build inputs and runs the
repository-wide tests and Lint gate. A successful gate is recorded under the
ignored `.release-cache/` directory and is reused while those build
inputs remain unchanged; documentation and release-note-only commits do not
invalidate it. Only then does the runner increment the version, archive the
notes, and build the universal signed APK in a separate Gradle invocation.

The prepared APK and its SHA-256 receipt are retained in that ignored cache
directory. Publication then stages and commits the release allowlist, creates
and pushes the annotated tag, uploads or replaces the GitHub Release asset,
atomically publishes the update site, promotes the GitHub Release, and verifies
the public feed and APK. If publication is interrupted, rerun only the Publish
phase; tests, Lint, and APK packaging are not repeated:

```powershell
pwsh ops/release/release.ps1 -Phase Publish
```

The available phases are:

- `Full` (default): reuse or run verification, build once, and publish.
- `Verify`: run or reuse only the repository-wide tests and Lint gate.
- `Publish`: publish the prepared APK and resume an interrupted release.
- `Symbols`: independently retry the Crashlytics mapping upload.

Crashlytics symbols are intentionally outside the critical publication path.
Run the `Symbols` phase after release, or opt in to waiting with
`-UploadSymbols`. If site publication fails, the GitHub Release remains a draft
rather than advertising an unavailable update.

Use this read-only preflight when changing the release scripts:

```powershell
pwsh ops/release/release.ps1 -DryRun
```

Never paste the server password into chat, commit it, pass it as a command-line
argument, or store it in `RELEASE_NOTES.md`. After bootstrap, routine releases
do not need the server password at all.
