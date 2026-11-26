# Code signing

`SyncPad.apk` is signed with SyncPad's own key (CN=SyncPad), the same for every
release, so Android installs updates over the previous version.

`SyncPad.exe` is not signed with a certificate yet, so Windows SmartScreen may
warn the first few times it is run. Each release lists SHA-256 checksums, and
the website shows them, so a download can be checked before it is trusted.

## Plan: free signing through SignPath Foundation

[SignPath Foundation](https://signpath.org) signs open-source projects for free.
Their certificate names SignPath Foundation as the publisher, and they require:

- an OSI licence (SyncPad is MIT) and no closed-source parts;
- binaries built by CI from the public source: `.github/workflows/release.yml`
  already builds every release on GitHub Actions from its tag;
- two-factor authentication on GitHub and SignPath for everyone who can release;
- this attribution, shown with the downloads once signing is live:
  "Free code signing provided by SignPath.io, certificate by SignPath Foundation."

Steps, once:

1. Apply at signpath.org with the repository link.
2. When approved, add `SIGNPATH_API_TOKEN` and `SIGNPATH_ORGANIZATION_ID` as
   repository secrets.
3. In the workflow, after "Test, build and check", send `dist/release/SyncPad.exe`
   and the folder inside `SyncPad-windows.zip` to SignPath with
   `signpath/github-action-submit-signing-request`, put the signed files back, and
   rewrite `SHA256SUMS.txt` before publishing.

## Code signing policy

- Only files built by the release workflow from a tagged commit in this
  repository are signed.
- Releases are made by the repository owner. Commits from others are reviewed
  before they are merged.
- Signed software never collects personal data. SyncPad sends nothing anywhere
  except to the paired laptop or phone, to iroh's relay and address servers when
  "Connect from anywhere" is used, and to GitHub to check for updates.
