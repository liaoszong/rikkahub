<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>PaleInk</h1>

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
[![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

An Android AI client built for everyday chat, multimodal work, and image creation.

Click to join our Discord server 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## About PaleInk

PaleInk started from [RikkaHub](https://github.com/rikkahub/rikkahub) and is now maintained as an
independent Android release. It has its own roadmap and release schedule, while useful upstream
changes are merged when they fit.

PaleInk releases and bug reports are handled in this repository. RikkaHub's copyright notices and
the AGPL-3.0 license remain in effect.

## 🧭 Multiplatform Direction

Android remains PaleInk's main platform. Windows is planned next through Kotlin Multiplatform and
Compose Desktop; iOS may follow later. Native Android features will remain first-class.

Windows and iOS clients are not available yet. See the
[multiplatform plan](docs/architecture/ANDROID_FIRST_MULTIPLATFORM_ARCHITECTURE.md) for details.

### Latest PaleInk release: 2.4.5-pale.6

- 🖼️ Generate or edit 1–8 images in chat, with progressive results and background processing.
- 🗃️ Browse generated images and chat attachments in the Asset Library.
- 💬 Long chats and background requests can recover after interruption; search results show sources.
- 🛡️ Updates support resume and signature checks, with 16 KB compatibility and separate Debug data.

## 🚀 Download

🔗 [Download PaleInk](https://updates.paleink.cc/) (Recommended)

🔗 [Original RikkaHub website](https://rikka-ai.com/download)

> [!WARNING]
> PaleInk and RikkaHub use different update channels and signatures. Install PaleInk only from its
> official download page.

## ✨ Features

- 🎨 Material You Design and 🌙 Dark mode
- 📦 Workspace: a proot-based Linux agent environment
- 🔄 Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- 🖼️ Multimodal input support (Image, Text Documentation, PDF, Docx)
- 🎨 Generate and edit 1–8 images directly in chat
- 🗃️ Asset Library for generated images and chat attachments
- 🔔 Background generation with progress and cancellation
- 🛡️ Interruption recovery, protected credentials, and safer backup/restore
- 🧭 Automatic model selection based on provider capabilities
- 🔗 Search results with source citations
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
> Please discuss large features or refactors before opening a PR. Translation changes should update
> all three README languages.

## 💰 Donate

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## 📄 License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).
