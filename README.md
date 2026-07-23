<!-- Modified from Hy4ri/hermes-mobile for this fork; see NOTICE. -->

<div align="center">
  <br>
  <img src="https://img.shields.io/badge/Android-34DDDD?style=for-the-badge&logo=android&logoColor=black" alt="Android"/>
  <img src="https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose"/>
  <img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/Material%20You-6750A4?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Material You"/>
  <br><br>
</div>

<h1 align="center">Hermes Mobile</h1>
<p align="center"><strong>Native Android companion app for your Hermes AI agent.</strong></p>

<p align="center">
  <a href="https://github.com/saralilyb/hermes-mobile/releases/latest"><img src="https://img.shields.io/github/v/release/saralilyb/hermes-mobile?color=6750A4&label=Latest%20Release&logo=github" alt="Latest Release"></a>
  <img src="https://img.shields.io/github/actions/workflow/status/saralilyb/hermes-mobile/android.yml?branch=main&label=CI&logo=githubactions" alt="CI">
  <img src="https://img.shields.io/badge/minSdk-26-brightgreen" alt="minSdk 26">
  <img src="https://img.shields.io/badge/targetSdk-36-brightgreen" alt="targetSdk 36">
</p>

---

## Overview

**Hermes Mobile** is an unofficial native Android client for
[Hermes Agent](https://hermes-agent.nousresearch.com). This security-focused
fork is based on [Hy4ri/hermes-mobile](https://github.com/Hy4ri/hermes-mobile)
and currently incorporates upstream through `v1.18.0`. It retains downstream
security, complete-history pagination, signing, and distribution changes. The
public release uses generic Hermes branding; the optional `iris` flavor exists
only to distinguish a side-by-side personal installation.

---

## Features

- **Real-Time Chat:** Room-backed history, rich tool and approval cards,
  bottom-follow with unread counts, reasoning controls, and a two-row composer.
- **System Config:** Manage active profiles, installed skills, plugins, and LLM model selections.
- **Operations:** Stream and filter live logs, manage cron jobs, edit environment keys, and test webhooks.
- **Gateway Status:** Monitor WebSocket connection, MCP servers, and messaging channel status.
- **Productivity:** View and manage tasks via integrated Kanban boards and track agent milestones.
- **Modern UX:** Drawer-first Navigation3, dynamic Material You colors, theme
  presets, scroll-aware controls, and pull-to-refresh.
- **Responsive Data Loading:** Two-phase model loading and compacted-history
  requests only where complete session history requires them.

---

## Quick Start

### Install with Obtainium

Add this repository URL to
[Obtainium](https://github.com/ImranR98/Obtainium):

```text
https://github.com/saralilyb/hermes-mobile
```

Obtainium installs the signed generic `Hermes Mobile` APK published with each
GitHub Release. The `Iris Mobile` flavor is a separate personal build, not the
public update channel.

### Build from source

Prerequisites:

- **JDK 21+**
- **Android Studio** or an Android SDK with platform 36

```sh
git clone https://github.com/saralilyb/hermes-mobile.git
cd hermes-mobile
./gradlew assembleHermesDebug
adb install app/build/outputs/apk/hermes/debug/app-hermes-debug.apk
```

Release builds require `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD`. The tag-triggered workflow creates a draft release only after
the signed APK passes `apksigner` verification. Verify published artifacts
against the certificate fingerprint in [SIGNING.md](SIGNING.md).

---

## Authentication

Enter the complete Hermes dashboard base URL, including `https://`, any
explicit port, and any reverse-proxy path prefix. For example:

```text
https://hermes.example.com/
https://hermes.example.com:9119/dashboard/
```

The app probes the dashboard and follows the server's authentication challenge.
Basic authentication uses the username and password configured on the
dashboard; the app exchanges them for an endpoint-scoped session cookie.
WebSocket connections request a fresh, single-use ticket for every handshake
and reconnect. Do not use example or default passwords.

Release builds require HTTPS and derive `wss://` WebSocket URLs from the same
base URL. Debug builds can use explicit HTTP URLs for local development and
show a cleartext warning.

### Connection profiles

Use **Settings → Connection profiles** to switch among complete server URLs.
Credentials and session state remain scoped to their profile.

### Pairing (admin)

The **Pairing** screen lets you approve or revoke agents and services that are
trying to connect to your gateway, such as Telegram or Discord sessions.

---

## Project Structure

```
app/src/main/java/com/m57/hermescontrol/
├── data/          # Local (Room, EncryptedSharedPreferences) & Remote (Retrofit, OkHttp WS)
├── notification/  # Foreground service for message notifications
├── theme/         # Material You design system, status colors, spacing, and typography
└── ui/            # Compose screens (Chat, Settings, Profiles, Kanban, etc.) + Navigation
```

---

## Tech Stack

- **Language:** Kotlin 2.4.10 with KSP compiler plugin
- **UI & Layout:** Jetpack Compose (BOM 2026.03.01) & Material 3 / Material You
- **Navigation:** Navigation3 (Compose-first Routing)
- **Networking:** Retrofit 3.0.0, OkHttp 5.4.0, kotlinx-serialization
- **Database:** Room 2.7.1 with SQLCipher encryption
- **Security:** `EncryptedSharedPreferences` (AES256-GCM)
- **Formatting:** `ktlint` style rules (checked automatically in CI)

---

## Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for our branch workflow, code style guidelines, and PR checklist.

For developer-specific details, code conventions, and project architecture notes, refer to [AGENTS.md](AGENTS.md).

---

## License

Copyright © 2026 M57 (Hy4ri). Security, transport, packaging, and distribution
modifications Copyright © 2026 Sara Burke.

This fork remains licensed under the Apache License, Version 2.0. See
[LICENSE](LICENSE) and [NOTICE](NOTICE).
