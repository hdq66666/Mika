# Mika 声音模型用途与选择界面 · 外部审核交接

2026-11-22。请以 **`8496b96c`** 为审核和复测版本，目标分支为 `wangzan101/Mika:main`。这版已安装到 Android 16 USB 真机。开发侧验证已完成，用户手工验收和外部审查结论待填写。

## 版本与差异

| 审核对象 | 提交 | 用途 |
| --- | --- | --- |
| 开发基线 | `f4d9bcaa` | 查看本功能相对上游的全部变化 |
| 上游草稿 PR #792 | `e9856a2c` | 上次推送到 PR 的代码，存在按名称推断 TTS 的旧逻辑 |
| 用途修复 | `5bc1a98b` | 用户显式标记决定 TTS 归类，误标后可以直接改回主模型 |
| 添加服务商导航修复 | `b5dfcdf3` | 从角色或备援选择器打开添加表单，返回原角色或槽位 |
| 本次真机候选 | **`8496b96c`** | 声音设置内直接显示分组单选列表，移除额外的选择页面 |

优先审查 PR 之后的三个提交。审核分支名中的 `b5dfcdf3` 是首次发布到 fork 的快照。下列链接固定到实际代码版本，文档提交单独位于其后。[候选源码](https://github.com/hdq66666/Mika/tree/8496b96c1c426b58162d9ca4c0db7257afea4d7a) · [相对上游基线的完整差异](https://github.com/hdq66666/Mika/compare/f4d9bcaa0fc5847124fc67e7797616055fbc36b3...8496b96c1c426b58162d9ca4c0db7257afea4d7a) · [PR 之后的增量差异](https://github.com/hdq66666/Mika/compare/e9856a2cdcb957790c61039f97383fcb182fe90c...8496b96c1c426b58162d9ca4c0db7257afea4d7a) · [下载增量补丁](https://github.com/hdq66666/Mika/compare/e9856a2cdcb957790c61039f97383fcb182fe90c...8496b96c1c426b58162d9ca4c0db7257afea4d7a.patch) · [上游草稿 PR](https://github.com/wangzan101/Mika/pull/792)

## 用户要求与判定方法

| 场景 | 预期结果 |
| --- | --- |
| 同一个模型使用原名或自定义别名 | 用户在该服务商「用作」中勾选 TTS 后才进入声音模型列表。模型 ID、量化后缀和服务端的语音端点声明均不能代替用户的决定。 |
| 一家服务商只有一个模型，用户把它从主模型改为 TTS | 主模型、看图、轻量及其备援选择器隐藏整家服务商。服务商管理页仍能打开该模型。混合服务商继续展示适用于所选用途的其他模型。 |
| 用户误标，想把唯一模型改回主模型 | 在服务商详情打开模型，点「用作 → 主模型」，一次保存即切换用途。只取消 TTS 标记时，主模型留给用户选择。写入失败时核对内存和重新启动后的状态。 |
| 打开「模型 → 声音模型」 | 首屏直接列出已标记模型及其服务商，和 LLM 选择器共用分组、搜索和单选行。点选后在同一页编辑音色、语速和协议，系统返回一次回到模型总览。进入和离开页面沿用全局滑动过渡及系统减少动画设置。 |
| 唯一对话模型改回 TTS 后，从空的主模型列表点「添加服务商」 | 直接打开可操作的添加表单。取消或返回回到刚才的主模型选择页。对看图、轻量和三个备援入口复测，备援须保留原槽位。 |
| 添加服务商、刷新目录、手填或切换用途 | 使用本地模拟服务记录请求。读取目录可以产生 GET，请求数中应有 **0 次推理 POST**。显式测试对话模型时，确认框显示服务商与模型 ID，取消产生 0 次调用，确认只调用这一个目标。 |
| 旧版声音设置升级 | 保留用户原先选定的服务商、模型、音色与语速。迁移后手动取消的标记，重启也应保持取消状态。 |

## 代码入口

- 分类、持久化和恢复应连起来审。重点看 `isTtsOnly`、`mayHaveNonTtsModels`、`setTtsModel`、`use(reassignTts = true)` 和 `reconcileTtsChoice`。候选列表和运行时需遵循同一份服务商标记。[ModelOptions.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/settings/ModelOptions.kt) · [ProviderStore.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/settings/ProviderStore.kt) · [VoiceSettings.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/settings/VoiceSettings.kt)
- 声音设置和 LLM 用途页均使用 `ModelPickerRows` 与 `RadioRow`。检查筛选、当前项选中状态、切换服务商以及取消正在进行的试听。[VoiceModelScreen.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/ui/VoiceModelScreen.kt) · [ModelsScreen.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/ui/ModelsScreen.kt)
- 导航关注 `ModelSubPage.AddProvider`、进入表单前的来源页面与 `NavSlide`。请求授权关注 `testAndSave`、`retestModel` 及确认弹窗。分段播放和生产 TTS 服务的实现另有独立工作流。[ModelSetupScreen.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/ui/ModelSetupScreen.kt) · [ModelSetupViewModel.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/ui/ModelSetupViewModel.kt) · [Navigation.kt](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/app/src/main/java/com/wangzan101/mika/ui/Navigation.kt)

## 已有验证与待签核项

| 版本 | 已观察到的结果 |
| --- | --- |
| `e9856a2c` | 四项 GitHub CI 通过。模拟服务记录了目录 GET、取消测试零调用、确认后只向指定模型发送请求。 |
| `5bc1a98b` | 本地核心、CLI、Android 单测和 Dev 构建通过。覆盖未标记模型手选、误标改回、重新加载以及写入失败。 |
| `b5dfcdf3` | USB 真机两项导航测试遍历三种角色及三种备援。另在设备实际配置重现空列表，验证「主模型 → 添加服务商 → 系统返回 → 主模型」。 |
| `8496b96c` | Dev APK、Android 测试包、Android JVM 单测通过。USB 真机的 `voiceSettingsShowsMarkedModelsInlineAndKeepsSelectionOnPage` 通过，覆盖标记与未标记模型混排、同页改选、音色及语速保留。实际应用中，从声音页按一次系统返回到模型总览，再次进入成功。 |

待用户在手机上确认声音页的布局、行样式及过渡观感。待外部团队签核上述用途边界与导航。若发现问题，请记录提交、Android 版本、起始页面、已有角色标记、操作和实际结果。涉及请求行为时，可附模拟端点的方法、路径和调用次数。

## 复测命令

在 JDK 17 和 Android SDK 环境中，从 fork 取回审核代码。构建详情见项目贡献指南。[贡献指南](https://github.com/hdq66666/Mika/blob/8496b96c1c426b58162d9ca4c0db7257afea4d7a/.github/CONTRIBUTING.zh-CN.md)

```bash
git clone --branch review/tts-model-role-b5dfcdf3 https://github.com/hdq66666/Mika.git
cd Mika
git switch --detach 8496b96c1c426b58162d9ca4c0db7257afea4d7a
./gradlew -PdevInstall :core:test :core:cliTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

在专用 Android 真机上安装 Dev 包和测试包。以下用例使用隔离的测试配置。将 `ANDROID_SERIAL` 设为审核设备序列号。

```bash
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
TEST=com.wangzan101.mika.ui.ModelSetupScreenTest
adb -s "$ANDROID_SERIAL" shell am instrument -w -e class "$TEST#voiceSettingsShowsMarkedModelsInlineAndKeepsSelectionOnPage,$TEST#addProviderReturnsToEachRoleAfterOnlyChatModelIsMarkedTts,$TEST#addingProviderFromFallbackReturnsToItsRoleAndSlot,$TEST#soleMarkedModelCanBeAssignedBackToMainFromProviderDetails,$TEST#providerRetestConfirmsOneNamedModelBeforeCallingIt" com.wangzan101.mika.dev.test/androidx.test.runner.AndroidJUnitRunner
```
