# Progress Status: HermesControl Android App

This document tracks the completed features and the pending backlog for the HermesControl Jetpack Compose Android application.

## 👥 Done (Implemented & Verified)

### 1. Base Infrastructure & Environment
* **Nix Flake Dev Environment (`flake.nix`)**: Fully configured with JDK 17, Android SDK (platforms 34-36), `ktlint`, and Gradle.
* **Scaffolding & Build Pipeline**: Android Jetpack Compose project compiles cleanly using `./gradlew assembleDebug`.
* **Code Formatting**: Code formatting checks integrated using `ktlint` (`./gradlew ktlintCheck`).
* **Unit Testing Suite**: Verified with `./gradlew test` (AuthManager, EventParser, ConnectViewModel, ChatViewModel).
* **Network Capabilities**: Cleartext traffic enabled in `AndroidManifest.xml` to allow connection to local networks (e.g., `192.168.1.18` to `192.168.1.48`).
* **Git Repository**: Initialized and linked with GitHub (`https://github.com/Hy4ri/hermes-mobile.git`).

### 2. Implemented Features (~45 of 213 endpoints, ~21%)
* **Connection Screen (`ConnectScreen`)**: Saves Host, Port, and Auth Token to `AuthManager` (EncryptedSharedPreferences), with live verification via `GET /api/status`.
* **Chat Screen (`ChatScreen`)**: Live chat over WebSocket (JSON-RPC) supporting:
  * Creating sessions, resuming sessions, listing sessions, and submitting prompts.
  * Message token streaming, thinking delta indicator, and interrupting sessions.
  * Basic Markdown-ish rendering (bold, code blocks) and expandable tool call indicators.
* **Skills Screen (`SkillsScreen`)**: Lists skills (`GET /api/skills`) and allows toggling them (`PUT /api/skills/toggle`) with auto-refresh and swipe-to-refresh. Toggles have optimistic state reversion on failure.
* **Cron Jobs Screen (`CronJobsScreen`)**: Lists cron jobs (`GET /api/cron/jobs`) with actions for Trigger, Pause, Resume, and Delete. Auto-refresh and swipe-to-refresh.
* **Gateway Control Screen (`GatewayScreen`)**: Integrates Start (`POST /api/gateway/start`), Stop (`POST /api/gateway/stop`), and Restart (`POST /api/gateway/restart`) with platform state reporting.
* **Profiles Management Screen (`ProfilesScreen`)**: Switch active profile, update soul personality description (`PUT /api/profiles/{name}/soul`), and switch AI model providers.
* **Toolsets Screen (`ToolsetsScreen`)**: List and toggle toolsets (`GET/PUT /api/tools/toolsets`) to enable/disable specific tool groups (e.g., Web Search, Python local run).
* **Achievements Screen (`AchievementsScreen`)**: Displays a gamified list of discovered/locked/unlocked achievements with progress bars (`GET /api/plugins/hermes-achievements/achievements`).
* **Settings Screen (`SettingsScreen`)**: Allows changing settings (Host, Port, Token, Theme, Auto-reconnect) and tests connection.
* **Navigation Drawer**: Integrated Material3 navigation drawer using Navigation3 for switching screens.

---

## ⏳ Left (To Be Implemented)

All remaining **168 endpoints** across the Hermes Dashboard API need to be implemented. The specific requested views/features:

### 1. Model View (AI model selection & recommended configurations)
### 2. Logs View (live scrolling platform logs / agent stdout logs)
### 3. Plugins View (install, update, list agent plugin extensions)
### 4. MCP (Model Context Protocol server configurations & management)
### 5. Channels / Messaging (platform config, e.g., Telegram connection settings)
### 6. Webhooks (create and dispatch live webhooks subscriptions)
### 7. Pairing (device pairing approvals & security management)
### 8. Live Config (edit live config parameters)
### 9. Keys (live environment variables and credentials manager)
### 10. System View (detailed stats, system health, and backups)
### 11. Kanban View (boards, cards, comments, task management plugin)

*Other minor backlogs include Skills Hub and Advanced Session management.*
