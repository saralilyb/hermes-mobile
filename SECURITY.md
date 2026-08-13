# Security model

## Scope and assumptions

Hermes Mobile is a direct client for a user-operated Hermes dashboard. It does
not use an analytics service, advertising network, crash-reporting service,
cloud relay, or third-party credential broker. The client assumes that the
configured dashboard and the Android device are controlled by the user.

Remote deployments should use an authenticated HTTPS endpoint, for example
`https://hermes.example.com:9119/`. Reverse-proxy path prefixes are supported.
The Android system trust store validates Transport Layer Security (TLS)
certificates. The app does not install a permissive trust manager, disable
hostname verification, or pin a leaf certificate that would break routine
certificate rotation.

## Transport policy

A complete `http://` or `https://` base URL is stored for each connection
profile. HTTP application programming interface (API), authentication,
attachment, and media requests are resolved relative to that URL. WebSocket
URLs are derived deterministically:

- `https` becomes `wss`;
- `http` becomes `ws`.

Release builds reject cleartext HTTP and declare cleartext traffic disabled in
both the Android manifest and network security configuration. Debug builds may
use HTTP for development on a trusted local network, but the connection user
interface displays an explicit warning. The app never downgrades HTTPS.

WebSocket tickets are preferred for cookie-authenticated dashboards. Tokens,
tickets, cookies, passwords, and WebSocket payloads are not written to the app
log. Logged WebSocket URLs have their entire query removed.

## Credential storage and backup

Profile tokens, endpoint-scoped session cookies, and the SQLCipher database
password are stored as independent, app-owned atomic blobs. Each blob uses an
Android Keystore AES-256 key with AES-GCM, a new 96-bit nonce, a 128-bit tag,
and authenticated version and logical-key metadata. Authentication, format, or
I/O errors fail closed. Deletes are authenticated tombstones, preventing a
deleted value from being restored by a legacy fallback.

Upgrades use a resumable copy-and-byte-verify migration followed by a
one-release dual-write compatibility period. During that rollback window, the
legacy store is committed first and remains authoritative; reads repair any
interrupted second write into the new blob store. Outside that reconciliation
path, legacy fallback is permitted only when no new blob or tombstone exists,
never after corruption. The deprecated `EncryptedSharedPreferences` and
`MasterKeys` APIs are isolated to the compatibility reader. Legacy ciphertext
and keysets are not removed by migration, and fresh installs never create them.

The local Room database remains encrypted with SQLCipher. Application backup is
disabled. Secure blobs live under Android's non-backup directory; migration
metadata, legacy encrypted stores, and databases also have explicit
cloud-backup and device-transfer exclusions.

The app does not persist the user's dashboard password after login. A successful
basic-authentication login stores the resulting session cookie and short-lived
WebSocket ticket instead.

## Android component exposure

Only the launcher activity is exported. The notification service, notification
reply receiver, and FileProvider are not exported. FileProvider access requires
a temporary URI permission grant. The app requests only network state,
notifications, foreground-service, and microphone permissions used by its
features.

## Application identities

The `hermes` product flavor uses application ID `sh.slb.hermesmobile` and label
`Hermes Mobile`. The `iris` flavor uses application ID `sh.slb.irismobile` and
label `Iris Mobile`. Both are namespaced for this fork, can coexist with the
upstream application, and cannot accidentally overwrite an upstream install
signed by another key. Source package names remain unchanged to keep the
transport patch reviewable upstream.

## Release provenance

The release workflow:

1. pins third-party GitHub Actions to full commit hashes;
2. validates the Gradle wrapper and a pinned ktlint checksum;
3. grants repository write permission only to the isolated draft-publish job;
4. decodes the signing keystore under `$RUNNER_TEMP` with mode `0600` or
   stricter and deletes it in an always-running cleanup step;
5. builds only `assembleHermesRelease` for public distribution;
6. verifies the APK with `apksigner --verbose --print-certs`;
7. publishes a SHA-256 checksum, commit identifier, version name, and monotonic
   semantic-version-derived version code;
8. creates a draft GitHub Release for human review.

Signing keys and passwords are supplied only through GitHub Actions secrets or
local environment variables. They must never be committed to the repository.

The build removes generated version-control metadata that otherwise differs
between checkout environments. Exact byte-for-byte reproducibility still
requires the same Android Software Development Kit, build tools, Java runtime,
Gradle dependency graph, and signing configuration; the workflow records the
commit and cryptographic artifact checks instead of claiming broader
reproducibility.

## Reporting vulnerabilities

Do not include credentials, private dashboard addresses, session cookies, or
WebSocket tickets in an issue. Use the repository owner's private security
contact or GitHub private vulnerability reporting when available.
