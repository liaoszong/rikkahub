<div align="center">
  <img src="docs/icon.png" alt="App 圖標" width="100" />
  <h1>RikkaHub</h1>

一個原生Android LLM 聊天客戶端，支持切換不同的供應商進行聊天 🤖💬

點擊加入我們的Discord伺服器 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md)

</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 關於這個 Fork

本倉庫是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的長期維護 Fork，在盡量跟進上游的同時，重點改善穩定性與完整的產品體驗。

目前維護的主要增強包括：

- 在一般聊天中由推理模型呼叫已設定的圖像模型，無需離開對話即可生成圖片。
- 支援把本輪上傳圖片或對話中的歷史生成圖作為參考圖繼續創作。
- 漸進式多圖畫廊：主圖、可切換縮圖、生成中佔位、部分失敗保留、整組收合與全螢幕縮放。
- 多張使用者附件使用緊湊畫廊展示，同時保持原有單圖訊息尺寸。
- 生圖任務支援背景執行、跨頁面觀察、明確取消、狀態持久化與中斷復原，避免自動重試造成重複計費。
- 內建 Palenik 供應商模型目錄，自動選擇可用的較新圖像模型，並為標題摘要選擇可靠的背景文字模型。
- 更清晰且可收合的「創作工具／聊天」側邊欄，以及可恢復下載的應用程式內更新體驗。
- 自動化建置、GitHub Release 與更新站發佈流程；Debug 建置不接入正式 Firebase，不污染正式統計。

上游版權聲明及 AGPL-3.0 授權條款繼續有效，本 Fork 的改動均記錄在 Git 歷史中。

### PaleInk 最新版本：2.4.5-pale.3

- 🖼️ 聊天內支援參考圖生成，並以緊湊、漸進式多圖畫廊展示結果。
- 🧰 創作工具與聊天側邊欄更清晰，可收合並持續記住展開狀態。
- ⬇️ 應用程式內更新支援斷點續傳、簽章與 APK 身分校驗，並改善首次安裝權限說明。
- 🛡️ 強化備份復原、圖片持久化、Provider 就緒判斷、Web 存取、Debug 隱私及跨平台發佈門禁。

## 🚀 下載

🔗 [下載 PaleInk Fork](https://updates.paleink.cc/)（推薦）

🔗 [前往上游 RikkaHub 官網](https://rikka-ai.com/download)

> [!WARNING]
> RikkaHub 存在許多 fork 版本，fork 版本出現問題與 RikkaHub 無關，請謹慎使用 fork 版本，避免隱私洩露或者過度索要權限問題。

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
- 🎨 在一般聊天中直接生成、參考編輯並漸進展示多張圖片
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
> 以下PR將被拒絕：
> 1. 添加新語言，因為添加新語言會增加後續本地化的工作量
> 2. 添加新功能，這個項目是有態度的
> 3. AI生成的大規模重構和更改

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
