package com.m57.hermescontrol.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class HubPreviewContractTest {
    @Test
    fun hubPreviewDecodesProducerMarkdownThroughActualApiSignature() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(OkHttpProvider.json.asConverterFactory("application/json".toMediaType()))
                        .build().create(HermesApiService::class.java)
                server.enqueue(MockResponse().setBody("""{"name":"weather","skill_md":"# Weather"}"""))
                val body = api.previewHubSkill("hub:weather").body()!!
                assertEquals("# Weather", body.javaClass.getMethod("getSkillMd").invoke(body))
                val request = server.takeRequest()
                assertEquals("/api/skills/hub/preview", request.requestUrl!!.encodedPath)
                assertEquals("hub:weather", request.requestUrl!!.queryParameter("identifier"))
                server.enqueue(MockResponse().setBody("""{"name":"weather","content":"installed"}"""))
                assertEquals("installed", api.getSkillContent("weather").body()!!.content)
            } finally {
                server.shutdown()
            }
        }
}
