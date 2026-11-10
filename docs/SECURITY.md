# Security policy

## Supported versions
Only the latest release on the [Releases page](https://github.com/aalpersen/iptvdler/releases) gets security fixes.

## Reporting a vulnerability
Please **don't open a public issue** for security problems.

Report it privately through GitHub instead: go to the repository's **Security** tab →
**Report a vulnerability** ([direct link](https://github.com/aalpersen/iptvdler/security/advisories/new)).

Helpful details:
- the affected version and platform (Android version / Windows, macOS, Linux);
- what an attacker can do, and the steps or a small proof of concept to reproduce it;
- if it involves a playlist or stream, a **minimal synthetic sample** (e.g. hosted on `localhost` or
  `example.com`). Never send real provider URLs or credentials.

You should get a first reply within two weeks. Once a fix is released, the advisory is published with credit
to you unless you'd rather stay anonymous.

## Scope
In scope, for example:
- crashes, code execution or file writes outside the download folder caused by a crafted playlist, HLS
  manifest, subtitle file or Xtream API response;
- credentials (Xtream passwords, URL tokens) leaking into logs, crash reports, file names or other apps;
- a way to make iptvdler download a DRM-protected stream it should refuse.

Out of scope:
- the content or behavior of third-party IPTV providers and streams;
- requests to add DRM circumvention (iptvdler will never do that);
- unsigned installers triggering SmartScreen / Gatekeeper warnings (known, documented in the README).
