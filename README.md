<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub</h1>

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
[![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

A native Android LLM chat client that supports switching between different providers for
conversations 🤖💬

Click to join our Discord server 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## About This Fork

This repository is a long-term maintained fork of
[RikkaHub](https://github.com/rikkahub/rikkahub). It stays close to upstream while focusing on
reliability improvements and carefully scoped product enhancements.

Changes currently maintained by this fork include:

- Chat-native image generation: reasoning models can call the configured image model without
  leaving the conversation.
- Reference-image editing from chat attachments and earlier generated images.
- Progressive multi-image galleries with a large preview, switchable thumbnails, pending slots,
  partial-failure handling, grouped collapse, and full-screen zoom.
- Compact galleries for multiple user attachments while preserving the existing single-image
  message layout.
- Lifecycle-safe image generation that continues when navigating to another screen.
- Foreground-service notifications for long-running image requests, including explicit cancellation
  and Android 13+ notification permission handling.
- Persistent image-generation task state that can be observed again after recreating the page.
- Interruption recovery without automatic request retries, preventing accidental duplicate billing.
- Safer result persistence to local image storage and Room, with generated files retained when a
  database write fails.
- Clear generation phases and elapsed-time feedback instead of an indefinite loading indicator.
- A built-in Palenik provider catalog, automatic image-model selection, and reliable background
  title-model fallback for provider-only setups.
- A clearer collapsible sidebar for creative tools and chats, plus resumable in-app updates and an
  automated release workflow.
- Isolated debug telemetry: debug APKs build without production Firebase and never pollute release
  analytics or crash reports.

Upstream copyright notices and the AGPL-3.0 license remain in effect. Changes specific to this fork
are tracked in its Git history.

### Latest PaleInk release: 2.4.5-pale.3

- 🖼️ Chat-native reference-image generation and compact progressive multi-image galleries.
- 🧰 A clearer, persistent collapsible sidebar for creative tools and conversations.
- ⬇️ Resumable, signed in-app updates with APK identity checks and a clearer first-install guide.
- 🛡️ Stronger backup, media persistence, provider readiness, Web access, Debug privacy, and
  cross-platform release gates.

## 🚀 Download

🔗 [Download the PaleInk fork](https://updates.paleink.cc/) (Recommended)

🔗 [Upstream RikkaHub website](https://rikka-ai.com/download)

> [!WARNING]
> There are many forked versions of RikkaHub. Issues with forks are unrelated to RikkaHub, so please use forks with caution to avoid privacy leaks or excessive permission requests.

## ✨ Features

- 🎨 Material You Design and 🌙 Dark mode
- 📦 Workspace: a proot-based Linux agent environment
- 🔄 Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- 🖼️ Multimodal input support (Image, Text Documentation, PDF, Docx)
- 🎨 Generate and edit images directly inside chats, including multi-image progressive galleries
- 🖥️ Web access for multi-platform use
- 🛠️ MCP support
- 📝 Markdown Rendering (with code highlighting, Latex formulas, tables, Mermaid)
- 🪾 Message Branching
- 🔍 Search capabilities (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, etc.)
- 🧩 Prompt variables (model name, time, etc.)
- 🤳 QR code export and import for providers
- 🤖 Agent customization
- 🧠 ChatGPT-like memory feature
- 📝 AI Translation
- 🌐 Custom HTTP request headers and request bodies
- 💌 Silly Tavern character card import

## ✨ Contributing

This project is developed using [Android Studio](https://developer.android.com/studio). PRs are
welcome!

Technology stack documentation:

- [Kotlin](https://kotlinlang.org/) (Development language)
- [Koin](https://insert-koin.io/) (Dependency Injection)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI framework)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preference data
  storage)
- [Room](https://developer.android.com/training/data-storage/room) (Database)
- [Coil](https://coil-kt.github.io/coil/) (Image loading)
- [Material You](https://m3.material.io/) (UI design)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (Navigation)
- [Okhttp](https://square.github.io/okhttp/) (HTTP client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON serialization)

> [!TIP]
> Debug builds work without Firebase. Release builds keep using `app/google-services.json` for
> production Analytics and Crashlytics.

> [!IMPORTANT]  
> The following PRs will be rejected:
> 1. Translation related changes, such as adding new languages or updating existing translations
> 2. Adding new features, this project is opinionated and will not accept pull requests for new features
> 3. Large-scale refactoring and changes generated by AI

## 💰 Donate

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## 📄 License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).
