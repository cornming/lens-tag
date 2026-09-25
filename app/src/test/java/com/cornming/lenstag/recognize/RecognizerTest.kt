package com.cornming.lenstag.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 兩種辨識器共用的回覆解析、提示詞、以及模型調度邏輯。 */
class RecognizerTest {

    @Test
    fun `parses the agreed primary pipe secondary format`() {
        val result = parseLabelResponse("門|door")
        assertEquals("門", result?.primary)
        assertEquals("door", result?.secondary)
    }

    @Test
    fun `tolerates a model that ignores the format and returns just one word`() {
        // 小模型常常不照格式回，這時候至少要把它講的當成原文用，不能整個丟掉
        val result = parseLabelResponse("門")
        assertEquals("門", result?.primary)
        assertNull(result?.secondary)
    }

    @Test
    fun `trims whitespace the model padded around each part`() {
        val result = parseLabelResponse("  門 |  door  ")
        assertEquals("門", result?.primary)
        assertEquals("door", result?.secondary)
    }

    @Test
    fun `blank or null responses yield null rather than an empty label`() {
        assertNull(parseLabelResponse(null))
        assertNull(parseLabelResponse(""))
        assertNull(parseLabelResponse("   "))
        assertNull(parseLabelResponse("|"))
    }

    @Test
    fun `prompt asks for the bilingual format only when a language is given`() {
        assertTrue(buildPrompt("English").contains("English"))
        assertTrue(buildPrompt("English").contains("|"))
        assertTrue(!buildPrompt(null).contains("|"))
        assertTrue(!buildPrompt("").contains("|"))
    }
}

class AzureSettingsTest {

    private val full = AzureSettings(
        endpoint = "https://example.services.ai.azure.com/models/chat/completions?api-version=x",
        apiKey = "secret",
        simpleModel = "fast-model",
        complexModel = "strong-model",
    )

    @Test
    fun `routes simple and complex tasks to their own models`() {
        assertEquals("fast-model", full.modelFor(RecognitionTask.SIMPLE))
        assertEquals("strong-model", full.modelFor(RecognitionTask.COMPLEX))
    }

    @Test
    fun `falls back to the simple model when no complex model is configured`() {
        // 使用者只想填一個模型也要能正常運作
        val onlySimple = full.copy(complexModel = "")
        assertEquals("fast-model", onlySimple.modelFor(RecognitionTask.SIMPLE))
        assertEquals("fast-model", onlySimple.modelFor(RecognitionTask.COMPLEX))
    }

    @Test
    fun `is not usable until endpoint, key and model are all filled in`() {
        assertTrue(full.isUsable())
        assertTrue(!full.copy(endpoint = "").isUsable())
        assertTrue(!full.copy(apiKey = "").isUsable())
        assertTrue(!full.copy(simpleModel = "").isUsable())
    }
}

class AzureRequestTest {

    @Test
    fun `request body carries the model, prompt and image in OpenAI chat format`() {
        val body = buildRequestBody(
            model = "gpt-4o-mini",
            prompt = "這是什麼？",
            imageDataUrl = "data:image/jpeg;base64,AAAA",
        )
        assertTrue(body.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(body.contains("這是什麼？"))
        assertTrue(body.contains("image_url"))
        assertTrue(body.contains("data:image/jpeg;base64,AAAA"))
    }

    @Test
    fun `extracts the assistant message out of a chat completions response`() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":"門|door"}}]}
        """.trimIndent()
        assertEquals("門|door", extractContent(response))
    }

    @Test
    fun `malformed or empty responses yield null instead of throwing`() {
        assertNull(extractContent("not json"))
        assertNull(extractContent("""{"choices":[]}"""))
        assertNull(extractContent("""{"error":{"message":"bad request"}}"""))
    }

    @Test
    fun `request body never sends parameters that reasoning models reject`() {
        // 回歸防護：GPT-5 系列等推理模型在 chat completions 不支援 max_tokens
        // 和 temperature，帶了會直接回 400。之後誰想「順手」加回去，這裡會擋住。
        val body = buildRequestBody("gpt-5", "prompt", "data:image/jpeg;base64,AAAA")
        assertTrue(!body.contains("max_tokens"))
        assertTrue(!body.contains("temperature"))
    }

    @Test
    fun `pulls the human-readable message out of an Azure error body`() {
        val body = """{"error":{"code":"DeploymentNotFound","message":"The API deployment for this resource does not exist."}}"""
        assertEquals("The API deployment for this resource does not exist.", extractErrorMessage(body))
    }

    @Test
    fun `error message extraction tolerates garbage and empty bodies`() {
        assertNull(extractErrorMessage(null))
        assertNull(extractErrorMessage(""))
        assertNull(extractErrorMessage("<html>502 Bad Gateway</html>"))
        assertNull(extractErrorMessage("""{"unexpected":"shape"}"""))
    }
}

class HttpStatusMappingTest {

    @Test
    fun `auth failures point at the key`() {
        assertEquals(FailureReason.AUTH, reasonForHttpStatus(401))
        assertEquals(FailureReason.AUTH, reasonForHttpStatus(403))
    }

    @Test
    fun `404 points at the endpoint or deployment name`() {
        assertEquals(FailureReason.NOT_FOUND, reasonForHttpStatus(404))
    }

    @Test
    fun `429 is Azure-side quota, reported as rate limited`() {
        assertEquals(FailureReason.RATE_LIMITED, reasonForHttpStatus(429))
    }

    @Test
    fun `malformed requests are reported as rejected`() {
        assertEquals(FailureReason.BAD_REQUEST, reasonForHttpStatus(400))
        assertEquals(FailureReason.BAD_REQUEST, reasonForHttpStatus(422))
    }

    @Test
    fun `any 5xx is a server problem, not the user's configuration`() {
        assertEquals(FailureReason.SERVER, reasonForHttpStatus(500))
        assertEquals(FailureReason.SERVER, reasonForHttpStatus(503))
        assertEquals(FailureReason.SERVER, reasonForHttpStatus(599))
    }

    @Test
    fun `unrecognised codes fall back to unknown rather than guessing`() {
        assertEquals(FailureReason.UNKNOWN, reasonForHttpStatus(418))
    }
}
