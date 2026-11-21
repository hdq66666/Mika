package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.settings.TtsProtocol
import com.wangzan101.mika.settings.VoiceChoice
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder

/** 欢迎页、配置模型一页(上手和设置共用)、选用途页、服务商详情、全部模型和「用作」面板(只用假数据,不联网)。 */
@RunWith(AndroidJUnit4::class)
class ModelSetupScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val calls = mutableListOf<String>()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)

    private val actions = ModelSetupActions(
        selectPreset = { calls += "preset $it" }, testAndSave = { calls += "test" }, openForm = { calls += "open form" },
        saveUntested = { calls += "save untested" },
        openProvider = { calls += "provider $it" }, pickRole = { calls += "role $it" }, next = { calls += "next" },
        openUsage = { calls += "usage" }, openDecision = { calls += "decision" }, openSignIn = { calls += "signin $it" },
    )

    private val form = AddForm.of(ProviderPresets.all.first()).copy(apiKey = "sk")
    private val deepseek = Provider("p1", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com",
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-coder", toolCalls = false)))
    private val qwen = Provider("p2", "通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-vl-max", "qwen-long").map { ListedModel(it) })
    private val main = ProviderConfig("c1", deepseek.kind, deepseek.baseUrl, "deepseek-chat", caps, deepseek.name, deepseek.id)
    private val vision = ProviderConfig("c2", qwen.kind, qwen.baseUrl, "qwen-vl-max", caps, qwen.name, qwen.id)

    private fun connected(roles: RoleChoices = RoleChoices(main = "c1")) = ModelSetupUi(
        form, providers = listOf(deepseek, qwen), configs = listOf(main, vision), roles = roles, keyed = setOf(deepseek.id, qwen.id),
    )

    private fun show(ui: ModelSetupUi, onboarding: Boolean = true) =
        compose.setContent { MikaTheme { ModelSetupContent(ui, onboarding = onboarding, actions = actions, onBack = {}) } }

    @Test fun welcomeStarts() {
        compose.setContent { MikaTheme { WelcomeScreen { calls += "start" } } }
        compose.onNodeWithText(WelcomeCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("welcome-start").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test fun firstVisitShowsFormAndNextIsDisabled() {
        show(ModelSetupUi(form))
        compose.onNodeWithTag("setup-next").assertIsNotEnabled()
        // 上面多了「用账号登录」一组,按钮可能在屏幕外:先滚到再点
        compose.onNodeWithTag("setup-test").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("preset-Kimi").performScrollTo().performClick()
        assertEquals(listOf("test", "preset Kimi"), calls)
    }

    /** 「不测试,直接保存」先问一次:说清后果,取消了就什么都不做,确认了才存。 */
    @Test fun saveWithoutTestAsksBeforeSaving() {
        show(ModelSetupUi(form))
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_BODY).assertIsDisplayed()
        // 取消:什么都没存
        compose.onNodeWithText("取消").performClick()
        assertTrue(calls.isEmpty())
        // 确认:存
        compose.onNodeWithTag("setup-save-untested").performScrollTo().performClick()
        compose.onNodeWithText(ModelSetupCopy.SAVE_UNTESTED_CONFIRM).performClick()
        assertEquals(listOf("save untested"), calls)
    }

    @Test fun connectedProvidersAreListedAndNextEnables() {
        show(connected())
        compose.onNodeWithTag("context-main").assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.PROVIDERS).assertIsDisplayed()
        compose.onNodeWithText("已连接 · 2 个模型 · 主模型、轻量模型在用").assertIsDisplayed()
        // 轻量卡「用主模型」下面也写着主模型名,所以只在主模型卡里找
        compose.onNodeWithTag("role-main").assertTextContains("deepseek-chat", substring = true)
        // 表单收起,留一个入口
        compose.onNodeWithText(ModelsCopy.ADD_PROVIDER).performClick()
        compose.onNodeWithTag("provider-通义千问").performClick()
        compose.onNodeWithTag("role-vision").performScrollTo().performClick()
        compose.onNodeWithTag("setup-next").assertIsEnabled().performClick()
        assertEquals(listOf("open form", "provider p2", "role Vision", "next"), calls)
    }

    @Test fun settingsPageShowsAllThreeRolesWithoutBottomButton() {
        show(connected(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain)), onboarding = false)
        compose.onNodeWithText(ModelsCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.LEAD).assertDoesNotExist()
        // 改动当场生效,左上角返回就行
        compose.onNodeWithTag("setup-next").assertDoesNotExist()
        ModelRole.entries.forEach { compose.onNodeWithText(ModelsCopy.roleLabel(it)).assertIsDisplayed() }
        compose.onNodeWithText(ModelsCopy.OFF).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).assertIsDisplayed()
        compose.onNodeWithTag("role-light").performClick()
        assertEquals(listOf("role Light"), calls)
    }

    @Test fun settingsShowsSelectedTtsModelInTheSameRoleCardStyle() {
        val provider = deepseek.copy(ttsModels = setOf("tts-a"))
        val ui = connected().copy(providers = listOf(provider, qwen))
        compose.setContent {
            MikaTheme {
                ModelSetupContent(
                    ui, onboarding = false, actions = ModelSetupActions(openVoice = { calls += "voice" }),
                    voiceChoice = VoiceChoice(provider.id, "tts-a", TtsProtocol.OpenAiSpeech), onBack = {},
                )
            }
        }
        compose.onNodeWithTag("role-voice").performScrollTo()
            .assertTextContains("tts-a").assertTextContains("DeepSeek").performClick()
        assertEquals(listOf("voice"), calls)
    }

    @Test fun settingsPageOpensTokenUsageButOnboardingDoesNotShowIt() {
        show(connected(), onboarding = false)
        compose.onNodeWithText(UsageCopy.TITLE).performScrollTo().performClick()
        assertEquals(listOf("usage"), calls)
        calls.clear()
        show(connected(), onboarding = true)
        compose.onNodeWithText(UsageCopy.TITLE).assertDoesNotExist()
    }

    @Test fun decisionModelHasAnIndependentSettingsEntry() {
        show(connected(), onboarding = false)
        compose.onNodeWithText("决策模型 · Jev").performScrollTo().performClick()
        assertEquals(listOf("decision"), calls)
    }

    @Test fun decisionModelIsNotAnOnboardingRequirement() {
        show(connected(), onboarding = true)
        compose.onNodeWithText("决策模型 · Jev").assertDoesNotExist()
        compose.onNodeWithTag("setup-next").assertIsEnabled()
    }

    @Test fun contextFormValidatesPreviewsAndSavesTheModelUsedForNewTasks() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { error("Fixture has no API key") })
        val provider = store.connect("Test", deepseek.kind, "https://example.test/v1", null, listOf(ListedModel("m")))
        store.use(ModelRole.Main, provider.id, "m", caps.copy(contextTokens = 32_000))
        compose.setContent { MikaTheme { ModelSetupScreen(store, onboarding = false, onBack = {}, onDone = {}) } }
        val open = compose.onNodeWithTag("context-main")
        val window = compose.onNodeWithTag("context-window")
        val trigger = compose.onNodeWithTag("context-trigger")
        val readTimeout = compose.onNodeWithTag("context-read-timeout")
        val save = compose.onNodeWithTag("context-save")
        val preview = compose.onNodeWithTag("context-effective")
        compose.onNodeWithTag("context-light").assertDoesNotExist()
        open.performScrollTo().performClick()
        preview.assertTextContains("18000", substring = true)
        for (invalid in listOf("0", "-1", "1e6", "2147483648")) {
            window.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        window.performTextReplacement("1000000")
        preview.assertTextContains("967000", substring = true)
        for (invalid in listOf("499999", "980001", "+600000")) {
            trigger.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        trigger.performTextReplacement("600000")
        for (invalid in listOf("0", "-1", "1e6", "2147484", "2147483648")) {
            readTimeout.performTextReplacement(invalid)
            save.assertIsNotEnabled()
        }
        readTimeout.performTextReplacement("600")
        save.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1_000_000, store.modelFor(ModelRole.Main)!!.capabilities.contextTokens)
            assertEquals(600_000, store.modelFor(ModelRole.Light)!!.capabilities.compactionTriggerTokens)
        }
        open.performScrollTo().performClick()
        trigger.assertTextContains("600000")
        readTimeout.assertTextContains("600")
        compose.onNodeWithText("重置").performScrollTo().performClick()
        preview.assertTextContains("18000", substring = true)
        compose.onNodeWithText("取消").performClick()
        assertEquals(ModelContextSettings(1_000_000, 600_000, 600), store.provider(provider.id)!!.contextSettings["m"])
        open.performScrollTo().performClick()
        compose.onNodeWithText("重置").performScrollTo().performClick()
        save.performClick()
        compose.runOnIdle { assertEquals(null, store.provider(provider.id)!!.contextSettings["m"]) }
    }

    @Test fun visionPickerOffersSameAsMainAndOffAndListsEveryModel() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                RolePickerContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Off)), ModelRole.Vision,
                    RolePickerActions(pick = { p, m -> picked += "$p $m" }, choose = { picked += "$it" }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.VISION_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).performClick()
        compose.onNodeWithText(ModelsCopy.OFF).performClick()
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        // 不按能力置灰
        compose.onNodeWithText("deepseek-coder").assertIsEnabled().performClick()
        compose.onNodeWithText("qwen-long").performScrollTo().performClick()
        assertEquals(listOf("${RoleChoice.SameAsMain}", "${RoleChoice.Off}", "p1 deepseek-coder", "p2 qwen-long"), picked)
    }

    @Test fun chatPickerHidesTtsModelsAndTtsOnlyProviders() {
        val mixed = deepseek.copy(
            models = deepseek.models.orEmpty() + ListedModel("speech-mixed"),
            ttsModels = setOf("speech-mixed"),
        )
        val speechOnly = deepseek.copy(
            id = "speech-only",
            name = "Local Voice",
            models = listOf(ListedModel("fishaudio/drama-3-int4")),
            ttsModels = setOf("fishaudio/drama-3-int4"),
        )
        val ui = connected().copy(providers = listOf(mixed, speechOnly))
        compose.setContent {
            MikaTheme {
                RolePickerContent(ui, ModelRole.Main, RolePickerActions(), onBack = {})
            }
        }
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        compose.onNodeWithText("deepseek-chat").assertIsDisplayed()
        compose.onNodeWithText("speech-mixed").assertDoesNotExist()
        compose.onNodeWithText("Local Voice").assertDoesNotExist()
        compose.onNodeWithText("fishaudio/drama-3-int4").assertDoesNotExist()
    }

    @Test fun lightPickerOffersOnlySameAsMain() {
        compose.setContent { MikaTheme { RolePickerContent(connected(), ModelRole.Light, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LIGHT_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF).assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.roleHint(ModelRole.Light)).assertIsDisplayed()
    }

    @Test fun pickerFiltersWhenThereAreManyModels() {
        val many = qwen.copy(models = (1..9).map { ListedModel("qwen-$it") })
        val ui = ModelSetupUi(form, providers = listOf(deepseek, many), configs = listOf(main), roles = RoleChoices(main = "c1"))
        compose.setContent { MikaTheme { RolePickerContent(ui, ModelRole.Main, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithTag("model-filter").performTextInput("qwen-9")
        // 筛选框里也有这几个字,只看不是输入框的那一行
        compose.onNode(hasText("qwen-9") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("qwen-1").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
    }

    @Test fun providerDetailPreviewsInUseModelsFirstAndLinksToAll() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(showAll = { got += "all" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.CONNECTED).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.KEY_SAVED).assertIsDisplayed()
        // 在用的排最前,只放 3 个
        compose.onNodeWithTag("model-qwen-vl-max").assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.roleName(ModelRole.Vision)).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-max").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-turbo").assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.viewAll(5)).performScrollTo().performClick()
        assertEquals(listOf("all"), got)
        // 删除前说清会影响哪个用途
        compose.onNodeWithText(ModelsCopy.DELETE).performScrollTo().performClick()
        compose.awaitOne(ModelsCopy.deleteBody(listOf(ModelRole.Vision)), inDialog = true).assertIsDisplayed()
    }

    @Test fun providerRetestConfirmsOneNamedModelBeforeCallingIt() {
        val provider = deepseek.copy(models = emptyList())
        val second = main.copy(id = "c3", model = "deepseek-other")
        val ui = connected(RoleChoices(main = main.id, light = RoleChoice.Model(second.id))).copy(
            providers = listOf(provider, qwen), configs = listOf(main, second),
        )
        val tested = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(ui, provider, ProviderDetailActions(retestModel = { tested += it }), onBack = {})
            }
        }
        val model = main.model
        val action = compose.onNodeWithTag("provider-retest-$model").performScrollTo()
        action.performClick()
        compose.awaitOne(appString(R.string.models_retest_confirm_title), inDialog = true).assertIsDisplayed()
        compose.onNode(hasText(model, substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertTrue(tested.isEmpty())
        compose.awaitOne(ModelSetupCopy.CANCEL, inDialog = true).performClick()
        assertTrue(tested.isEmpty())
        action.performClick()
        compose.onNodeWithTag("provider-confirm-retest").performClick()
        assertEquals(listOf(model), tested)
    }

    @Test fun tappingAModelOpensUseAsSheetAndAssigns() {
        val used = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(connected(), deepseek, ProviderDetailActions(use = { role, m -> used.add("$role $m") }), onBack = {})
            }
        }
        compose.onNodeWithTag("model-deepseek-coder").performClick()
        compose.awaitOne(ModelsCopy.useAsSubtitle("DeepSeek")).assertIsDisplayed()
        compose.awaitOne(ModelsCopy.roleName(ModelRole.Vision)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Vision deepseek-coder"), used)
    }

    @Test fun useAsSheetMarksTheCurrentRole() {
        compose.setContent { MikaTheme { ProviderDetailContent(connected(), deepseek, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithTag("model-deepseek-chat").performClick()
        compose.awaitOne(ModelsCopy.IN_USE).assertIsDisplayed()
        compose.awaitOne(ModelSetupCopy.CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ModelsCopy.IN_USE).assertDoesNotExist()
    }

    @Test fun useAsSheetCanToggleTtsIndependentlyForMultipleModels() {
        var provider by mutableStateOf(deepseek)
        val actions = ProviderDetailActions(setTts = { model, enabled ->
            provider = provider.copy(ttsModels = if (enabled) provider.ttsModels + model else provider.ttsModels - model)
            true
        })
        compose.setContent {
            MikaTheme { ProviderDetailContent(connected().copy(providers = listOf(provider)), provider, actions, onBack = {}) }
        }
        fun openRole(model: String) {
            compose.onNodeWithTag("model-$model").performScrollTo().performClick()
            compose.awaitOne(ModelsCopy.useAsSubtitle("DeepSeek")).assertIsDisplayed()
        }
        val tts = compose.onNodeWithTag("model-use-tts")
        openRole("deepseek-chat")
        compose.awaitOne(ModelsCopy.IN_USE).assertIsDisplayed()
        tts.assertIsNotSelected().performClick()
        compose.onNodeWithTag("use-as").assertDoesNotExist()
        openRole("deepseek-coder")
        tts.assertIsNotSelected().performClick()
        compose.onNodeWithTag("use-as").assertDoesNotExist()
        openRole("deepseek-chat")
        tts.assertIsSelected().assertTextContains(ModelsCopy.IN_USE).performClick()
        compose.onNodeWithTag("use-as").assertDoesNotExist()
        assertEquals(setOf("deepseek-coder"), provider.ttsModels)
    }

    @Test fun soleMarkedModelCanBeAssignedBackToMainFromProviderDetails() {
        val id = "fishaudio/drama-3-int4"
        var provider by mutableStateOf(deepseek.copy(models = listOf(ListedModel(id)), ttsModels = setOf(id)))
        var picked: ModelRole? by mutableStateOf(null)
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(
                    connected().copy(providers = listOf(provider), configs = emptyList(), roles = RoleChoices()),
                    provider, ProviderDetailActions(use = { role, model ->
                        picked = role
                        provider = provider.copy(ttsModels = provider.ttsModels - model)
                        true
                    }), onBack = {},
                )
            }
        }
        compose.onNodeWithTag("model-$id").performScrollTo().performClick()
        compose.onNodeWithTag("model-use-tts").assertIsSelected()
        compose.awaitOne(ModelsCopy.roleName(ModelRole.Main)).performClick()
        compose.onNodeWithTag("use-as").assertDoesNotExist()
        assertEquals(ModelRole.Main, picked)
        assertTrue(provider.ttsModels.isEmpty())
    }

    @Test fun providerWithoutModelListCanMarkAManualTtsModel() {
        val provider = deepseek.copy(models = emptyList())
        val marked = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(
                    connected().copy(providers = listOf(provider), configs = emptyList(), roles = RoleChoices()),
                    provider,
                    ProviderDetailActions(setTts = { model, enabled -> marked.add(model to enabled) }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("manual-model").performScrollTo().performTextInput("fishaudio/drama-3-int4")
        compose.onNodeWithTag("manual-model-use").performClick()
        compose.awaitOne(ModelsCopy.useAsSubtitle("DeepSeek")).assertIsDisplayed()
        compose.onNodeWithTag("model-use-tts").assertIsNotSelected().performClick()
        compose.onNodeWithTag("use-as").assertDoesNotExist()
        assertEquals(listOf("fishaudio/drama-3-int4" to true), marked)
    }

    @Test fun allModelsSearchesAndRefreshes() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                AllModelsContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(refresh = { got += "refresh" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.allModelsTitle("通义千问")).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertIsDisplayed()
        compose.onNodeWithTag("all-models-filter").performTextInput("plus")
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertDoesNotExist()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).performClick()
        assertEquals(listOf("refresh"), got)
    }

    @Test fun allModelsShowsRefreshInProgress() {
        val running = connected().copy(listing = mapOf(qwen.id to ListState.Running))
        compose.setContent { MikaTheme { AllModelsContent(running, qwen, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LISTING).assertIsDisplayed()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).assertIsNotEnabled()
    }

    // ---------- 用账号登录 ----------

    private val grok = Provider("p3", "xAI / Grok", ProviderConfig.Kind.Xai, "https://api.x.ai/v1", listOf(ListedModel("grok-4"), ListedModel("grok-4-fast")))

    @Test fun addFormListsAccountSignInsAboveApiKeys() {
        show(ModelSetupUi(form))
        compose.onNodeWithText(ModelsCopy.SIGN_IN_GROUP).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.KEY_GROUP).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("signin-chatgpt").performScrollTo().performClick()
        compose.onNodeWithTag("signin-xai").performScrollTo().performClick()
        compose.onNodeWithTag("signin-openrouter").performScrollTo().performClick()
        assertEquals(listOf("signin ChatGpt", "signin Xai", "signin OpenRouter"), calls)
    }

    @Test fun signedInAccountRowOpensTheProvider() {
        show(ModelSetupUi(form, formOpen = true, providers = listOf(grok), xaiAccount = SubscriptionAccount("me@example.com", signedIn = true)))
        compose.onNodeWithTag("signin-xai-signed-in", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("signin-openrouter-signed-in", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("signin-xai").performScrollTo().performClick()
        assertEquals(listOf("provider p3"), calls)
    }

    @Test fun xaiSheetWarnsFirstThenShowsTheCode() {
        val started = mutableListOf<String>()
        val actions = SignInSheetActions(start = { started += "start $it" }, cancel = { started += "cancel $it" }, useApiKey = { started += "key $it" })
        var ui by mutableStateOf(ModelSetupUi(form))
        compose.setContent { MikaTheme { SignInSheetBody(ui, SignInKind.Xai, actions) } }
        compose.onNodeWithText(ModelsCopy.signInTitle(SignInKind.Xai)).assertIsDisplayed()
        compose.onNodeWithTag("signin-risk").assertIsDisplayed()
        compose.onNodeWithTag("signin-start").performClick()
        compose.onNodeWithTag("signin-use-key").performClick()
        ui = ui.copy(accountSignIn = AccountSignInUi(AccountProvider.Xai, running = true, userCode = "KQ7M-P2XD", openUrl = "https://accounts.x.ai/device",
            expiresAtMs = System.currentTimeMillis() + 600_000))
        compose.onNodeWithTag("signin-code").assertTextContains("KQ7M-P2XD")
        compose.onNodeWithText(ModelsCopy.XAI_CODE_COPY_REOPEN).assertIsDisplayed()
        compose.onNodeWithTag("signin-cancel").performClick()
        assertEquals(listOf("start Xai", "key Xai", "cancel Xai"), started)
    }

    @Test fun openRouterSheetShowsFailureAndCanRetry() {
        val started = mutableListOf<String>()
        val ui = ModelSetupUi(form, accountSignIn = AccountSignInUi(AccountProvider.OpenRouter, failure = "已取消登录。"))
        compose.setContent { MikaTheme { SignInSheetBody(ui, SignInKind.OpenRouter, SignInSheetActions(start = { started += "$it" })) } }
        compose.onNodeWithText(ModelsCopy.signInStart(SignInKind.OpenRouter)).assertIsDisplayed()
        compose.onNodeWithTag("signin-risk").assertDoesNotExist()
        compose.onNodeWithTag("signin-failed").assertTextContains("已取消登录。")
        compose.onNodeWithTag("signin-start").performClick()
        assertEquals(listOf("OpenRouter"), started)
    }

    @Test fun signedInXaiDetailShowsAccountSetMainAndThe403Note() {
        val got = mutableListOf<String>()
        val ui = ModelSetupUi(form, providers = listOf(deepseek, grok), configs = listOf(main), roles = RoleChoices(main = "c1"),
            xaiAccount = SubscriptionAccount("me@example.com", signedIn = true))
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(ui, grok, ProviderDetailActions(useAsMain = { got += "main" }, signOut = { got += "out" }), onBack = {})
            }
        }
        compose.onNodeWithText("me@example.com").assertIsDisplayed()
        compose.onNodeWithTag("account-403-note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SET_MAIN).performScrollTo().performClick()
        compose.onNodeWithText(ModelsCopy.CHATGPT_SIGN_OUT).performScrollTo().performClick()
        assertEquals(listOf("main", "out"), got)
    }
}
