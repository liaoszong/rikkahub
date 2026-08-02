# Fork boundary gate

`boundary.json` pins the upstream merge base and a deterministic fingerprint of every modified path outside Fork-owned roots. `verify-fork-boundary.ps1` fails when a feature starts touching a new upstream path without an explicit boundary review.

Run:

```powershell
pwsh -NoProfile -File ops/fork-boundary/verify-fork-boundary.ps1
```

To inspect the current path set while intentionally syncing upstream or adding a reviewed adapter touchpoint:

```powershell
pwsh -NoProfile -File ops/fork-boundary/verify-fork-boundary.ps1 -Describe
```

Update `upstreamBase`, `knownIntegrationPathCount`, and `knownIntegrationPathSetSha256` only in a dedicated boundary/upstream-sync commit. Do not weaken the Fork-owned prefixes to make an unrelated feature pass.
