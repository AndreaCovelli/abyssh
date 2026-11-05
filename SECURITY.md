# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub's
[private vulnerability reporting form](https://github.com/AndreaCovelli/abyssh/security/advisories/new).
Please don't open a public issue or pull request for them.

A useful report says which version you tested, how to reproduce the problem, and what an
attacker gains from it. Leave real passwords, private keys, and session output out of it.

Abyssh is maintained on a best-effort basis, so there is no fixed response time. Reports
are answered through the advisory, and you'll be credited there if you want to be.

## Supported versions

Only the [latest release](https://github.com/AndreaCovelli/abyssh/releases/latest) gets
security fixes. Fixes ship in a new release, not as patches to older ones.

## Scope

In scope is the code in this repository, which includes the Android app and the native
library, along with the versions of libssh2, OpenSSL, and libghostty-vt it builds with.
Vulnerabilities in those projects themselves belong with their maintainers, though a
report that Abyssh ships an affected version is welcome here.
