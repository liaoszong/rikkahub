<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>PaleInk</h1>

一款面向日常对话、多模态处理和图片创作的 Android AI 客户端。

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文

点击链接加入群聊 👉 [【RikkaHub】](https://qm.qq.com/q/I8MSU0FkOu)

</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 关于 PaleInk

PaleInk 源自 [RikkaHub](https://github.com/rikkahub/rikkahub)，现在作为独立 Android 版本维护，有自己的功能规划和发布节奏。合适的上游改动仍会继续合并。

PaleInk 的版本发布和问题反馈以本仓库为准。RikkaHub 的版权声明及 AGPL-3.0 许可证继续有效。

## 🧭 多平台方向

Android 仍是 PaleInk 的主平台。后续计划通过 Kotlin Multiplatform 和 Compose Desktop 支持 Windows，iOS 暂列在更长期计划中；Android 原生能力不会因此缩水。

Windows 和 iOS 客户端目前尚未发布，具体路线见[多平台规划](docs/architecture/ANDROID_FIRST_MULTIPLATFORM_ARCHITECTURE.md)。

### PaleInk 最新版本：2.4.5-pale.6

- 🖼️ 聊天内生成或编辑 1–8 张图片，支持渐进显示和后台运行。
- 🗃️ 在资产库统一查看生成图片和聊天附件。
- 💬 长对话和后台请求支持中断恢复，搜索结果可显示来源。
- 🛡️ 更新支持断点续传和签名校验，并兼容 16 KB 页面，隔离 Debug 数据。

## 🚀 下载

🔗 [下载 PaleInk](https://updates.paleink.cc/)（推荐）

🔗 [前往原版 RikkaHub 官网](https://rikka-ai.com/download)

> [!WARNING]
> PaleInk 与 RikkaHub 使用不同的更新源和签名。请只从 PaleInk 官方下载页安装 PaleInk。

## 💖 赞助商

|                                         赞助商                                         | 介绍                                                                                                                                              |
|:-----------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------------------------------------|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | 感谢 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的资金支持。我们推荐使用 aihubmix 作为全球主流模型的一站式服务平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及数百种其他模型）。 |
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI中转" width="50" /><br /><b><a href="https://sui-xiang.com">随想AI中转</a></b> | 感谢<a href="https://sui-xiang.com">随想AI中转</a>对本项目的赞助！随想AI中转 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |
| <img src="docs/sponsors/ztest.png" alt="真测 ztest.ai" width="50" /><br /><b><a href="https://ztest.ai">真测 ztest.ai</a></b> | 感谢<a href="https://ztest.ai">真测 ztest.ai</a>对本项目的赞助！真测 ztest.ai 是一个 AI 中转站模型检测平台，检测结果数据全公开，23 项探针覆盖协议、身份、能力、内容完整性、安全性、性能六大维度，交叉印证识别伪造与降级。作为独立第三方验证平台，实时监测 AI 中转站的模型真实性、响应质量与服务可用性。 |

## ✨ 功能特色

- 🎨 现代化安卓APP设计（Material You / 预测性返回）和 🌙 暗色模式
- 📦 工作区：基于 proot 的 Linux 智能体环境
- 🖥️ Web多端访问支持
- 🛠️ MCP 支持
- 🔄 多种类型的供应商支持，自定义 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模态输入支持
- 🎨 聊天内生成和参考编辑 1–8 张图片
- 🗃️ 资产库统一管理生成图片和聊天附件
- 🔔 文本、工具和图片任务支持后台运行、进度通知和取消
- 🛡️ 中断恢复、凭据保护和更安全的备份恢复
- 🧭 根据供应商能力自动选择模型
- 🔗 搜索结果显示来源引用
- 📝 Markdown 渲染（支持代码高亮、数学公式、表格、Mermaid）
- 🔍 搜索功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤳 二维码导出和导入提供商
- 🤖 智能体自定义
- 🧠 类ChatGPT记忆功能
- 📝 AI翻译
- 🌐 自定义HTTP请求头和请求体

## ✨ 贡献

本项目使用[Android Studio](https://developer.android.com/studio)开发，欢迎提交PR

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material You](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> Debug 构建无需 Firebase；Release 构建继续使用 `app/google-services.json` 提供正式环境的 Analytics 与 Crashlytics。

> [!IMPORTANT]
> 大型功能或重构请先讨论再提交 PR。翻译改动请同步维护三份 README。

## 💰 捐赠

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜欢这个项目，请给个Star ⭐

<a href="https://www.star-history.com/?type=date&repos=re-ovo%2Frikkahub">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&theme=dark&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=re-ovo/rikkahub&type=date&legend=top-left&sealed_token=qSytWeq7LkzQQViTjK0MYlvvA_qkfuwjOxOqgbRpLUZZwok5rO6LXhpVL7Mq-q3o89BfKpzE7g66BCy18H6eiqTsD8czD0J-HejLqmHy-npcvCTHu11wZw" />
 </picture>
</a>

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) 开源。
