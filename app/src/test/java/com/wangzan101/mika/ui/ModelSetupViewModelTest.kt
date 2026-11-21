package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.model.ApiKeyText
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ClientSetup
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** 按地址给出列模型的结果;没写的地址列出空表。 */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private val listHeaders = mutableListOf<Map<String, String>>()
    /** 下一次列模型抛这个;用来确认异常收成失败,不打掉测试协程。 */
    private var listBoom: Throwable? = null
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Mika/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, headers ->
            listBoom?.let { throw it }
            listCalls += Triple(kind, url, apiKey)
            listHeaders += headers
            listing[url] ?: ModelListResult.Ok(emptyList())
        },
    )

    /** 建过的模型(模型名、请求头等); 显式试调用的结果由 [reply] 决定。 */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun manuallyEnteredTtsModelsCannotBeTestedForChatRolesOrFallbacks() = runTest {
        val store = store()
        val provider = store.connect("Speech", ProviderConfig.Kind.OpenAiCompat, "https://tts.test/v1", null,
            listOf(ListedModel("speech-alias", speechOnly = true), ListedModel("tts-mark")))
        assertTrue(store.setTtsModel(provider.id, "tts-mark", true))
        val vm = vm(store)
        var picked = 0

        for ((model, role) in listOf("tts-mark", "speech-alias", "fishaudio/drama-3-int4").zip(ModelRole.entries)) {
            vm.pick(role, provider.id, model) { picked++ }
            runCurrent()
            assertTrue(vm.state.value.manualPick[provider.id] is ListState.Failed)
            vm.pickFallback(role, 0, provider.id, model) { picked++ }
            runCurrent()
            assertTrue(vm.state.value.manualPick[provider.id] is ListState.Failed)
        }

        assertEquals(0, picked)
        assertTrue(built.isEmpty())
        assertTrue(store.roles.value.referenced.isEmpty())
    }
    private val localSpeechUrl = "http://192.168.2.231:8091/v1"

    @Test fun settingsSavesTheCatalogWithoutTestingToolsOrSelectingAMainModel() = runTest {
        val models = listOf(ListedModel("fishaudio/drama-3-int4"), ListedModel("vendor/model-123"))
        listing[localSpeechUrl] = ModelListResult.Ok(models)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel("private-tts")
        vm.testAndSave()
        runCurrent()

        val p = store.providers.value.single()
        assertEquals(models, p.models)
        assertEquals(setOf("private-tts"), p.typedModels)
        assertTrue(p.verifiedModels.isEmpty())
        assertTrue(built.isEmpty())
        assertNull(store.roles.value.main)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun settingsCatalogErrorsAreShownEvenWithAManualModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel("fishaudio/drama-3-int4")
        for (failure in listOf(ListFailure.Auth, ListFailure.Network)) {
            listing[localSpeechUrl] = ModelListResult.Failed(failure)
            vm.testAndSave()
            runCurrent()

            assertEquals(ListState.Failed(failure.text), vm.state.value.form.test)
            assertTrue(store.providers.value.isEmpty())
            assertTrue(built.isEmpty())
        }
    }

    @Test fun settingsWithNoCatalogCanSaveAManualModelWithoutTesting() = runTest {
        listing[localSpeechUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("fishaudio/drama-3-int4")
        vm.testAndSave()
        runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(built.isEmpty())

        vm.saveWithoutTest()
        runCurrent()

        val p = store.providers.value.single()
        assertTrue(p.savedUntested)
        assertEquals(setOf("fishaudio/drama-3-int4"), p.typedModels)
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
    }

    @Test fun reconnectingSavesMixedCatalogAndKeepsTtsMarksWithoutProbing() = runTest {
        val models = listOf(
            ListedModel("fishaudio/drama-3-int4"),
            ListedModel("vendor/opaque-speech", unsupportedApis = listOf("/v1/audio/speech")),
            ListedModel("vendor/opaque-marked"),
            ListedModel("vendor/chat"),
        )
        listing[localSpeechUrl] = ModelListResult.Ok(models)
        val store = store()
        val p = store.connect("192.168.2.231", ProviderConfig.Kind.OpenAiCompat, localSpeechUrl, null, emptyList())
        assertTrue(store.setTtsModel(p.id, "vendor/opaque-marked", true))
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()

        assertTrue(built.isEmpty())
        assertNull(store.roles.value.main)
        val saved = store.providers.value.single()
        assertEquals(models, saved.models)
        assertEquals(setOf("vendor/opaque-marked"), saved.ttsModels)
    }

    @Test fun onboardingSavesAListedTtsProviderWithoutProbingOrPickingMain() = runTest {
        val model = ListedModel("fishaudio/drama-3-int4")
        listing[localSpeechUrl] = ModelListResult.Ok(listOf(model))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.testAndSave()
        runCurrent()

        assertEquals(listOf(model), store.providers.value.single().models)
        assertTrue(built.isEmpty())
        assertNull(store.roles.value.main)
        assertFalse(vm.state.value.canNext)
    }

    @Test fun onboardingDoesNotProbeAManuallyEnteredTtsModel() = runTest {
        val model = ListedModel("vendor/opaque-speech", unsupportedApis = listOf("/v1/audio/speech"))
        listing[localSpeechUrl] = ModelListResult.Ok(listOf(model, ListedModel("vendor/chat")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl(localSpeechUrl)
        vm.setManual(true)
        vm.setModel(model.id)
        vm.testAndSave()
        runCurrent()

        assertTrue(built.isEmpty())
        assertNull(store.roles.value.main)
        assertTrue(store.providers.value.single().verifiedModels.isEmpty())
    }

    @Test fun testAndSaveStoresProviderAndWaitsForTheUserToSelectMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("还没有服务商时表单展开", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        assertTrue(store.list().isEmpty())
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertFalse(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    @Test fun providerSessionHeaderIsRememberedWhenListingWithoutAChatProbe() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label)
        vm.setApiKey("k")
        vm.testAndSave()
        runCurrent()

        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
        assertTrue(listHeaders.single()[go.sessionHeader].orEmpty().isNotEmpty())
        assertTrue(built.isEmpty())
    }

    /** Cloudflare Workers AI 的目录不可用时，输入账号和模型 ID 并直接保存。 */
    @Test fun cloudflarePresetFullFlowFromAccountIdToSavedProvider() = runTest {
        val cf = ProviderPresets.all.first { it.label == "Cloudflare Workers AI" }
        val store = store()
        val vm = vm(store)

        vm.selectPreset(cf.label)
        var form = vm.state.value.form
        assertTrue(form.isCloudflare)
        assertFalse("Cloudflare 单独展示 Account ID 输入项", form.urlEditable)
        assertFalse(form.canTest)
        assertEquals(tr(com.wangzan101.mika.R.string.model_url_account_id), form.urlProblem)

        // 用户输入 Account ID 与 API Key
        val actualUrl = "https://api.cloudflare.com/client/v4/accounts/my_acc_123/ai/v1"
        vm.setAccountId("my_acc_123")
        vm.setApiKey("cf_token_xyz")
        form = vm.state.value.form
        assertEquals("my_acc_123", form.accountId)
        assertEquals(actualUrl, form.baseUrl)
        assertNull(form.urlProblem)
        assertTrue(form.canTest)

        // Cloudflare 不提供列模型接口 (GET /models 返回 405/NotSupported)
        listing[actualUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()

        form = vm.state.value.form
        assertTrue(form.noList)
        assertFalse("Requires model name to probe", form.canTest)

        // 用户输入模型名 @cf/openai/gpt-oss-20b
        vm.setModel("@cf/openai/gpt-oss-20b")
        assertTrue(vm.state.value.form.canTest)

        vm.saveWithoutTest(); runCurrent()
        assertFalse("保存后关闭表单", vm.state.value.showForm)
        val saved = store.providers.value.single()
        assertEquals("Cloudflare Workers AI (my_a…_123)", saved.name)
        assertEquals(actualUrl, saved.baseUrl)
        assertEquals(setOf("@cf/openai/gpt-oss-20b"), saved.typedModels)
        assertTrue(saved.verifiedModels.isEmpty())
        assertNull(store.roles.value.main)
        assertTrue(built.isEmpty())
    }

    /** 自定义填了某家的官方地址:建议改用那家的预设;地址就是预设的、接口类型不同时一并说;改用时 Key 留着。 */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "这是「Gemini」的官方地址，用它的预设更省事：地址和接口类型都已填好。接口类型也对不上：「Gemini」用的是 Gemini 接口，这里选的是 OpenAI 兼容。",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // 同一主机的别的路径(如它的兼容接口):照样建议,但不说接口类型不对
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("选了预设就不再建议", after.suggestedPreset)
    }

    @Test fun manualPicksSaveRolesAndFallbacksWithoutMakingInferenceCalls() = runTest {
        val store = store()
        val provider = store.connect("Local", ProviderConfig.Kind.OpenAiCompat, "https://chat.test/v1", "", emptyList())
        val vm = vm(store)
        var picked = 0
        vm.pick(ModelRole.Main, provider.id, " qwen3 ") { picked++ }
        vm.pickFallback(ModelRole.Main, 0, provider.id, "ignored-while-running") { picked++ }
        runCurrent()
        vm.pickFallback(ModelRole.Main, 0, provider.id, "qwen-backup") { picked++ }
        runCurrent()

        assertEquals(2, picked)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(listOf("qwen-backup"), store.list().filter { it.id in store.roles.value.mainFallbacks }.map { it.model })
        assertEquals(setOf("qwen3", "qwen-backup"), store.provider(provider.id)?.typedModels)
        assertEquals(emptySet<String>(), store.provider(provider.id)?.verifiedModels)
        assertTrue(built.isEmpty())
        assertTrue(probes.isEmpty())
    }

    /** 旧版本存下的不提供列表的服务商(手填的模型从没试过):提醒,「重新测试」通了提醒消失、记下;不通说原因。 */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        store.use(ModelRole.Light, p.id, "backup", ProviderPresets.custom.capabilities)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retestModel(p.id, "gemini"); runCurrent()
        assertEquals(
            ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 gemini-flash-latest）。"),
            vm.state.value.retest[p.id to "gemini"],
        )
        assertEquals(setOf("gemini", "backup"), vm.state.value.unverified(vm.state.value.providers.single()).toSet())
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retestModel(p.id, "gemini"); runCurrent()
        assertNull(vm.state.value.retest[p.id to "gemini"])
        assertEquals(listOf("backup"), vm.state.value.unverified(vm.state.value.providers.single()))
        assertEquals(listOf("gemini", "gemini"), built.map { it.first })
        // 有列表的服务商不提醒
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** 明文 http:本机、局域网的不提示;别的地址提醒一句,照样能测试。 */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("这个地址不加密，API Key 和对话内容可能被同一网络里的人看到。", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("通义千问")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "通义千问"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("第二家不改主模型", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        vm.openForm(); vm.selectPreset("通义千问"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "通义千问" }
        // 选择页列出所有服务商的全部模型,不按能力过滤
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("选了看图就记看图", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // 用主模型:看图这一路用主模型,不再有单独的看图配置
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // 不用
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("没人用的配置删掉", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("自定义的 OpenAI 兼容接口可以不填 Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // 服务不提供列表:先要一个模型名,没存
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // 可直接保存手填模型。下一步需要明确选一个主模型。
        vm.setModel("chatty"); vm.saveWithoutTest(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        assertTrue(built.isEmpty())
        assertEquals(setOf("qwen3", "chatty"), vm.state.value.options(vm.state.value.providers.single()).map { it.id }.toSet())
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("已有的配置保留它的能力", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失败时保留原来的列表", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.mika.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.mika.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("工具调用 · 未确认" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("工具调用" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("不支持工具调用" to false), ModelSetupCopy.tags(o[2]))
    }

    /** 自定义服务商可以起名字(去掉首尾空白);不填用地址的主机名。同一个地址起不同的名字是两家。 */
    @Test fun customProviderUsesTheGivenNameOrFallsBackToTheHost() = runTest {
        val url = "https://relay.example.com/v1"
        listing[url] = ModelListResult.Ok(listOf(ListedModel("m1")))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k1")
        assertEquals("relay.example.com", vm.state.value.form.defaultName)
        vm.setName("  我的中转  ")
        vm.testAndSave(); runCurrent()
        assertEquals(listOf("我的中转"), store.providers.value.map { it.name })
        assertEquals("", vm.state.value.form.name)

        vm.openForm()
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k2"); vm.setName("   ")
        vm.testAndSave(); runCurrent()
        val names = store.providers.value.map { it.name }
        assertEquals(listOf("我的中转", "relay.example.com"), names)
        assertEquals(listOf("k1", "k2"), store.providers.value.map { store.providerKey(it.id) })
    }

    /** 名字有长度上限;换成预设时清掉;地址是某家预设的,默认名就是预设名;预设的名字不受表单里的名字影响。 */
    @Test fun customNameIsCappedAndOnlyAppliesToCustom() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setName("名".repeat(ModelSetupViewModel.MAX_NAME + 8))
        assertEquals(ModelSetupViewModel.MAX_NAME, vm.state.value.form.name.length)
        // 按字符截:emoji 不被截成半个
        vm.setName("😀".repeat(ModelSetupViewModel.MAX_NAME + 3))
        val capped = vm.state.value.form.name
        assertEquals(ModelSetupViewModel.MAX_NAME, capped.codePointCount(0, capped.length))
        assertEquals("😀".repeat(ModelSetupViewModel.MAX_NAME), capped)
        vm.setBaseUrl(deepseekUrl)
        assertEquals("DeepSeek", vm.state.value.form.defaultName)
        vm.selectPreset("DeepSeek")
        assertEquals("", vm.state.value.form.name)
        assertEquals("DeepSeek", vm.state.value.form.copy(name = "别的名字").providerName)
    }

    /** 列模型出错时显示原因，手填后再测试仍只查询目录。 */
    @Test fun listErrorShowsTheReasonAndOpensTheModelField() = runTest {
        for ((i, failure) in listOf(ListFailure.Auth, ListFailure.Server, ListFailure.BadResponse).withIndex()) {
            val url = "https://relay$i.example.com/v1"
            listing[url] = ModelListResult.Failed(failure)
            val store = store()
            val vm = vm(store)
            vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
            vm.testAndSave(); runCurrent()
            val f = vm.state.value.form
            assertEquals(ListState.Failed(failure.text), f.test)
            assertTrue("出错后可以手填", f.showModel)
            assertTrue(store.providers.value.isEmpty())
            vm.setModel("plan-model"); vm.testAndSave(); runCurrent()
            assertEquals(ListState.Failed(failure.text), vm.state.value.form.test)
            assertTrue(store.providers.value.isEmpty())
            assertTrue(built.isEmpty())
            vm.closeForm()
        }
    }

    /** 列表里已有该模型时沿用目录记录。 */
    @Test fun typedModelThatIsListedIsNotMarkedTyped() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.setManual(true); vm.setModel("deepseek-reasoner"); vm.testAndSave(); runCurrent()
        assertTrue(built.isEmpty())
        val p = store.providers.value.single()
        assertEquals(emptySet<String>(), p.typedModels)
        assertNull(store.roles.value.main)
        // 收起「手动填写」就不用填的那个
        vm.openForm(); vm.setApiKey("sk"); vm.setManual(true); vm.setModel("x"); vm.setManual(false)
        assertEquals("", vm.state.value.form.typedModel)
    }

    /** 选择页里有列表的那家也能手填;通了记为手填,刷新列表后还在,标「手填」。 */
    @Test fun pickerAllowsTypingAModelForAListedProvider() = runTest {
        val store = store()
        val p = store.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk", deepseek.models)
        val vm = vm(store)
        runCurrent()
        vm.pick(ModelRole.Light, p.id, "deepseek-v9"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
        assertEquals("存进文件", setOf("deepseek-v9"), store().provider(p.id)?.typedModels)
        store.rememberModels(p.id, deepseek.models)
        vm.pick(ModelRole.Light, p.id, "deepseek-chat"); runCurrent()
        val options = vm.state.value.options(store.provider(p.id)!!)
        assertTrue(options.single { it.id == "deepseek-v9" }.typed)
        assertFalse(options.single { it.id == "deepseek-chat" }.typed)
        // 选列表里的不记为手填
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals(setOf("deepseek-v9"), store.provider(p.id)?.typedModels)
    }

    // ---------- 不测试,直接保存 ----------

    /** 一次网络都不打:不列模型、不试调用,只存下地址、Key 和「没测过」这件事。 */
    @Test fun saveWithoutTestStoresProviderWithoutListingOrProbe() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.saveWithoutTest(); runCurrent()
        assertTrue("一次列模型都没调", listCalls.isEmpty())
        assertTrue("一次试调用都没有", built.isEmpty())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals("sk-1", store.providerKey(p.id))
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(emptySet<String>(), p.verifiedModels)
        assertTrue("标成没测过", p.savedUntested)
        // 存进文件也在
        assertTrue(store().provider(p.id)!!.savedUntested)
        // 留给用户明确选择主模型。
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue("存好后说清它没测过", s.probeNote?.contains(p.name) == true)
    }

    @Test fun savedManualModelCanBeExplicitlySelectedAfterReloadWithoutAProbe() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk")
        vm.setManual(true)
        vm.setModel("deepseek-v9")
        vm.saveWithoutTest()
        runCurrent()
        assertTrue(listCalls.isEmpty())
        val p = store.providers.value.single()
        assertEquals(setOf("deepseek-v9"), p.typedModels)
        assertNull(store.roles.value.main)

        val reloaded = store()
        val again = vm(reloaded)
        again.pick(ModelRole.Main, p.id, "deepseek-v9")
        runCurrent()

        assertEquals("deepseek-v9", reloaded.configFor(ModelRole.Main)?.model)
        assertTrue(reloaded.provider(p.id)!!.savedUntested)
        assertTrue(reloaded.provider(p.id)!!.verifiedModels.isEmpty())
        assertTrue(built.isEmpty())
    }

    /** 已经有主模型时跳过测试存下另一家:主模型不变。 */
    @Test fun saveWithoutTestKeepsTheCurrentMainModel() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        vm.pick(ModelRole.Main, store.providers.value.single().id, "deepseek-chat"); runCurrent()
        val main = store.configFor(ModelRole.Main)
        assertEquals("deepseek-chat", main?.model)
        vm.openForm(); vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://127.0.0.1:11434/v1"); vm.setManual(true); vm.setModel("local-model")
        vm.saveWithoutTest(); runCurrent()
        assertEquals(main, store.configFor(ModelRole.Main))
    }

    /** 地址格式不对、或该填 Key 的没填,什么都不存。 */
    @Test fun saveWithoutTestRefusesAnInvalidForm() = runTest {
        val store = store()
        val vm = vm(store)
        assertFalse("还没填 Key", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("api.deepseek.com")
        vm.setApiKey("sk")
        assertFalse("地址没写协议", vm.state.value.form.canSaveUntested)
        vm.saveWithoutTest(); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        // 自己跑的服务不要 Key,可以存
        vm.setBaseUrl("http://127.0.0.1:11434/v1")
        vm.setApiKey("")
        assertTrue(vm.state.value.form.canSaveUntested)
    }

    /** 已经测过的那家再跳过测试存一次:服务商不重复,Key 换成新的,已核实过的模型留着。 */
    @Test fun saveWithoutTestKeepsExistingProviderAndItsVerifiedModels() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        store.markVerified(p.id, "deepseek-chat")
        assertFalse(p.savedUntested)
        vm.openForm(); vm.setApiKey("sk-2"); vm.saveWithoutTest(); runCurrent()
        assertEquals("不重复添加", 1, store.providers.value.size)
        val after = store.provider(p.id)!!
        assertEquals("sk-2", store.providerKey(p.id))
        assertEquals("列过的列表留着", listOf("deepseek-chat", "deepseek-reasoner", "deepseek-coder"), after.models!!.map { it.id })
        assertEquals(setOf("deepseek-chat"), after.verifiedModels)
        assertFalse("核实过的模型让这家不算没测过", after.savedUntested)
    }

    // ---------- 粘贴进来的 Key 里有换行、零宽或非 ASCII ----------

    /** 中间的换行原样留下,立刻标红,两个保存动作都不发请求、也不存。 */
    @Test fun newlineInTheKeyIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\ncd")
        val form = vm.state.value.form
        assertEquals("sk-ab\ncd", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    /** `\r` 和 `\r\n` 同样留下,不改写,也不崩溃。 */
    @Test fun crlfInTheKeyIsRejectedAndDoesNotCrash() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-ab\r\ncd")
        assertEquals("sk-ab\r\ncd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        vm.setApiKey("sk-\rcd")
        assertEquals("sk-\rcd", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** 中文前缀留下,立刻标红,测试和直接保存都不发请求、也不存。改掉之后提示跟着消失。 */
    @Test fun chinesePrefixIsRejectedAndNotSaved() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-abc123")
        val form = vm.state.value.form
        assertEquals("密钥：sk-abc123", form.apiKey)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, form.keyNotice)
        assertFalse(form.canTest)
        assertFalse(form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(built.isEmpty())
        assertTrue(store.providers.value.isEmpty())
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("sk-abc123")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
    }

    /** 清空等于把整段删掉:红字和「去掉不可见字符」都收起,空着时两个保存动作仍不可用。 */
    @Test fun clearingTheKeyDropsHintsAndLeavesSaveDisabled() = runTest {
        val store = store()
        val vm = vm(store)
        vm.setApiKey("密钥：sk-\nabc")
        assertEquals(ApiKeyText.Notice.IllegalCharacters, vm.state.value.form.keyNotice)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
        vm.testAndSave(); runCurrent()
        vm.saveWithoutTest(); runCurrent()
        assertTrue(listCalls.isEmpty())
        assertTrue(store.providers.value.isEmpty())

        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.setApiKey("")
        assertEquals("", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.form.canSaveUntested)
    }

    /** 零宽空格去掉后按普通 Key 用,不标成非法字符。 */
    @Test fun zeroWidthSpaceIsStrippedAndTreatedAsANormalKey() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-\u200Babc")
        assertEquals("sk-abc", vm.state.value.form.apiKey)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-abc", listCalls.single().third)
        assertEquals("sk-abc", store.providerKey(store.providers.value.single().id))
    }

    /** 中间的普通空格不是非法字符:照旧拿去问服务商,失败走原来的「连接失败」。 */
    @Test fun spaceInsideTheKeyStillHitsTheProvider() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1 2")
        assertEquals(ApiKeyText.Notice.None, vm.state.value.form.keyNotice)
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("sk-1 2", listCalls.single().third)
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** 换 Key 走同一套:换行和中文前缀都不保存、也不重新列模型。零宽去掉后可以换。 */
    @Test fun changeKeyRefusesNewlinesAndIllegalCharacters() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        val listed = listCalls.size
        vm.changeKey(id, "sk-\n2"); runCurrent()
        assertEquals("换行的 Key 不存", "sk", store.providerKey(id))
        assertEquals("换行的 Key 不重新列模型", listed, listCalls.size)
        vm.changeKey(id, "密钥：sk-3"); runCurrent()
        assertEquals("没存非法的 Key", "sk", store.providerKey(id))
        assertEquals("非法的 Key 不重新列模型", listed, listCalls.size)
        vm.changeKey(id, "sk-\u200B2"); runCurrent()
        assertEquals("sk-2", store.providerKey(id))
        assertEquals(listed + 1, listCalls.size)
        assertEquals("sk-2", listCalls.last().third)
    }

    /** 列模型时头值仍抛异常:收成失败,不打掉协程,也不把异常原文(可能含 Key)露出去。 */
    @Test fun listingExceptionDoesNotCrashAndHidesTheHeaderValue() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val id = store.providers.value.single().id
        listBoom = IllegalArgumentException("Unexpected char 0x0a in Authorization value: Bearer sk-\nabc")
        vm.refresh(id); runCurrent()
        val failed = vm.state.value.listing[id] as ListState.Failed
        assertEquals("Key 里有无法使用的字符，请只粘贴密钥本身。", failed.message)
        assertFalse(failed.message.contains("Bearer"))
        assertEquals("sk", store.providerKey(id))
    }
}
