package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/** 对着本地服务核对目录请求与用户单独确认的模型试调用。 */
class ModelSetupNetworkTest {
    @get:Rule val texts = ChineseTexts()
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun modelList(vararg ids: String): MockResponse {
        val models = ids.joinToString(",") { """{"id":"$it"}""" }
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"object":"list","data":[$models]}""")
    }

    private fun setUp(kind: ProviderConfig.Kind = ProviderConfig.Kind.OpenAiCompat): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(kind)
        vm.setBaseUrl(server.url("/").toString().trimEnd('/'))
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun missingCatalogNeverFallsBackToAChatRequest() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("manual-model")
        vm.testAndWait()
        assertTrue(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())

        runBlocking {
            vm.saveWithoutTest()
            withTimeout(15_000) { vm.state.first { !it.showForm } }
        }
        val saved = store.providers.value.single()
        assertEquals(setOf("manual-model"), saved.typedModels)
        assertTrue(saved.verifiedModels.isEmpty())
        assertNull(store.roles.value.main)
        assertEquals(listOf("/models", "/models"), List(server.requestCount) { server.takeRequest().path })
    }

    @Test fun savingMixedCatalogThenChoosingMainDoesNotSendInferenceRequests() {
        server.enqueue(modelList("fishaudio/drama-3-int4", "opaque-model", "vendor/chat"))
        val (store, vm) = setUp()
        vm.testAndWait()
        val saved = store.providers.value.single()
        assertEquals(listOf("fishaudio/drama-3-int4", "opaque-model", "vendor/chat"), saved.models.orEmpty().map { it.id })
        assertNull(store.roles.value.main)
        assertTrue(vm.setTtsModel(saved.id, "opaque-model", true))
        runBlocking {
            vm.pick(ModelRole.Main, saved.id, "fishaudio/drama-3-int4")
            withTimeout(15_000) { vm.state.first { it.roles.main != null } }
        }

        assertEquals("fishaudio/drama-3-int4", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.setTtsModel(saved.id, "fishaudio/drama-3-int4", true))
        assertNull(store.configFor(ModelRole.Main))
        assertTrue(vm.useFromProvider(ModelRole.Main, saved.id, "fishaudio/drama-3-int4"))
        assertEquals("fishaudio/drama-3-int4", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("opaque-model"), store.provider(saved.id)?.ttsModels)
        assertTrue(store.provider(saved.id)!!.verifiedModels.isEmpty())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/models", request.path)
        assertEquals("GET", request.method)
        assertEquals("Bearer sk", request.getHeader("Authorization"))
    }

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}"""))
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse(vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        assertEquals(listOf("/models"), List(server.requestCount) { server.takeRequest().path })
    }

    @Test fun explicitlyTestingOneResponsesModelUsesOnlyItsEndpointAndModelId() {
        server.enqueue(modelList("gpt-x", "gpt-y"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
        ))
        val (store, vm) = setUp(ProviderConfig.Kind.OpenAiResponses)
        vm.testAndWait()
        val saved = store.providers.value.single()
        runBlocking {
            vm.pick(ModelRole.Main, saved.id, "gpt-y")
            withTimeout(15_000) { vm.state.first { it.roles.main != null } }
        }
        assertEquals(1, server.requestCount)
        assertTrue(store.provider(saved.id)!!.verifiedModels.isEmpty())
        runBlocking {
            vm.retestModel(saved.id, "gpt-y")
            withTimeout(15_000) { store.providers.first { it.single().verifiedModels == setOf("gpt-y") } }
        }

        assertEquals("gpt-y", store.configFor(ModelRole.Main)?.model)
        assertEquals(ProviderConfig.Kind.OpenAiResponses, store.configFor(ModelRole.Main)?.kind)
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(listOf("/models", "/responses"), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer sk" })
        val body = requests.last().body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-y\""))
        assertTrue(body.contains("\"name\":\"get_time\""))
    }
}
