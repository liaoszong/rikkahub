<div align="center">
  <img src="docs/icon.png" alt="App 圖標" width="100" />
  <h1>PaleInk</h1>

一款面向日常對話、多模態處理和圖片創作的 Android AI 客戶端。

點擊加入我們的Discord伺服器 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md)

</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 關於 PaleInk

PaleInk 源自 [RikkaHub](https://github.com/rikkahub/rikkahub)，目前作為獨立 Android 版本維護，有自己的功能規劃和發佈節奏。合適的上游改動仍會繼續合併。

PaleInk 的版本發佈和問題回報以本倉庫為準。RikkaHub 的版權聲明及 AGPL-3.0 授權條款繼續有效。

## 🧭 多平台方向

Android 仍是 PaleInk 的主平台。後續計劃透過 Kotlin Multiplatform 和 Compose Desktop 支援 Windows，iOS 暫列在更長期計劃中；Android 原生能力不會因此縮水。

Windows 和 iOS 客戶端目前尚未發佈，具體路線見[多平台規劃](docs/architecture/ANDROID_FIRST_MULTIPLATFORM_ARCHITECTURE.md)。

### PaleInk 最新版本：2.4.5-pale.6

- 🖼️ 聊天內生成或編輯 1–8 張圖片，支援漸進顯示和背景執行。
- 🗃️ 在資產庫統一查看生成圖片和聊天附件。
- 💬 長對話和背景請求支援中斷復原，搜尋結果可顯示來源。
- 🛡️ 更新支援斷點續傳和簽章校驗，並相容 16 KB 頁面，隔離 Debug 資料。

## 🚀 下載

🔗 [下載 PaleInk](https://updates.paleink.cc/)（推薦）

🔗 [前往原版 RikkaHub 官網](https://rikka-ai.com/download)

> [!WARNING]
> PaleInk 與 RikkaHub 使用不同的更新來源和簽章。請只從 PaleInk 官方下載頁安裝 PaleInk。

## 💖 贊助商

|                                         贊助商                                         | 介紹                                                                                                                                              |
|:-----------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------------------------------------|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | 感謝 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的資金支持。我們推薦使用 aihubmix 作為全球主流模型的一站式服務平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及數百種其他模型）。 |
| <img src="docs/sponsors/suixiang.jpg" alt="隨想AI網關" width="50" /><br /><b>隨想AI網關</b> | 感謝隨想AI網關對本項目的贊助！隨想AI網關 是一家可靠高效的 API 中繼服務提供商，提供 Claude、Codex、Gemini 等的中繼服務。注重隱私的中轉站·無數據倒賣·無模型摻水，隱私，透明，極速售後。新帳戶註冊每日簽到就送 0.5 元測試額度，儲值額度 1:1，無需訂閱，按量付費。多線路冗餘、跨區域容災、自動故障切換，長鏈路 SSE 不中斷。99.9% 可用性，關鍵呼叫從不掉隊。 |

## ✨ 功能特色

- 🎨 現代化安卓APP設計（Material You / 預測性返回）和 🌙 暗色模式
- 📦 工作區：基於 proot 的 Linux 智能體環境
- 🖥️ Web多端訪問支持
- 🛠️ MCP 支持
- 🔄 多種類型的供應商支持，自定義 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模態輸入支持
- 🎨 聊天內生成和參考編輯 1–8 張圖片
- 🗃️ 資產庫統一管理生成圖片和聊天附件
- 🔔 文字、工具和圖片任務支援背景執行、進度通知和取消
- 🛡️ 中斷復原、憑據保護和更安全的備份復原
- 🧭 根據供應商能力自動選擇模型
- 🔗 搜尋結果顯示來源引用
- 📝 Markdown 渲染（支持代碼高亮、數學公式、表格、Mermaid）
- 🔍 搜尋功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 變量（模型名稱、時間等）
- 🤳 二維碼導出和導入提供商
- 🤖 智能體自定義
- 🧠 類ChatGPT記憶功能
- 📝 AI翻譯
- 🌐 自定義HTTP請求頭和請求體

## ✨ 貢獻

本項目使用[Android Studio](https://developer.android.com/studio)開發，歡迎提交PR

技術棧文檔:

- [Kotlin](https://kotlinlang.org/) (開發語言)
- [Koin](https://insert-koin.io/) (依賴注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好數據存儲)
- [Room](https://developer.android.com/training/data-storage/room) (數據庫)
- [Coil](https://coil-kt.github.io/coil/) (圖片加載)
- [Material You](https://m3.material.io/) (UI 設計)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (導航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客戶端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> Debug 建置無需 Firebase；Release 建置繼續使用 `app/google-services.json` 提供正式環境的 Analytics 與 Crashlytics。

> [!IMPORTANT]
> 大型功能或重構請先討論再提交 PR。翻譯改動請同步維護三份 README。

## 💰 捐贈

* [Patreon](https://patreon.com/rikkahub)
* [愛發電](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜歡這個項目，請給個Star ⭐

<a href="https://www.star-history.com/?type=date&repos=re-ovo%2Frikkahub">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&theme=dark&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
 </picture>
</a>

## 📄 許可證

本項目基於 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 開源。
