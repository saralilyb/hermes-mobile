// Modified from Hy4ri/hermes-mobile for this fork; see NOTICE.

package com.m57.hermescontrol.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.AttachmentSource
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.GatewayFile
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.data.remote.GatewayFileResult
import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.JsonRpcError
import com.m57.hermescontrol.data.ws.PrivilegedRequestBinding
import com.m57.hermescontrol.data.ws.ServerRequestBinding
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fakes.FakeChatPersistenceRepository
import com.m57.hermescontrol.ui.chat.fakes.FakeSlashUsageStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed, value-free text the VM shows when a secret-bearing verb is not acknowledged. */
private const val PRIVILEGED_FAILURE_TEXT = "Hermes did not acknowledge that. The request is still waiting."

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockEventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    private val mockConnectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    private lateinit var app: Application
    private lateinit var fakeRepo: FakeChatPersistenceRepository
    private lateinit var fakeSlashUsageStore: FakeSlashUsageStore
    private lateinit var mockApi: com.m57.hermescontrol.data.remote.HermesApiService

    /** Counter used to generate unique WS request IDs. */
    private var reqCount = 0
    private val sentRequestIds = mutableMapOf<String, MutableList<String>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main
        reqCount = 0
        sentRequestIds.clear()

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        every { AuthManager.getPinnedModels() } returns emptyList()
        mockkObject(HermesWsClient)
        mockkObject(ApiClient)
        mockkObject(HermesDatabase)

        app = mockk(relaxed = true)
        every { app.getString(R.string.chat_model_switch_failed, any()) } answers {
            val formatArgs = arg<Array<out Any>>(1)
            "Failed to switch model: ${formatArgs.first()}"
        }
        every { app.getString(R.string.chat_privileged_not_acknowledged) } returns PRIVILEGED_FAILURE_TEXT
        fakeRepo = FakeChatPersistenceRepository()
        fakeSlashUsageStore = FakeSlashUsageStore()
        every { AuthManager.getSelectedProfileId() } returns "profile-a"

        mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.isTypingEffectEnabled() } returns true
        every { AuthManager.getTypingEffectDelayMs() } returns 30
        every { AuthManager.isAutoReconnect() } returns false
        every { HermesWsClient.events } returns mockEventsFlow
        every { HermesWsClient.connectionStatus } returns mockConnectionStatus
        every { HermesWsClient.connect() } answers {
            mockConnectionStatus.value = ConnectionStatus.CONNECTING
        }
        every { HermesWsClient.disconnect() } returns Unit

        // Default send stub: generates unique IDs and invokes onSent callback
        every { HermesWsClient.send(any(), any(), any()) } answers {
            reqCount++
            val id = "req-id-$reqCount"
            sentRequestIds.getOrPut(arg(0)) { mutableListOf() } += id
            arg<((String) -> Unit)?>(2)?.invoke(id)
            id
        }
        every { HermesWsClient.sendMessageForConnection(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-msg-$reqCount"
            arg<((String) -> Unit)?>(3)?.invoke(id)
            true
        }
        every { HermesWsClient.sendRedirectForConnection(any(), any(), any(), any()) } answers {
            reqCount++
            val id = "req-redirect-$reqCount"
            arg<((String) -> Unit)?>(3)?.invoke(id)
            true
        }
        every { HermesWsClient.respondToClarify(any(), any(), any(), any(), any(), any()) } returns true
        every { HermesWsClient.respondToServerRequest(any(), any()) } returns true
        every { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) } returns
            CompletableDeferred<Any?>(mapOf("ok" to true))
        every { HermesWsClient.connectionBinding("profile-a") } returns mockk()
        every { HermesWsClient.isConnectionBindingCurrent(any()) } returns true
        every { HermesWsClient.requestForConnection(any(), any(), any(), any()) } returns
            CompletableDeferred<Any?>(mapOf("ok" to true))

        // Default: the gateway acknowledges. Tests that care about the ack
        // boundary re-stub this via capturePrivileged().
        every { HermesWsClient.privilegedRequest(any(), any(), any()) } returns
            CompletableDeferred<Any?>(mapOf("status" to "ok"))

        // Stub model-options so preloadModelOptions() (fired at GatewayReady) is safe.
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getModelInfo() } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.ModelInfoResponse(
                    model = "gpt-5.6-sol",
                    provider = "openai-codex",
                    effectiveContextLength = 272_000L,
                ),
            )
        coEvery {
            mockApi.getModelOptions(any(), any())
        } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.ModelOptionsResponse(
                    providers =
                        listOf(
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4o", "gpt-4o-mini"),
                            ),
                            com.m57.hermescontrol.data.model.ModelProvider(
                                slug = "anthropic",
                                name = "Anthropic",
                                models = listOf("claude-3-5-sonnet"),
                            ),
                        ),
                ),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun cachedEntity(
        sessionId: String,
        content: String,
        id: String = "cache-$content",
    ) = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = "assistant",
        content = content,
        timestamp = 1L,
    )

    private fun serverMessages(content: String) =
        retrofit2.Response.success(
            com.m57.hermescontrol.data.model.SessionMessagesResponse(
                messages =
                    listOf(
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = 1,
                            role = "assistant",
                            content = content,
                        ),
                    ),
                pagination =
                    com.m57.hermescontrol.data.model.SessionMessagePagination(
                        limit = 150,
                        offset = 0,
                        order = "latest",
                        returned = 1,
                        total = 1,
                    ),
            ),
        )

    /** Create a ViewModel with the fake repo injected directly. */
    private fun createViewModel(startCleanup: Boolean = false): ChatViewModel =
        ChatViewModel(app, startCleanup, fakeRepo, fakeSlashUsageStore, testDispatcher)

    /**
     * Create ViewModel, simulate GatewayReady, feed SESSION_CREATE result,
     * and return a Pair(viewModel, sessionId).
     *
     * Captures the real session.create ID so unrelated startup requests cannot
     * make this helper accidentally resolve the wrong pending callback.
     */
    private suspend fun TestScope.createViewModelWithSession(
        startCleanup: Boolean = false,
    ): Pair<ChatViewModel, String> {
        val viewModel = createViewModel(startCleanup)
        advanceUntilIdle()

        mockConnectionStatus.value = ConnectionStatus.CONNECTED
        mockEventsFlow.emit(WsEvent.GatewayReady(null))
        advanceUntilIdle()

        val createRequestId = sentRequestIds[WsMethods.SESSION_CREATE]?.lastOrNull()
        checkNotNull(createRequestId) { "session.create was not sent" }
        mockEventsFlow.emit(WsEvent.RpcResult(createRequestId, mapOf("session_id" to "session-123")))
        advanceUntilIdle()

        // Sanity check: confirm the session was actually set
        val session = viewModel.uiState.value.currentSessionId
        checkNotNull(session) {
            "createViewModelWithSession: session was not set — " +
                "$createRequestId did not resolve SESSION_CREATE."
        }

        return Pair(viewModel, "session-123")
    }

    private suspend fun TestScope.createPaginatedViewModel(): ChatViewModel {
        val (viewModel, _) = createViewModelWithSession()
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionListResponse(
                    sessions = listOf(com.m57.hermescontrol.data.model.SessionInfo(id = "paged", message_count = 300)),
                ),
            )
        coEvery { mockApi.getSessionMessages("paged", 150, 0, true, "latest") } returns
            retrofit2.Response.success(
                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                    messages =
                        listOf(
                            com.m57.hermescontrol.data.model.SessionMessage(
                                id = 200,
                                role = "assistant",
                                content = "recent",
                            ),
                        ),
                    pagination =
                        com.m57.hermescontrol.data.model.SessionMessagePagination(
                            limit = 150,
                            offset = 0,
                            order = "latest",
                            returned = 150,
                            total = 300,
                        ),
                ),
            )
        viewModel.switchSession("paged")
        advanceUntilIdle()
        check(viewModel.uiState.value.hasOlderMessages)
        return viewModel
    }

    // ── Slash command tests ──────────────────────────────────────────────────

    @Test
    fun connectionRowsNeverEnterTranscript() =
        runTest {
            val (vm, _) = createViewModelWithSession()
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()
            assertFalse(
                vm.uiState.value.messages.any {
                    it.role == MessageRole.SYSTEM && it.content in setOf("Connected to Hermes", "Session resumed")
                },
            )
        }

    @Test
    fun testUndoCommand_replacesCompleteTranscriptCacheAndPrefillsComposer() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            viewModel.addAttachment("content://safe", "safe.txt", "text/plain", 4)
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(
                    any(),
                    WsMethods.COMMAND_DISPATCH,
                    mapOf("name" to "undo", "arg" to "2", "session_id" to sessionId),
                    any(),
                )
            } returns undoResult
            val pageOffsets = mutableListOf<Int>()
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), true, "latest") } answers {
                val offset = arg<Int>(2)
                pageOffsets += offset
                val content = if (offset == 0) "kept recent" else "kept archived"
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = if (offset == 0) 20 else 10,
                                    role = "assistant",
                                    content = content,
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                limit = 150,
                                offset = offset,
                                order = "latest",
                                returned = if (offset == 0) 150 else 1,
                                total = 151,
                            ),
                    ),
                )
            }

            viewModel.sendMessage("/undo 2")
            runCurrent()
            undoResult.complete(
                mapOf(
                    "type" to "prefill",
                    "message" to "undone prompt",
                    "notice" to "Undid 2 turns",
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf(0, 150), pageOffsets)
            assertEquals(
                listOf("kept archived", "kept recent", "Undid 2 turns"),
                viewModel.uiState.value.messages.map { it.content },
            )
            assertEquals("undone prompt", viewModel.uiState.value.pendingPrefillText)
            assertEquals(
                setOf("kept archived", "kept recent", "Undid 2 turns"),
                fakeRepo.loadMessages(sessionId).map { it.content }.toSet(),
            )
            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)
        }

    @Test
    fun fullRefreshDoesNotRetireAcceptedUndoReconciliation() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult
            var historyCalls = 0
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), true, "latest") } answers {
                val content = if (++historyCalls == 1) "refresh superseded" else "undo authoritative"
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = historyCalls,
                                    role = "assistant",
                                    content = content,
                                ),
                            ),
                    ),
                )
            }

            viewModel.sendMessage("/undo")
            runCurrent()
            viewModel.refreshCurrentSession()
            runCurrent()

            assertEquals(1, historyCalls)
            undoResult.complete(mapOf("type" to "prefill", "message" to "restored", "notice" to "Undone"))
            advanceUntilIdle()

            assertEquals(2, historyCalls)
            assertEquals(
                listOf("undo authoritative", "Undone"),
                viewModel.uiState.value.messages.map { it.content },
            )
            assertEquals(
                setOf("undo authoritative", "Undone"),
                fakeRepo.loadMessages(sessionId).map { it.content }.toSet(),
            )
            assertEquals("restored", viewModel.uiState.value.pendingPrefillText)
        }

    @Test
    fun testUndoCommand_lateResultAfterSessionSwitchIsInert() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult

            viewModel.sendMessage("/undo")
            runCurrent()
            viewModel.switchSession("other-session")
            undoResult.complete(mapOf("type" to "prefill", "message" to "stale", "notice" to "stale"))
            advanceUntilIdle()

            assertEquals("other-session", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.pendingPrefillText)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale" })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "/undo" })
        }

    @Test
    fun testUndoCommand_lateResultAfterProfileSwitchIsInert() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult

            viewModel.sendMessage("/undo")
            runCurrent()
            every { AuthManager.getSelectedProfileId() } returns "profile-b"
            undoResult.complete(mapOf("type" to "prefill", "message" to "stale", "notice" to "stale"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.pendingPrefillText)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale" })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "/undo" })
        }

    @Test
    fun testUndoCommand_socketGenerationChangeBeforeResultIsInertWithoutStatusUpdate() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult
            var messagePageCalls = 0
            coEvery { mockApi.getSessionMessages(any(), any(), any(), any(), any()) } answers {
                messagePageCalls++
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )
            }

            viewModel.sendMessage("/undo")
            runCurrent()
            every { HermesWsClient.isConnectionBindingCurrent(any()) } returns false
            undoResult.complete(mapOf("type" to "prefill", "message" to "stale", "notice" to "stale"))
            advanceUntilIdle()

            assertEquals(ConnectionStatus.CONNECTED, mockConnectionStatus.value)
            assertNull(viewModel.uiState.value.pendingPrefillText)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale" })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "/undo" })
            assertEquals(0, messagePageCalls)
        }

    @Test
    fun testUndoCommand_socketGenerationChangeDuringPaginationStopsBeforeReplacement() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult
            var messagePageCalls = 0
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), true, "latest") } answers {
                messagePageCalls++
                every { HermesWsClient.isConnectionBindingCurrent(any()) } returns false
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 20,
                                    role = "assistant",
                                    content = "stale page",
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                                total = 300,
                            ),
                    ),
                )
            }

            viewModel.sendMessage("/undo")
            runCurrent()
            undoResult.complete(mapOf("type" to "prefill", "message" to "stale", "notice" to "stale"))
            advanceUntilIdle()

            assertEquals(ConnectionStatus.CONNECTED, mockConnectionStatus.value)
            assertEquals(1, messagePageCalls)
            assertNull(viewModel.uiState.value.pendingPrefillText)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale page" || it.content == "stale" })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "/undo" })
        }

    @Test
    fun undoFencesSuspendedRefreshAndOlderPageButAllowsPostUndoRefresh() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val staleRefresh = CompletableDeferred<Unit>()
            val staleOlder = CompletableDeferred<Unit>()
            var offsetZeroCalls = 0

            fun page(
                content: String,
                offset: Int,
                returned: Int = 1,
                total: Int = 1,
            ) = com.m57.hermescontrol.data.model.SessionMessagesResponse(
                messages =
                    listOf(
                        com.m57.hermescontrol.data.model.SessionMessage(
                            id = content.hashCode(),
                            role = "assistant",
                            content = content,
                        ),
                    ),
                pagination =
                    com.m57.hermescontrol.data.model.SessionMessagePagination(
                        limit = 150,
                        offset = offset,
                        order = "latest",
                        returned = returned,
                        total = total,
                    ),
            )

            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), true, "latest") } coAnswers {
                val offset = arg<Int>(2)
                val response =
                    if (offset == 150) {
                        withContext(NonCancellable) { staleOlder.await() }
                        page("stale older", offset)
                    } else {
                        offsetZeroCalls++
                        when (offsetZeroCalls) {
                            1 -> page("initial", 0, returned = 150, total = 300)
                            2 -> {
                                withContext(NonCancellable) { staleRefresh.await() }
                                page("stale refresh", 0)
                            }
                            3 -> page("authoritative", 0)
                            else -> page("post undo", 0)
                        }
                    }
                retrofit2.Response.success(response)
            }
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult

            viewModel.refreshCurrentSession()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.hasOlderMessages)

            viewModel.refreshCurrentSession()
            runCurrent()
            viewModel.loadOlderMessages()
            runCurrent()
            viewModel.sendMessage("/undo")
            runCurrent()
            undoResult.complete(mapOf("type" to "prefill", "message" to "rewound", "notice" to "undone"))
            runCurrent()

            assertEquals(listOf("authoritative", "undone"), viewModel.uiState.value.messages.map { it.content })
            assertEquals(setOf("authoritative", "undone"), fakeRepo.loadMessages(sessionId).map { it.content }.toSet())

            staleRefresh.complete(Unit)
            staleOlder.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("authoritative", "undone"), viewModel.uiState.value.messages.map { it.content })
            assertEquals(setOf("authoritative", "undone"), fakeRepo.loadMessages(sessionId).map { it.content }.toSet())

            viewModel.refreshCurrentSession()
            advanceUntilIdle()
            assertEquals(listOf("post undo"), viewModel.uiState.value.messages.map { it.content })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "post undo" })
        }

    @Test
    fun testSlashCommand_help_addsHelpMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            every {
                HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any())
            } returns
                CompletableDeferred(
                    mapOf("type" to "exec", "output" to "**Available Commands:**\n\u2022 `/status`\n\u2022 `/new`"),
                )

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("Available Commands") },
            )
            assertTrue(
                viewModel.uiState.value.messages
                    .any { it.content.contains("/status") },
            )
        }

    @Test
    fun testSlashCommand_new_createsNewSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/new")
            advanceUntilIdle()

            verify(atLeast = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testSlashCommand_fork_sendsSessionBranch() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            // /fork with an optional branch title.
            viewModel.sendMessage("/fork my-fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertEquals("my-fork", branchSent.second["name"])
        }

    @Test
    fun testSlashCommand_fork_withoutName_omitsNameParam() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            val captured = mutableListOf<Pair<String, Map<String, Any>>>()
            every { HermesWsClient.send(any(), any(), any()) } answers {
                val id = "req-${captured.size + 1}"
                captured.add(arg<String>(0) to (arg<Map<String, Any>>(1)))
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.sendMessage("/fork")
            advanceUntilIdle()

            val branchSent = captured.firstOrNull { it.first == WsMethods.SESSION_BRANCH }
            assertNotNull("session.branch should be dispatched for /fork", branchSent)
            assertEquals(sessionId, branchSent!!.second["session_id"])
            assertFalse("name param should be omitted when no title given", branchSent.second.containsKey("name"))
        }

    @Test
    fun testSlashCommand_stop_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stop")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun testSlashCommand_interrupt_sendsInterrupt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/interrupt")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    @Test
    fun slashUsage_countsOnlySuccessfulDispatchForSelectedProfile() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any()) } returns
                CompletableDeferred<Any?>(mapOf("type" to "exec", "output" to "ok"))

            viewModel.sendMessage("/help")
            advanceUntilIdle()

            assertEquals(1, fakeSlashUsageStore.countsNow("profile-a")["/help"])
            assertTrue(fakeSlashUsageStore.countsNow("profile-b").isEmpty())
        }

    @Test
    fun slashUsage_followsProfileSwitchWhileViewModelLives() =
        runTest {
            val selectedProfile = MutableStateFlow("profile-a")
            every { AuthManager.getSelectedProfileId() } answers { selectedProfile.value }
            val viewModel =
                ChatViewModel(
                    app,
                    false,
                    fakeRepo,
                    fakeSlashUsageStore,
                    testDispatcher,
                    selectedProfileId = { selectedProfile.value },
                    selectedProfileIds = selectedProfile,
                )
            fakeSlashUsageStore.recordUse("profile-a", "/help")
            fakeSlashUsageStore.recordUse("profile-b", "/resume")
            advanceUntilIdle()
            assertEquals(mapOf("/help" to 1), viewModel.uiState.value.slashUsageCounts)

            selectedProfile.value = "profile-b"
            advanceUntilIdle()
            assertEquals(mapOf("/resume" to 1), viewModel.uiState.value.slashUsageCounts)

            val recordAcceptedSlash =
                ChatViewModel::class.java.getDeclaredMethod("recordAcceptedSlash", String::class.java).apply {
                    isAccessible = true
                }
            recordAcceptedSlash.invoke(viewModel, "/help")
            advanceUntilIdle()
            assertEquals(1, fakeSlashUsageStore.countsNow("profile-b")["/help"])
            assertEquals(1, fakeSlashUsageStore.countsNow("profile-b")["/resume"])
            assertEquals(1, fakeSlashUsageStore.countsNow("profile-a")["/help"])
        }

    @Test
    fun slashUsage_doesNotCountRejectedOrBlockedCommands() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any()) } returns
                CompletableDeferred<Any?>().also {
                    it.completeExceptionally(HermesWsClient.HermesRpcException("invalid"))
                }

            viewModel.sendMessage("/invalid")
            viewModel.sendMessage("/clear")
            advanceUntilIdle()

            assertTrue(fakeSlashUsageStore.countsNow("profile-a").isEmpty())
        }

    @Test
    fun resumeAndHistoryRequestPickerWithoutBackendDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            viewModel.sendMessage("/resume")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.openHistoryRequested)
            assertEquals(1, fakeSlashUsageStore.countsNow("profile-a")["/resume"])
            verify(exactly = 0) { HermesWsClient.request(WsMethods.COMMAND_DISPATCH, any(), any()) }
            verify(exactly = 0) { HermesWsClient.request(WsMethods.SLASH_EXEC, any(), any()) }

            viewModel.consumeOpenHistoryRequest()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.openHistoryRequested)
        }

    @Test
    fun testSlashCommand_unknown_showsErrorMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            var dispatchReqId = "dispatch-unk"

            every { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(dispatchReqId)
                dispatchReqId
            }

            viewModel.sendMessage("/nonexistent")
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    dispatchReqId,
                    JsonRpcError(code = -32601, message = "Unknown command: nonexistent"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage
                    ?.contains("Unknown command") == true,
            )
        }

    @Test
    fun testSlashCommandStatusRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/status")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandSessionsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/sessions")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testSlashCommandStatsRoutesToSlash() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/stats")
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
        }

    @Test
    fun testBareModelCommand_opensPickerInsteadOfDispatch() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // A bare "/model" must NOT dispatch a slash command; it opens the picker.
            viewModel.sendMessage("/model")
            advanceUntilIdle()

            assertTrue(
                "picker should be shown when bare /model is typed",
                viewModel.uiState.value.showModelPicker,
            )
            assertTrue(
                "picker should have preloaded providers (cached at GatewayReady)",
                viewModel.uiState.value.modelPickerProviders
                    .isNotEmpty(),
            )
            verify(exactly = 0) { HermesWsClient.send(WsMethods.COMMAND_DISPATCH, any(), any()) }
            assertTrue(fakeSlashUsageStore.countsNow("profile-a").isEmpty())
        }

    @Test
    fun testModelPickerSelection_hotSwapsCurrentSessionViaConfigSet() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("/model")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.showModelPicker)

            // Selecting a model sends the bare spec through the awaited
            // config.set RPC. It never reaches prompt.submit.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                CompletableDeferred<Any?>(mapOf("ok" to true))
            }

            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()

            assertFalse("picker closes after selection", viewModel.uiState.value.showModelPicker)
            assertEquals(
                "openai/gpt-4o",
                viewModel.uiState.value.currentSessionModel,
            )
            verify { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) }
            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("selection must route through config.set key=model", call)
            assertEquals("gpt-4o --provider openai --session", call!!.second)
            assertEquals(sessionId, call.third)
            assertTrue(fakeSlashUsageStore.countsNow("profile-a").isEmpty())
        }

    @Test
    fun testSessionInfo_tracksModelForCurrentSessionOnly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "provider" to "openai-codex",
                            "model" to "gpt-5.6-sol",
                            "terminal_backend" to "ssh",
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "openai-codex/gpt-5.6-sol",
                viewModel.uiState.value.currentSessionModel,
            )
            assertEquals("ssh", viewModel.uiState.value.terminalBackend)

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data = mapOf("provider" to "fireworks", "model" to "glm-5p2"),
                    sessionId = "another-session",
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "openai-codex/gpt-5.6-sol",
                viewModel.uiState.value.currentSessionModel,
            )
        }

    @Test
    fun testModelContextLength_tracksQualifiedResponseModel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            assertEquals(272_000L, viewModel.uiState.value.modelContextLength)
            assertEquals(
                "openai-codex/gpt-5.6-sol",
                viewModel.uiState.value.modelContextLengthModel,
            )
        }

    @Test
    fun testCreateNewSession_clearsPreviousSessionModelImmediately() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data = mapOf("provider" to "fireworks", "model" to "glm-5p2"),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "todo",
                    data =
                        mapOf(
                            "todos" to
                                listOf(
                                    mapOf(
                                        "id" to "1",
                                        "content" to "Old task",
                                        "status" to "in_progress",
                                    ),
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            mockEventsFlow.emit(
                WsEvent.SubagentEvent(
                    type = "subagent.start",
                    payload = mapOf("subagent_id" to "old-agent"),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertEquals("fireworks/glm-5p2", viewModel.uiState.value.currentSessionModel)
            assertEquals(1, viewModel.uiState.value.todos.size)
            assertEquals(1, viewModel.uiState.value.subagentIndicators.size)

            viewModel.createNewSession()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.currentSessionModel)
            assertNull(ActiveSessionHolder.activeSessionId.value)
            assertTrue(viewModel.uiState.value.todos.isEmpty())
            assertTrue(viewModel.uiState.value.subagentIndicators.isEmpty())

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "provider" to "fireworks",
                            "model" to "stale-model",
                            "usage" to
                                mapOf(
                                    "context_used" to 99_000,
                                    "context_max" to 272_000,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionModel)
            assertNull(viewModel.uiState.value.contextUsage)
        }

    @Test
    fun testSendMessage_rejectsPromptUntilNewSessionIsReady() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            viewModel.createNewSession()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSessionReady)
            assertFalse(viewModel.sendMessage("first prompt"))
            verify(exactly = 0) { HermesWsClient.sendMessage(any(), any(), any(), any()) }
        }

    @Test
    fun testContextUsage_tracksLiveWindowForCurrentSessionOnly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 54_321,
                                    "context_max" to 272_000,
                                    "context_percent" to 19.97,
                                    "input" to 1_900_000,
                                    "output" to 88_000,
                                    "total" to 1_988_000,
                                    "calls" to 42,
                                    "compressions" to 3,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            val initial = viewModel.uiState.value.contextUsage
            assertNotNull(initial)
            assertEquals(54_321L, initial?.usedTokens)
            assertEquals(272_000L, initial?.maxTokens)
            assertEquals(1_900_000L, initial?.inputTokens)
            assertEquals(3L, initial?.compressions)

            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "done",
                    sessionId = sessionId,
                    usage =
                        mapOf(
                            "context_used" to 61_000,
                            "context_max" to 272_000,
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(61_000L, viewModel.uiState.value.contextUsage?.usedTokens)
            assertEquals(
                "missing cumulative fields retain the previous totals",
                1_900_000L,
                viewModel.uiState.value.contextUsage?.inputTokens,
            )

            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "other",
                    sessionId = "another-session",
                    usage =
                        mapOf(
                            "context_used" to 99_000,
                            "context_max" to 272_000,
                        ),
                ),
            )
            advanceUntilIdle()
            assertEquals(61_000L, viewModel.uiState.value.contextUsage?.usedTokens)
        }

    @Test
    fun testSessionUsagePush_updatesCurrentWindowAndPreservesPartialTotals() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 54_321,
                                    "context_max" to 272_000,
                                    "input" to 1_900_000,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 0,
                                    "compressions" to 4,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            val usage = viewModel.uiState.value.contextUsage
            assertEquals(0L, usage?.usedTokens)
            assertEquals(272_000L, usage?.maxTokens)
            assertEquals(1_900_000L, usage?.inputTokens)
            assertEquals(4L, usage?.compressions)
        }

    @Test
    fun testSessionUsagePush_rejectsStaleSessionAndFractionalOccupancy() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 10_000,
                                    "context_max" to 272_000,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 99_000,
                                ),
                        ),
                    sessionId = "another-session",
                ),
            )
            advanceUntilIdle()
            assertEquals(10_000L, viewModel.uiState.value.contextUsage?.usedTokens)

            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 1.5,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.contextUsage?.usedTokens)
        }

    @Test
    fun testBranchResult_replacesParentUsageWithChildSnapshot() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionInfo(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 80_000,
                                    "context_max" to 272_000,
                                    "input" to 900_000,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()

            every {
                HermesWsClient.send(WsMethods.SESSION_BRANCH, any(), any())
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke("branch-request")
                "branch-request"
            }
            viewModel.sendMessage("/fork child")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "branch-request",
                    result =
                        mapOf(
                            "session_id" to "child-runtime",
                            "title" to "child",
                            "info" to
                                mapOf(
                                    "model" to "gpt-5.6-sol",
                                    "provider" to "openai-codex",
                                    "reasoning_effort" to "high",
                                    "usage" to
                                        mapOf(
                                            "context_used" to 31_000,
                                            "context_max" to 272_000,
                                            "input" to 120_000,
                                        ),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("child-runtime", state.currentSessionId)
            assertEquals("openai-codex/gpt-5.6-sol", state.currentSessionModel)
            assertEquals("high", state.reasoningLevel)
            assertEquals(31_000L, state.contextUsage?.usedTokens)
            assertEquals(120_000L, state.contextUsage?.inputTokens)
        }

    @Test
    fun branchResult_clearsParentSudoAndSecretPromptsWhenChildBecomesActive() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockEventsFlow.emit(sudoRequest())
            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)
            assertNotNull(viewModel.uiState.value.secretPrompt)

            every {
                HermesWsClient.send(WsMethods.SESSION_BRANCH, any(), any())
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke("branch-prompts")
                "branch-prompts"
            }
            viewModel.sendMessage("/fork child")
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "branch-prompts",
                    result = mapOf("session_id" to "child-runtime"),
                ),
            )
            advanceUntilIdle()

            assertEquals("child-runtime", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.sudoPrompt)
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun failedBranch_preservesExactParentSudoAndSecretPrompts() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockEventsFlow.emit(sudoRequest())
            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            val sudoPrompt = viewModel.uiState.value.sudoPrompt
            val secretPrompt = viewModel.uiState.value.secretPrompt

            every {
                HermesWsClient.send(WsMethods.SESSION_BRANCH, any(), any())
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke("failed-branch")
                "failed-branch"
            }
            viewModel.sendMessage("/fork child")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "failed-branch",
                    JsonRpcError(code = -32603, message = "branch failed"),
                ),
            )
            advanceUntilIdle()

            assertEquals(sudoPrompt, viewModel.uiState.value.sudoPrompt)
            assertEquals(secretPrompt, viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun staleBranchResult_doesNotClearNewerSessionsSudoAndSecretPrompts() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every {
                HermesWsClient.send(WsMethods.SESSION_BRANCH, any(), any())
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke("stale-branch-prompts")
                "stale-branch-prompts"
            }
            viewModel.sendMessage("/fork child")
            advanceUntilIdle()

            viewModel.switchSession("other-session")
            advanceUntilIdle()
            mockEventsFlow.emit(sudoRequest(sessionId = "other-session"))
            mockEventsFlow.emit(secretRequest(sessionId = "other-session"))
            advanceUntilIdle()
            val sudoPrompt = viewModel.uiState.value.sudoPrompt
            val secretPrompt = viewModel.uiState.value.secretPrompt

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "stale-branch-prompts",
                    result = mapOf("session_id" to "stale-child"),
                ),
            )
            advanceUntilIdle()

            assertEquals("other-session", viewModel.uiState.value.currentSessionId)
            assertEquals(sudoPrompt, viewModel.uiState.value.sudoPrompt)
            assertEquals(secretPrompt, viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testStaleBranchResult_doesNotReplaceSelectedSession() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every {
                HermesWsClient.send(WsMethods.SESSION_BRANCH, any(), any())
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke("stale-branch")
                "stale-branch"
            }

            viewModel.sendMessage("/fork child")
            advanceUntilIdle()
            viewModel.switchSession("other-session")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    id = "stale-branch",
                    result =
                        mapOf(
                            "session_id" to "stale-child",
                            "title" to "stale child",
                            "info" to
                                mapOf(
                                    "usage" to
                                        mapOf(
                                            "context_used" to 99_000,
                                            "context_max" to 272_000,
                                        ),
                                ),
                        ),
                ),
            )
            advanceUntilIdle()

            assertEquals("other-session", viewModel.uiState.value.currentSessionId)
            assertNull(viewModel.uiState.value.contextUsage)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "Session branched" })
        }

    @Test
    fun testTypedModelCommandWithArg_dispatchesDirectly() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed "/model <model> --provider <slug> --session" bypasses the
            // picker and dispatches straight to the backend as a normal prompt.
            viewModel.sendMessage("/model gpt-4o --provider openai --session")
            advanceUntilIdle()

            assertFalse(
                "typed /model with arg should not open the picker",
                viewModel.uiState.value.showModelPicker,
            )
            // A fully-typed /model goes to the backend via the `config.set` RPC
            // (key="model"), which the gateway routes to _apply_model_switch. NOT
            // command.dispatch (4018s on /model) and NOT prompt.submit (LLM would
            // treat it as text).
            verify { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) }
        }

    @Test
    fun testTypedModelCommand_caseInsensitive_doesNotForwardSlashPrefix() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // A fully-typed /MODEL (uppercase) must still route through
            // config.set key=model with the BARE spec — the leading "/MODEL"
            // slash prefix must be stripped, or parse_model_flags on the
            // backend won't recognize it and the hot-swap silently fails.
            val modelCalls = mutableListOf<Triple<String, String, String>>()
            every { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                modelCalls.add(
                    Triple(
                        params["key"] as String,
                        params["value"] as String,
                        params["session_id"] as String,
                    ),
                )
                CompletableDeferred<Any?>(mapOf("ok" to true))
            }

            viewModel.sendMessage("/MODEL gpt-4o --provider openai --session")
            advanceUntilIdle()

            val call = modelCalls.firstOrNull { it.first == "model" }
            assertNotNull("uppercase /MODEL must route through config.set key=model", call)
            assertEquals(
                "slash prefix must be stripped before send",
                "gpt-4o --provider openai --session",
                call!!.second,
            )
            assertFalse(
                "value must not carry the literal /MODEL prefix",
                call.second.startsWith("/"),
            )
            assertEquals(sessionId, call.third)
        }

    @Test
    fun testModelPickerSelection_confirmsExpensiveModelBeforeUpdatingLabel() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.SessionUsage(
                    data =
                        mapOf(
                            "usage" to
                                mapOf(
                                    "context_used" to 12_000L,
                                    "context_max" to 272_000L,
                                ),
                        ),
                    sessionId = sessionId,
                ),
            )
            advanceUntilIdle()
            val calls = mutableListOf<Map<String, Any>>()
            every { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) } answers {
                val params = arg<Map<String, Any>>(1)
                calls.add(params)
                if (params["confirm_expensive_model"] == true) {
                    CompletableDeferred<Any?>(
                        mapOf(
                            "ok" to true,
                            "provider" to "openai",
                            "model" to "gpt-expensive",
                        ),
                    )
                } else {
                    CompletableDeferred<Any?>(
                        mapOf(
                            "confirm_required" to true,
                            "confirm_message" to "This model costs more.",
                        ),
                    )
                }
            }

            viewModel.sendSlashModel("openai", "gpt-expensive")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionModel)
            assertEquals(
                "This model costs more.",
                viewModel.uiState.value.modelSwitchConfirmation?.message,
            )

            viewModel.confirmModelSwitch()
            advanceUntilIdle()

            assertEquals("openai/gpt-expensive", viewModel.uiState.value.currentSessionModel)
            assertEquals(12_000L, viewModel.uiState.value.contextUsage?.usedTokens)
            assertNull(viewModel.uiState.value.contextUsage?.maxTokens)
            assertNull(viewModel.uiState.value.modelSwitchConfirmation)
            assertEquals(true, calls.last()["confirm_expensive_model"])
        }

    @Test
    fun testModelPickerSelection_rpcFailureDoesNotUpdateLabel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.request(WsMethods.CONFIG_SET, any(), any()) } returns
                CompletableDeferred<Any?>().also {
                    it.completeExceptionally(
                        HermesWsClient.HermesRpcException("Session is busy"),
                    )
                }

            viewModel.sendSlashModel("openai", "gpt-4o")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.currentSessionModel)
            assertEquals(
                "Failed to switch model: Session is busy",
                viewModel.uiState.value.errorMessage,
            )
        }

    // ── Connection / init tests ──────────────────────────────────────────────

    @Test
    fun testInitialStateAndConnection() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.DISCONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.connect() }
        }

    @Test
    fun testAlreadyConnectedOnLaunch_createsSession() =
        runTest {
            mockConnectionStatus.value = ConnectionStatus.CONNECTED

            createViewModel()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun testGatewayReady_createsSessionIfNoneExists() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_LIST, any(), any()) }
            verify { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertTrue(viewModel.uiState.value.isConnected)
        }

    @Test
    fun testGatewayReady_withInitialSessionId_switchesToIt() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.initialSessionId = "session-from-notification"

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            assertEquals("session-from-notification", viewModel.uiState.value.currentSessionId)
            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf(
                        "session_id" to "session-from-notification",
                        "omit_messages" to true,
                    ),
                    any(),
                )
            }
            // Should NOT create a new session
            verify(inverse = true) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    // ── RPC result tests ─────────────────────────────────────────────────────

    @Test
    fun testSessionCreateRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3)
            mockEventsFlow.emit(WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")))
            advanceUntilIdle()

            assertEquals("session-123", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
        }

    @Test
    fun testSessionListRpcResult() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            // GatewayReady sends SESSION_LIST (req-id-1), COMMANDS_CATALOG (req-id-2),
            // then SESSION_CREATE (req-id-3). Emit the SESSION_LIST result.
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "req-id-1",
                    mapOf(
                        "sessions" to
                            listOf(
                                mapOf(
                                    "id" to "session-123",
                                    "title" to "My Session Title",
                                    "message_count" to 12.0,
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.sessions.size)
            assertEquals(
                "session-123",
                viewModel.uiState.value.sessions[0]
                    .id,
            )
            assertEquals(
                "My Session Title",
                viewModel.uiState.value.sessions[0]
                    .title,
            )
            assertEquals(
                12,
                viewModel.uiState.value.sessions[0]
                    .messageCount,
            )
        }

    // ── Streaming tests ──────────────────────────────────────────────────────

    @Test
    fun testMessageStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Start: reducer creates streamingMessage and sets isAgentTyping on uiState
            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isAgentTyping)
            assertNotNull(viewModel.streamingState.value.streamingMessage)

            // 2 — Thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta("Thinking...", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking...", viewModel.streamingState.value.thinkingText)

            // 3 — Deeper thinking
            mockEventsFlow.emit(WsEvent.ThinkingDelta(" deeper", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("Thinking... deeper", viewModel.streamingState.value.thinkingText)

            // 4 — First token (flushed by isTestEnvironment)
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()
            assertFalse(viewModel.streamingState.value.isThinking)
            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 5 — Second token
            mockEventsFlow.emit(WsEvent.MessageToken(" world", sessionId))
            advanceUntilIdle()
            assertEquals(
                "Hello world",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )

            // 6 — Complete: reducer finalizes message + resets streamingState
            mockEventsFlow.emit(WsEvent.MessageComplete("Hello world!", sessionId))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAgentTyping)
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello world!",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
        }

    @Test
    fun testStaleMessageComplete_doesNotResetSelectedSessionStream() =
        runTest {
            val (viewModel, previousSessionId) = createViewModelWithSession()
            val selectedSessionId = "selected-session"
            viewModel.switchSession(selectedSessionId)
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(selectedSessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", selectedSessionId))
            advanceUntilIdle()
            assertEquals("Hello", viewModel.streamingState.value.streamingMessage?.content)

            mockEventsFlow.emit(
                WsEvent.MessageComplete(
                    text = "stale",
                    sessionId = previousSessionId,
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageToken(" world", selectedSessionId))
            advanceUntilIdle()

            assertEquals(
                "Hello world",
                viewModel.streamingState.value.streamingMessage?.content,
            )
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale" })
        }

    @Test
    fun testReasoningStreamingFlow() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // 1 — Reasoning available: block becomes visible (no token yet)
            mockEventsFlow.emit(WsEvent.ReasoningAvailable(sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isReasoning)

            // 2 — Reasoning delta
            mockEventsFlow.emit(WsEvent.ReasoningDelta("Let me think", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think", viewModel.streamingState.value.reasoningText)

            // 3 — Deeper reasoning
            mockEventsFlow.emit(WsEvent.ReasoningDelta(" step by step", sessionId))
            advanceUntilIdle()
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 4 — Thinking still independent
            mockEventsFlow.emit(WsEvent.ThinkingDelta("thinking", sessionId))
            advanceUntilIdle()
            assertTrue(viewModel.streamingState.value.isThinking)
            assertEquals("thinking", viewModel.streamingState.value.thinkingText)
            // reasoning untouched
            assertEquals("Let me think step by step", viewModel.streamingState.value.reasoningText)

            // 5 — Complete: reducer finalizes message, attaching reasoning
            mockEventsFlow.emit(WsEvent.MessageComplete("The answer is 42", sessionId))
            advanceUntilIdle()

            assertNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "The answer is 42",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            // reasoning carried onto the finalized UI message
            assertEquals(
                "Let me think step by step",
                viewModel.uiState.value.messages[1]
                    .reasoningText,
            )
            // reasoning persisted to the entity (survives reload)
            val persisted =
                fakeRepo.dao.getMessagesForSession(sessionId).first { it.role == "ASSISTANT" }
            assertEquals("Let me think step by step", persisted.reasoningText)
        }

    @Test
    fun testToolExecution_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Calculating sum", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.ToolStart("calculator", mapOf("input" to "2+2")))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "Calculating sum",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[2]
                    .role,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
        }

    @Test
    fun testMessageStart_finalizesPreviousStreamingMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("First part", sessionId))
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Second part", sessionId))
            advanceUntilIdle()

            // messages[0] = "Session created" system message
            assertEquals(
                "First part",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertFalse(
                viewModel.uiState.value.messages[1]
                    .isStreaming,
            )
            assertNotNull(viewModel.streamingState.value.streamingMessage)
            assertEquals(
                "Second part",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    @Test
    fun testToolExecution_serializesDataAsJson() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(
                    name = "calculator",
                    data = mapOf("input" to "2+2", "nested" to mapOf("key" to "value")),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                MessageRole.TOOL,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertEquals(
                ToolStatus.RUNNING,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )

            mockEventsFlow.emit(
                WsEvent.ToolComplete("calculator", mapOf("result" to "4", "exit_code" to 0)),
            )
            advanceUntilIdle()

            assertEquals(
                ToolStatus.COMPLETED,
                viewModel.uiState.value.messages[1]
                    .toolStatus,
            )
        }

    // ── Clarify tests ────────────────────────────────────────────────────────

    @Test
    fun testClarifyRequestAndRespond() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Please choose:",
                    options = listOf("Yes", "No"),
                    clarifyId = "clarify-123",
                    sessionId = sessionId,
                    sourceProfileId = "profile-a",
                    connectionGeneration = 7,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "Please choose:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertEquals(
                listOf("Yes", "No"),
                viewModel.uiState.value.clarifyRequest
                    ?.options,
            )
            assertEquals(
                "clarify-123",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.respondToClarify("Yes")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)

            verify {
                HermesWsClient.respondToClarify(
                    sessionId = sessionId,
                    clarifyRequestId = "clarify-123",
                    questionId = null,
                    answer = "Yes",
                    sourceProfileId = "profile-a",
                    sourceConnectionGeneration = 7,
                )
            }
        }

    @Test
    fun testClarifyRequestCustomResponse() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Please explain:",
                    options = emptyList(),
                    clarifyId = "clarify-456",
                    sessionId = sessionId,
                    sourceProfileId = "profile-a",
                    connectionGeneration = 8,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "Please explain:",
                viewModel.uiState.value.clarifyRequest
                    ?.text,
            )
            assertTrue(
                viewModel.uiState.value.clarifyRequest
                    ?.options
                    ?.isEmpty() == true,
            )

            viewModel.respondToClarify("This is my custom response text")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "This is my custom response text",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )

            verify {
                HermesWsClient.respondToClarify(
                    sessionId = sessionId,
                    clarifyRequestId = "clarify-456",
                    questionId = null,
                    answer = "This is my custom response text",
                    sourceProfileId = "profile-a",
                    sourceConnectionGeneration = 8,
                )
            }
        }

    @Test
    fun testClarifyBatch_midBatchFailureRetainsFailedAndUnsentQuestions() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = null,
                    options = null,
                    clarifyId = "clarify-batch",
                    sessionId = sessionId,
                    questions =
                        listOf(
                            WsEvent.ClarifyQuestion("q0", "First?"),
                            WsEvent.ClarifyQuestion("q1", "Second?"),
                            WsEvent.ClarifyQuestion("q2", "Third?"),
                        ),
                    sourceProfileId = "profile-a",
                    connectionGeneration = 9,
                ),
            )
            advanceUntilIdle()
            val expected = viewModel.uiState.value.clarifyRequest!!
            var sendCount = 0
            every { HermesWsClient.respondToClarify(any(), any(), any(), any(), any(), any()) } answers {
                sendCount++
                sendCount != 2
            }

            viewModel.respondToClarifyBatch(
                expected,
                mapOf("q0" to "first answer", "q1" to "second answer", "q2" to "third answer"),
            )
            advanceUntilIdle()

            assertEquals(listOf("q1", "q2"), viewModel.uiState.value.clarifyRequest?.questions?.map { it.qid })
            assertEquals(2, sendCount)
            verify(exactly = 1) {
                HermesWsClient.respondToClarify(
                    sessionId,
                    "clarify-batch",
                    "q0",
                    "first answer",
                    "profile-a",
                    9,
                )
            }
        }

    @Test
    fun testClarifyBatch_allSuccessfulSendsCompletePrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = null,
                    options = null,
                    clarifyId = "clarify-success",
                    sessionId = sessionId,
                    questions =
                        listOf(
                            WsEvent.ClarifyQuestion("q0", "First?"),
                            WsEvent.ClarifyQuestion("q1", "Second?"),
                        ),
                    sourceProfileId = "profile-a",
                    connectionGeneration = 9,
                ),
            )
            advanceUntilIdle()
            val expected = viewModel.uiState.value.clarifyRequest!!

            viewModel.respondToClarifyBatch(expected, mapOf("q0" to "one", "q1" to "two"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.clarifyRequest)
            assertEquals("one\ntwo", viewModel.uiState.value.messages.last().content)
            verify(exactly = 2) {
                HermesWsClient.respondToClarify(sessionId, "clarify-success", any(), any(), "profile-a", 9)
            }
        }

    @Test
    fun testExpiredClarify_cannotRespondFromCapturedPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Question?",
                    options = emptyList(),
                    clarifyId = "clarify-expired",
                    sessionId = sessionId,
                    sourceProfileId = "profile-a",
                    connectionGeneration = 10,
                ),
            )
            advanceUntilIdle()
            val expired = viewModel.uiState.value.clarifyRequest!!
            mockEventsFlow.emit(
                WsEvent.ClarifyExpire("clarify-expired", sessionId, "profile-a", 10),
            )
            advanceUntilIdle()

            viewModel.respondToClarifyBatch(expired, mapOf("q0" to "too late"))
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.respondToClarify(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun testClarifyDismissInformsAgent() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Please choose:",
                    options = listOf("Yes", "No"),
                    clarifyId = "clarify-789",
                    sessionId = sessionId,
                    sourceProfileId = "profile-a",
                    connectionGeneration = 11,
                ),
            )
            advanceUntilIdle()
            assertEquals(
                "clarify-789",
                viewModel.uiState.value.clarifyRequest
                    ?.clarifyId,
            )

            viewModel.dismissClarify()
            advanceUntilIdle()

            // Dialog dismissed locally
            assertNull(viewModel.uiState.value.clarifyRequest)
            // Baseline has 1 "Connected" system message; dismiss adds exactly
            // ONE system note and must NOT fake a user bubble.
            val messages = viewModel.uiState.value.messages
            assertEquals(2, messages.size)
            assertEquals(MessageRole.SYSTEM, messages[0].role) // pre-existing "Session created"
            assertEquals(MessageRole.SYSTEM, messages[1].role) // dismiss trace
            assertTrue(messages[1].content.contains("dismissed", ignoreCase = true))

            verify {
                HermesWsClient.respondToClarify(
                    sessionId = sessionId,
                    clarifyRequestId = "clarify-789",
                    questionId = null,
                    answer = "The user cancelled — no answer provided.",
                    sourceProfileId = "profile-a",
                    sourceConnectionGeneration = 11,
                )
            }
        }

    @Test
    fun testClarifyDismiss_midBatchFailureRetainsUnresolvedWithoutCompletionNote() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = null,
                    options = null,
                    clarifyId = "clarify-dismiss-batch",
                    sessionId = sessionId,
                    questions =
                        listOf(
                            WsEvent.ClarifyQuestion("q0", "First?"),
                            WsEvent.ClarifyQuestion("q1", "Second?"),
                            WsEvent.ClarifyQuestion("q2", "Third?"),
                        ),
                    sourceProfileId = "profile-a",
                    connectionGeneration = 12,
                ),
            )
            advanceUntilIdle()
            val messageCount = viewModel.uiState.value.messages.size
            var sendCount = 0
            every { HermesWsClient.respondToClarify(any(), any(), any(), any(), any(), any()) } answers {
                sendCount++
                sendCount != 2
            }

            viewModel.dismissClarify()
            advanceUntilIdle()

            assertEquals(listOf("q1", "q2"), viewModel.uiState.value.clarifyRequest?.questions?.map { it.qid })
            assertEquals(2, sendCount)
            assertEquals(messageCount, viewModel.uiState.value.messages.size)
            assertTrue(
                viewModel.uiState.value.messages.none {
                    it.content.contains("dismissed", ignoreCase = true)
                },
            )
        }

    // ── Attachments ──────────────────────────────────────────────────────────

    /** Add [count] dummy attachments so a test starts with a populated list. */
    private fun TestScope.addDummyAttachments(
        viewModel: ChatViewModel,
        count: Int,
    ) {
        repeat(count) { i ->
            viewModel.addAttachment("uri$i", "file$i.txt", "text/plain", (i + 1) * 100L)
        }
        advanceUntilIdle()
    }

    @Test
    fun testAddAttachment() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment(
                uri = "content://dummy/1",
                name = "dummy.txt",
                mimeType = "text/plain",
                size = 1024L,
            )
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("content://dummy/1", pending[0].uri)
            assertEquals("dummy.txt", pending[0].name)
        }

    @Test
    fun testRemoveAttachment_validIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.removeAttachment(0)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri2", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_invalidIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)

            // Out of bounds index should not crash or change list
            viewModel.removeAttachment(5)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri1", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_mixedValidAndInvalidSequence() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            addDummyAttachments(viewModel, 3) // [uri0, uri1, uri2]

            // 1. Remove a valid index (the middle item) → list shrinks correctly.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertEquals(
                "uri0",
                viewModel.uiState.value.pendingAttachments[0]
                    .uri,
            )
            assertEquals(
                "uri2",
                viewModel.uiState.value.pendingAttachments[1]
                    .uri,
            )

            // 2. Fire invalid removals (out of bounds + negative) — must be no-ops.
            viewModel.removeAttachment(99)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            // 3. Another valid removal on the shifted list → still consistent.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri0", pending[0].uri)
        }

    @Test
    fun testClearAttachments() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.clearAttachments()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertTrue(pending.isEmpty())
        }

    // ── Send message ─────────────────────────────────────────────────────────

    @Test
    fun testSendMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Hello Hermes",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.USER,
                viewModel.uiState.value.messages[1]
                    .role,
            )
            assertTrue(viewModel.uiState.value.isAgentTyping)

            verify {
                HermesWsClient.sendMessageForConnection(
                    any(),
                    sessionId,
                    "Hello Hermes",
                    any(),
                )
            }
        }

    @Test
    fun testSendMessageRedirectsWhileStreaming() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // First send starts the turn — normal prompt.submit.
            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)
            verify {
                HermesWsClient.sendMessageForConnection(
                    any(),
                    sessionId,
                    "Hello Hermes",
                    any(),
                )
            }

            // Second send lands mid-turn — steer the live turn instead.
            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()

            verify { HermesWsClient.sendRedirectForConnection(any(), sessionId, "Wait, correction", any()) }
        }

    @Test
    fun testSendMessageWithAttachmentDoesNotRedirect() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isAgentTyping)

            // session.redirect is text-only, so an attachment send must stay on
            // prompt.submit even while a turn is streaming.
            viewModel.addAttachment(
                uri = "content://fake/1",
                name = "note.txt",
                mimeType = "text/plain",
                size = 12L,
            )
            advanceUntilIdle()

            viewModel.sendMessage("with a file")
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.sendRedirectForConnection(any(), any(), any(), any()) }
            verify {
                HermesWsClient.sendMessageForConnection(any(), sessionId, any(), any())
            }
        }

    @Test
    fun testRedirectRejectionResendsAsPrompt() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            every { HermesWsClient.sendRedirectForConnection(any(), any(), any(), any()) } answers {
                val id = "req-redirect-capture"
                arg<((String) -> Unit)?>(3)?.invoke(id)
                true
            }

            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()
            verify { HermesWsClient.sendRedirectForConnection(any(), sessionId, "Wait, correction", any()) }

            // A gateway whose agent cannot steer answers 4010. The text must be
            // resent as a normal prompt, not surfaced as an error.
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-redirect-capture",
                    JsonRpcError(4010, "agent does not support active-turn redirect"),
                ),
            )
            advanceUntilIdle()

            verify {
                HermesWsClient.sendMessageForConnection(
                    any(),
                    sessionId,
                    "Wait, correction",
                    any(),
                )
            }
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun testRedirectOtherErrorSurfacesToUser() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.sendMessage("Hello Hermes")
            advanceUntilIdle()

            every { HermesWsClient.sendRedirectForConnection(any(), any(), any(), any()) } answers {
                val id = "req-redirect-other"
                arg<((String) -> Unit)?>(3)?.invoke(id)
                true
            }

            viewModel.sendMessage("Wait, correction")
            advanceUntilIdle()

            // Any non-4010 rejection is a real error and must not silently
            // re-send the prompt.
            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-redirect-other",
                    JsonRpcError(5000, "redirect failed: boom"),
                ),
            )
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.sendMessageForConnection(
                    any(),
                    sessionId,
                    "Wait, correction",
                    any(),
                )
            }
            assertNotNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun testLoadOlderMessages_missingBindingNeverClaimsLoading() =
        runTest {
            val viewModel = createPaginatedViewModel()
            var olderCalls = 0
            coEvery { mockApi.getSessionMessages("paged", 150, 150, true, "latest") } answers {
                olderCalls++
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )
            }
            every { HermesWsClient.connectionBinding("profile-a") } returns null

            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(0, olderCalls)
        }

    @Test
    fun testLoadOlderMessages_persistenceRejectionReleasesLoadingOwner() =
        runTest {
            var rejectWrites = false
            lateinit var rejectingRepo: FakeChatPersistenceRepository
            rejectingRepo =
                FakeChatPersistenceRepository(
                    operationRegistrationHook = { sessionId ->
                        if (rejectWrites) rejectingRepo.invalidateReplacementWrites(sessionId)
                    },
                )
            fakeRepo = rejectingRepo
            val viewModel = createPaginatedViewModel()
            coEvery { mockApi.getSessionMessages("paged", 150, 150, true, "latest") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 50,
                                    role = "assistant",
                                    content = "rejected older",
                                ),
                            ),
                    ),
                )
            rejectWrites = true

            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "rejected older" })
            assertFalse(fakeRepo.loadMessages("paged").any { it.content == "rejected older" })
        }

    @Test
    fun testLoadOlderMessages_successAndFailureReleaseLoadingOwner() =
        runTest {
            val viewModel = createPaginatedViewModel()
            coEvery { mockApi.getSessionMessages("paged", 150, 150, true, "latest") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 50,
                                    role = "assistant",
                                    content = "older",
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                limit = 150,
                                offset = 150,
                                order = "latest",
                                returned = 150,
                                total = 300,
                            ),
                    ),
                )
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertTrue(viewModel.uiState.value.messages.any { it.content == "older" })

            coEvery { mockApi.getSessionMessages("paged", 150, 300, true, "latest") } throws
                java.io.IOException("network failed")
            viewModel.loadOlderMessages()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
        }

    @Test
    fun fullRefreshRetiresSuspendedOlderFetchWithoutRoomOrUiPrependAndAllowsNextPage() =
        runTest {
            val viewModel = createPaginatedViewModel()
            val olderFetchStarted = CompletableDeferred<Unit>()
            val releaseOlderFetch = CompletableDeferred<Unit>()
            val freshFetchStarted = CompletableDeferred<Unit>()
            val releaseFreshFetch = CompletableDeferred<Unit>()
            var olderCalls = 0
            coEvery { mockApi.getSessionMessages("paged", 150, any(), true, "latest") } coAnswers {
                val call = ++olderCalls
                if (call == 1) {
                    olderFetchStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOlderFetch.await() }
                } else {
                    freshFetchStarted.complete(Unit)
                    releaseFreshFetch.await()
                }
                val content = if (call == 1) "stale older" else "fresh older"
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = call,
                                    role = "assistant",
                                    content = content,
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                limit = 150,
                                offset = 150,
                                order = "latest",
                                returned = if (call == 1) 1 else 150,
                                total = 300,
                            ),
                    ),
                )
            }
            coEvery { mockApi.getSessionMessages("paged", 150, 0, true, "latest") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 200,
                                    role = "assistant",
                                    content = "refreshed recent",
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(150, 0, "latest", 150, 300),
                    ),
                )

            viewModel.loadOlderMessages()
            olderFetchStarted.await()
            viewModel.refreshCurrentSession()
            runCurrent()

            assertFalse(viewModel.uiState.value.isLoadingOlder)
            assertEquals(listOf("refreshed recent"), viewModel.uiState.value.messages.map { it.content })

            viewModel.loadOlderMessages()
            freshFetchStarted.await()
            assertTrue(viewModel.uiState.value.isLoadingOlder)
            releaseOlderFetch.complete(Unit)
            runCurrent()
            assertEquals(listOf("refreshed recent"), viewModel.uiState.value.messages.map { it.content })
            assertFalse(fakeRepo.loadMessages("paged").any { it.content == "stale older" })
            assertTrue(viewModel.uiState.value.isLoadingOlder)

            releaseFreshFetch.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.messages.any { it.content == "fresh older" })
            assertTrue(fakeRepo.loadMessages("paged").any { it.content == "fresh older" })
        }

    @Test
    fun fullRefreshAtomicallyRejectsOlderPageWaitingInPersistenceFifo() =
        runTest {
            val olderPersistenceRegistered = CompletableDeferred<Unit>()
            val releaseOlderPersistence = CompletableDeferred<Unit>()
            var suspendNextRegistration = false
            fakeRepo =
                FakeChatPersistenceRepository(
                    operationRegistrationHook = {
                        if (suspendNextRegistration) {
                            suspendNextRegistration = false
                            olderPersistenceRegistered.complete(Unit)
                            withContext(NonCancellable) { releaseOlderPersistence.await() }
                        }
                    },
                )
            val viewModel = createPaginatedViewModel()
            coEvery { mockApi.getSessionMessages("paged", 150, 150, true, "latest") } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 50,
                                    role = "assistant",
                                    content = "stale persisted older",
                                ),
                            ),
                    ),
                )
            suspendNextRegistration = true
            viewModel.loadOlderMessages()
            olderPersistenceRegistered.await()

            viewModel.refreshCurrentSession()
            runCurrent()
            assertFalse(viewModel.uiState.value.isLoadingOlder)
            releaseOlderPersistence.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale persisted older" })
            assertFalse(fakeRepo.loadMessages("paged").any { it.content == "stale persisted older" })
            assertEquals(listOf("recent"), viewModel.uiState.value.messages.map { it.content })
        }

    @Test
    fun fullRefreshWaitsForOlderDaoBoundaryBeforeCapturingRevision() =
        runTest {
            val olderDaoEntered = CountDownLatch(1)
            val releaseOlderDao = CountDownLatch(1)
            val refreshDaoPersisted = CountDownLatch(1)
            val dao =
                object : FakeChatMessageDao() {
                    override fun upsertAll(messageList: List<ChatMessageEntity>) {
                        if (messageList.any { it.content == "stale inside dao" }) {
                            olderDaoEntered.countDown()
                            check(releaseOlderDao.await(5, TimeUnit.SECONDS))
                        }
                        super.upsertAll(messageList)
                        if (messageList.any { it.content == "atomic refresh" }) refreshDaoPersisted.countDown()
                    }
                }
            fakeRepo = FakeChatPersistenceRepository(dao)
            val viewModel = createPaginatedViewModel()
            var refreshFetches = 0
            coEvery { mockApi.getSessionMessages("paged", 150, 0, true, "latest") } answers {
                refreshFetches++
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = 200,
                                    role = "assistant",
                                    content = "atomic refresh",
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(150, 0, "latest", 150, 300),
                    ),
                )
            }
            val staleRevision = fakeRepo.replacementGeneration("paged")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val olderWrite =
                    executor.submit<Boolean> {
                        kotlinx.coroutines.runBlocking {
                            fakeRepo.persistMessagesIfCurrent(
                                listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "stale inside dao")),
                                "paged",
                                staleRevision,
                            )
                        }
                    }
                assertTrue(olderDaoEntered.await(5, TimeUnit.SECONDS))
                viewModel.refreshCurrentSession()
                runCurrent()

                assertEquals(0, refreshFetches)
                assertTrue(viewModel.uiState.value.isLoading)
                releaseOlderDao.countDown()
                assertTrue(olderWrite.get(5, TimeUnit.SECONDS))
                runCurrent()
                assertTrue(refreshDaoPersisted.await(5, TimeUnit.SECONDS))
                runCurrent()

                assertEquals(1, refreshFetches)
                assertEquals(listOf("atomic refresh"), viewModel.uiState.value.messages.map { it.content })
                assertTrue(fakeRepo.loadMessages("paged").any { it.content == "atomic refresh" })
            } finally {
                releaseOlderDao.countDown()
                executor.shutdownNow()
            }
        }

    // ── Session switch ───────────────────────────────────────────────────────

    @Test
    fun testSwitchSession() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.switchSession("session-456")
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
            assertTrue(
                viewModel.uiState.value.messages
                    .isEmpty(),
            )

            verify {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-456", "omit_messages" to true),
                    any(),
                )
            }
        }

    @Test
    fun testSessionResumeRpcResult_restoresHistoryFromPayload() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val resumeRequestId = "resume-history"
            coEvery {
                ApiClient.hermesApi.getSessionMessages(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
            every {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-root", "omit_messages" to true),
                    any(),
                )
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke(resumeRequestId)
                resumeRequestId
            }

            viewModel.switchSession("session-root")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-456",
                        "resumed" to "session-tip",
                        "message_count" to 2.0,
                        "info" to
                            mapOf(
                                "model" to "gpt-5.6-sol",
                                "provider" to "openai-codex",
                                "reasoning_effort" to "xhigh",
                                "usage" to
                                    mapOf(
                                        "context_used" to 44_000,
                                        "context_max" to 272_000,
                                        "input" to 640_000,
                                    ),
                            ),
                        "messages" to
                            listOf(
                                mapOf(
                                    "role" to "user",
                                    "text" to "Earlier question",
                                ),
                                mapOf(
                                    "role" to "assistant",
                                    "text" to "Earlier answer",
                                    "reasoning" to "Earlier reasoning",
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            val messages = viewModel.uiState.value.messages
            assertEquals(
                "session-tip",
                viewModel.uiState.value.currentSessionId,
            )
            assertEquals(
                "openai-codex/gpt-5.6-sol",
                viewModel.uiState.value.currentSessionModel,
            )
            assertEquals("xhigh", viewModel.uiState.value.reasoningLevel)
            assertEquals(44_000L, viewModel.uiState.value.contextUsage?.usedTokens)
            assertEquals(640_000L, viewModel.uiState.value.contextUsage?.inputTokens)
            assertEquals(2, messages.size)
            assertEquals("Earlier question", messages[0].content)
            assertEquals(MessageRole.USER, messages[0].role)
            assertEquals("Earlier answer", messages[1].content)
            assertEquals(MessageRole.ASSISTANT, messages[1].role)
            assertEquals("Earlier reasoning", messages[1].reasoningText)
            assertEquals(2, messages.size)

            viewModel.refreshCurrentSession()
            advanceUntilIdle()

            assertEquals(
                listOf("Earlier question", "Earlier answer"),
                viewModel.uiState.value.messages.map { it.content },
            )
        }

    @Test
    fun testStaleSessionResumeRpcResult_isIgnored() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    any(),
                    any(),
                )
            } answers {
                val sessionId = arg<Map<String, Any>>(1)["session_id"] as String
                val requestId = "resume-$sessionId"
                arg<((String) -> Unit)?>(2)?.invoke(requestId)
                requestId
            }

            viewModel.switchSession("session-a")
            advanceUntilIdle()
            viewModel.switchSession("session-b")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    "resume-session-a",
                    mapOf(
                        "session_id" to "runtime-a",
                        "resumed" to "session-a-tip",
                        "messages" to
                            listOf(
                                mapOf(
                                    "role" to "assistant",
                                    "text" to "Wrong session",
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertTrue(viewModel.uiState.value.messages.isEmpty())
        }

    @Test
    fun testSessionResumeResultAfterUndo_isFullyIgnored() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val resumeId = "resume-before-undo"
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(resumeId)
                resumeId
            }
            val undoResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.requestForConnection(any(), WsMethods.COMMAND_DISPATCH, any(), any())
            } returns undoResult

            viewModel.switchSession(sessionId)
            advanceUntilIdle()
            viewModel.sendMessage("/undo")
            runCurrent()
            undoResult.complete(mapOf("type" to "prefill", "message" to "kept", "notice" to "rewound"))
            advanceUntilIdle()
            val before = viewModel.uiState.value
            val persistedBefore = fakeRepo.loadMessages(sessionId)

            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeId,
                    mapOf(
                        "session_id" to "stale-runtime",
                        "resumed" to "stale-storage",
                        "messages" to listOf(mapOf("role" to "assistant", "text" to "stale resume")),
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(before, viewModel.uiState.value)
            assertEquals(persistedBefore, fakeRepo.loadMessages(sessionId))
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale resume" })
        }

    @Test
    fun testStaleSessionResumeError_isFullyIgnored() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val sessionId = "storage-root"
            val resumeId = "resume-before-reconnect"
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(resumeId)
                resumeId
            }
            viewModel.switchSession(sessionId)
            advanceUntilIdle()
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()
            val before = viewModel.uiState.value
            val persistedBefore = fakeRepo.loadMessages(sessionId)

            mockEventsFlow.emit(WsEvent.RpcError(resumeId, JsonRpcError(5000, "stale resume error")))
            advanceUntilIdle()

            assertEquals(before, viewModel.uiState.value)
            assertEquals(persistedBefore, fakeRepo.loadMessages(sessionId))
            assertFalse(viewModel.uiState.value.messages.any { it.content.contains("stale resume error") })
        }

    @Test
    fun testValidSessionResumeTransitionsRuntimeIdentityOnce() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val resumeId = "valid-resume"
            every { HermesWsClient.send(WsMethods.SESSION_RESUME, any(), any()) } answers {
                arg<((String) -> Unit)?>(2)?.invoke(resumeId)
                resumeId
            }
            viewModel.switchSession("storage-root")
            advanceUntilIdle()
            val result = mapOf("session_id" to "runtime-tip", "resumed" to "storage-tip")

            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, result))
            advanceUntilIdle()
            assertEquals("storage-tip", viewModel.uiState.value.currentSessionId)
            assertEquals("runtime-tip", ActiveSessionHolder.activeSessionId.value)
            assertEquals(0, viewModel.uiState.value.messages.count { it.content == "Session resumed" })

            mockEventsFlow.emit(WsEvent.RpcResult(resumeId, result))
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.messages.count { it.content == "Session resumed" })
        }

    @Test
    fun testResumePayload_doesNotBreakRestPagination() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery { api.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-root",
                                    message_count = 200,
                                ),
                            ),
                    ),
                )
            coEvery {
                api.getSessionMessages("session-root", 150, 0, true, "latest")
            } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "user",
                                    content = "REST question",
                                ),
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    role = "assistant",
                                    content = "REST answer",
                                    reasoning = "REST reasoning",
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                limit = 150,
                                offset = 0,
                                order = "latest",
                                returned = 150,
                            ),
                    ),
                )
            var olderPageRequested = false
            coEvery {
                api.getSessionMessages("session-root", 150, 150, true, "latest")
            } answers {
                olderPageRequested = true
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
            }
            val resumeRequestId = "resume-pagination"
            every {
                HermesWsClient.send(
                    WsMethods.SESSION_RESUME,
                    mapOf("session_id" to "session-root", "omit_messages" to true),
                    any(),
                )
            } answers {
                arg<((String) -> Unit)?>(2)?.invoke(resumeRequestId)
                resumeRequestId
            }

            viewModel.switchSession("session-root")
            advanceUntilIdle()
            mockEventsFlow.emit(
                WsEvent.RpcResult(
                    resumeRequestId,
                    mapOf(
                        "session_id" to "runtime-root",
                        "resumed" to "session-root",
                        "messages" to
                            listOf(
                                mapOf(
                                    "role" to "assistant",
                                    "text" to "Fallback answer",
                                ),
                            ),
                    ),
                ),
            )
            advanceUntilIdle()
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertTrue(olderPageRequested)
            assertFalse(
                viewModel.uiState.value.messages.any {
                    it.content == "Fallback answer"
                },
            )
            assertEquals(
                "REST reasoning",
                viewModel.uiState.value.messages
                    .first { it.content == "REST answer" }
                    .reasoningText,
            )
        }

    @Test
    fun concurrentFullRefresh_newestOwnerWinsAllHistoryState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val releaseOlder = CompletableDeferred<Unit>()
            var initialCalls = 0
            val requests = mutableListOf<Pair<Int, String?>>()
            coEvery { mockApi.getSessionMessages(sessionId, any(), any(), true, any()) } coAnswers {
                val offset = arg<Int>(2)
                val order = arg<String?>(4)
                requests += offset to order
                initialCalls++
                val response =
                    when (initialCalls) {
                        1 -> {
                            withContext(NonCancellable) { releaseOlder.await() }
                            retrofit2.Response.success(
                                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                                    messages =
                                        listOf(
                                            com.m57.hermescontrol.data.model.SessionMessage(
                                                role = "assistant",
                                                content = "stale",
                                            ),
                                        ),
                                    pagination =
                                        com.m57.hermescontrol.data.model.SessionMessagePagination(
                                            150,
                                            250,
                                            null,
                                            1,
                                            400,
                                        ),
                                ),
                            )
                        }
                        2 ->
                            retrofit2.Response.success(
                                com.m57.hermescontrol.data.model.SessionMessagesResponse(
                                    messages =
                                        listOf(
                                            com.m57.hermescontrol.data.model.SessionMessage(
                                                id = 9,
                                                role = "assistant",
                                                content = "newest",
                                            ),
                                        ),
                                    pagination =
                                        com.m57.hermescontrol.data.model.SessionMessagePagination(
                                            150,
                                            0,
                                            "latest",
                                            150,
                                            400,
                                        ),
                                ),
                            )
                        else ->
                            retrofit2.Response.success(
                                com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                            )
                    }
                response
            }

            viewModel.refreshCurrentSession()
            runCurrent()
            viewModel.refreshCurrentSession()
            runCurrent()
            assertEquals("newest", viewModel.uiState.value.messages.last().content)
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)

            releaseOlder.complete(Unit)
            advanceUntilIdle()
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertEquals("newest", viewModel.uiState.value.messages.last().content)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale" })
            assertFalse(fakeRepo.loadMessages(sessionId).any { it.content == "stale" })
            assertTrue(requests.contains(150 to "latest"))
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun concurrentFullRefresh_staleFailureCannotMutateNewestOwner() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val releaseFailure = CompletableDeferred<Unit>()
            var calls = 0
            coEvery { mockApi.getSessionMessages(sessionId, 150, 0, true, "latest") } coAnswers {
                calls++
                if (calls == 1) {
                    withContext(NonCancellable) { releaseFailure.await() }
                    retrofit2.Response.error(500, okhttp3.ResponseBody.create(null, "stale failure"))
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = 2,
                                        role = "assistant",
                                        content = "current",
                                    ),
                                ),
                            pagination =
                                com.m57.hermescontrol.data.model.SessionMessagePagination(
                                    150,
                                    0,
                                    "latest",
                                    1,
                                    1,
                                ),
                        ),
                    )
                }
            }

            viewModel.refreshCurrentSession()
            runCurrent()
            viewModel.refreshCurrentSession()
            runCurrent()
            releaseFailure.complete(Unit)
            advanceUntilIdle()

            assertEquals("current", viewModel.uiState.value.messages.last().content)
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale failure" })
            assertFalse(fakeRepo.loadMessages(sessionId).any { it.content == "stale failure" })
            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun fullRefreshRetiresSyncAndStaleCleanupCannotReleaseNewSyncOwner() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val releaseStaleSync = CompletableDeferred<Unit>()
            val releaseRefresh = CompletableDeferred<Unit>()
            val releaseNewSync = CompletableDeferred<Unit>()
            var calls = 0
            coEvery { mockApi.getSessionMessages(sessionId, 150, any(), true, any()) } coAnswers {
                calls++
                val content =
                    when (calls) {
                        1 -> {
                            withContext(NonCancellable) { releaseStaleSync.await() }
                            "stale sync"
                        }
                        2 -> {
                            releaseRefresh.await()
                            "full refresh"
                        }
                        3 -> {
                            releaseNewSync.await()
                            "new sync"
                        }
                        else -> "post-refresh sync"
                    }
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionMessage(
                                    id = calls,
                                    role = "assistant",
                                    content = content,
                                ),
                            ),
                        pagination =
                            com.m57.hermescontrol.data.model.SessionMessagePagination(
                                150,
                                0,
                                "latest",
                                1,
                                1,
                            ),
                    ),
                )
            }

            viewModel.syncCurrentSession()
            runCurrent()
            viewModel.refreshCurrentSession()
            runCurrent()

            // isLoading fences incremental sync while the replacement is active.
            viewModel.syncCurrentSession()
            runCurrent()
            assertEquals(2, calls)

            releaseRefresh.complete(Unit)
            runCurrent()
            assertEquals(listOf("full refresh"), viewModel.uiState.value.messages.map { it.content })

            viewModel.syncCurrentSession()
            runCurrent()
            assertEquals(3, calls)
            releaseStaleSync.complete(Unit)
            runCurrent()

            // The retired sync's finally block must not clear the newer owner.
            viewModel.syncCurrentSession()
            runCurrent()
            assertEquals(3, calls)
            assertFalse(fakeRepo.loadMessages(sessionId).any { it.content == "stale sync" })
            assertFalse(viewModel.uiState.value.messages.any { it.content == "stale sync" })

            releaseNewSync.complete(Unit)
            advanceUntilIdle()
            viewModel.syncCurrentSession()
            advanceUntilIdle()

            assertEquals(4, calls)
            assertTrue(viewModel.uiState.value.messages.any { it.content == "post-refresh sync" })
            assertTrue(fakeRepo.loadMessages(sessionId).any { it.content == "post-refresh sync" })
        }

    @Test
    fun cachedHistorySurvivesViewModelRecreationWhenRefreshIsOffline() =
        runTest {
            fakeRepo.dao.addMessageDirect(cachedEntity("offline-session", "cached history"))
            coEvery { mockApi.getSessionMessages(any(), any(), any(), true, any()) } throws
                java.io.IOException("offline")

            val recreated = createViewModel()
            recreated.switchSession("offline-session")
            advanceUntilIdle()

            assertEquals(listOf("cached history"), recreated.uiState.value.messages.map { it.content })
            assertFalse(recreated.uiState.value.isLoading)
            assertTrue(recreated.uiState.value.errorMessage?.contains("Failed to load messages") == true)
        }

    @Test
    fun lateCacheCannotReplaceSuccessfulRefresh() =
        runTest {
            val releaseCache = CompletableDeferred<Unit>()
            val delayedRepo =
                object : FakeChatPersistenceRepository() {
                    override suspend fun loadMessages(sessionId: String): List<ChatMessage> {
                        withContext(NonCancellable) { releaseCache.await() }
                        return listOf(ChatMessage(id = "cached", role = MessageRole.ASSISTANT, content = "stale cache"))
                    }
                }
            coEvery { mockApi.getSessionMessages(any(), any(), any(), true, any()) } returns serverMessages("server")
            val viewModel = ChatViewModel(app, false, delayedRepo, fakeSlashUsageStore, testDispatcher)

            viewModel.switchSession("session-a")
            runCurrent()
            assertEquals(listOf("server"), viewModel.uiState.value.messages.map { it.content })
            releaseCache.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("server"), viewModel.uiState.value.messages.map { it.content })
        }

    @Test
    fun lateCacheFromOldSessionAndProfileIsRejected() =
        runTest {
            val releaseCache = CompletableDeferred<Unit>()
            val selectedProfile = MutableStateFlow("profile-a")
            val delayedRepo =
                object : FakeChatPersistenceRepository() {
                    override suspend fun loadMessages(sessionId: String): List<ChatMessage> {
                        if (sessionId != "session-a") return emptyList()
                        withContext(NonCancellable) { releaseCache.await() }
                        return listOf(
                            ChatMessage(
                                id = sessionId,
                                role = MessageRole.ASSISTANT,
                                content = "stale-$sessionId",
                            ),
                        )
                    }
                }
            coEvery { mockApi.getSessionMessages(any(), any(), any(), true, any()) } throws
                java.io.IOException("offline")
            val viewModel =
                ChatViewModel(
                    app,
                    false,
                    delayedRepo,
                    fakeSlashUsageStore,
                    testDispatcher,
                    selectedProfileId = { selectedProfile.value },
                    selectedProfileIds = selectedProfile,
                )

            viewModel.switchSession("session-a")
            runCurrent()
            viewModel.switchSession("session-b")
            selectedProfile.value = "profile-b"
            releaseCache.complete(Unit)
            advanceUntilIdle()

            assertEquals("session-b", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.messages.any { it.content.startsWith("stale-") })
        }

    @Test
    fun successfulRefreshSupersedesCacheWithoutLosingNewerLiveMessage() =
        runTest {
            val releaseRefresh = CompletableDeferred<Unit>()
            fakeRepo.dao.addMessageDirect(cachedEntity("session-a", "cached history"))
            coEvery { mockApi.getSessionMessages(any(), any(), any(), true, any()) } coAnswers {
                releaseRefresh.await()
                serverMessages("server").body()!!
                    .let { retrofit2.Response.success(it) }
            }
            val viewModel = createViewModel()

            viewModel.switchSession("session-a")
            runCurrent()
            assertEquals(listOf("cached history"), viewModel.uiState.value.messages.map { it.content })
            mockEventsFlow.emit(WsEvent.MessageComplete("live", sessionId = "session-a"))
            runCurrent()
            releaseRefresh.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("server", "live"), viewModel.uiState.value.messages.map { it.content })
        }

    @Test
    fun staleMessageCountResponseCannotMutateSessionsOrDriveHistoryRequest() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val releaseCount = CompletableDeferred<Unit>()
            val requests = mutableListOf<Pair<String, Int>>()
            coEvery { mockApi.getSessionMessages(any(), any(), any(), true, "latest") } answers {
                requests += arg<String>(0) to arg<Int>(2)
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(messages = emptyList()),
                )
            }
            coEvery { mockApi.getSessions(any(), any(), any()) } coAnswers {
                withContext(NonCancellable) { releaseCount.await() }
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(id = sessionId, message_count = 400),
                            ),
                    ),
                )
            }

            viewModel.refreshCurrentSession()
            runCurrent()
            viewModel.switchSession("replacement")
            runCurrent()
            releaseCount.complete(Unit)
            advanceUntilIdle()

            assertEquals("replacement", viewModel.uiState.value.currentSessionId)
            assertFalse(viewModel.uiState.value.sessions.any { it.id == sessionId && it.messageCount == 400 })
            assertFalse(requests.contains(sessionId to 250))
        }

    @Test
    fun testCompactedHistory_usesLatestOrderAndPagesBackward() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            coEvery { api.getSessions(any(), any(), any()) } returns
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-root",
                                    message_count = 200,
                                ),
                            ),
                    ),
                )

            data class PageRequest(
                val limit: Int?,
                val offset: Int,
                val includeCompacted: Boolean?,
                val order: String?,
            )

            val pageRequests = mutableListOf<PageRequest>()
            coEvery {
                api.getSessionMessages(any(), any(), any(), any(), any())
            } answers {
                val request =
                    PageRequest(
                        limit = arg(1),
                        offset = arg(2),
                        includeCompacted = arg(3),
                        order = arg(4),
                    )
                pageRequests += request
                if (request.offset == 0) {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = 2836,
                                        role = "user",
                                        content = "Recent turn",
                                    ),
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = 2837,
                                        role = "assistant",
                                        content = "Recent reply",
                                    ),
                                ),
                            pagination =
                                com.m57.hermescontrol.data.model.SessionMessagePagination(
                                    limit = 150,
                                    offset = 0,
                                    order = "latest",
                                    returned = 150,
                                ),
                        ),
                    )
                } else {
                    retrofit2.Response.success(
                        com.m57.hermescontrol.data.model.SessionMessagesResponse(
                            messages =
                                listOf(
                                    com.m57.hermescontrol.data.model.SessionMessage(
                                        id = 2686,
                                        role = "user",
                                        content = "Archived turn",
                                    ),
                                ),
                            pagination =
                                com.m57.hermescontrol.data.model.SessionMessagePagination(
                                    limit = 150,
                                    offset = 150,
                                    order = "latest",
                                    returned = 1,
                                ),
                        ),
                    )
                }
            }

            viewModel.switchSession("session-root")
            advanceUntilIdle()
            viewModel.loadOlderMessages()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PageRequest(150, 0, true, "latest"),
                    PageRequest(150, 150, true, "latest"),
                ),
                pageRequests,
            )
            assertEquals(
                listOf("Archived turn", "Recent turn", "Recent reply"),
                viewModel.uiState.value.messages.map { it.content },
            )
            assertEquals(
                "rest-session-root-2686",
                viewModel.uiState.value.messages.first().id,
            )
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun testEmptyInitialRestPage_disablesOlderPagination() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val api = ApiClient.hermesApi
            var countRequested = false
            coEvery { api.getSessions(any(), any(), any()) } answers {
                countRequested = true
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionListResponse(
                        sessions =
                            listOf(
                                com.m57.hermescontrol.data.model.SessionInfo(
                                    id = "session-root",
                                    message_count = 200,
                                ),
                            ),
                    ),
                )
            }
            val pageRequests = mutableListOf<Triple<String, Int, Int>>()
            coEvery {
                api.getSessionMessages(any(), any(), any(), any(), any())
            } answers {
                pageRequests += Triple(arg(0), arg(1), arg(2))
                retrofit2.Response.success(
                    com.m57.hermescontrol.data.model.SessionMessagesResponse(
                        messages = emptyList(),
                    ),
                )
            }

            viewModel.switchSession("session-root")
            advanceUntilIdle()

            assertTrue(countRequested)
            assertEquals(
                listOf(
                    Triple("session-root", 150, 0),
                    Triple("session-root", 150, 50),
                ),
                pageRequests,
            )
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.messages.isEmpty())
            assertFalse(viewModel.uiState.value.hasOlderMessages)
        }

    @Test
    fun testInterruptSession_withSessionId_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, mapOf("session_id" to sessionId), any()) }
        }

    @Test
    fun testInterruptSession_withoutSessionId_doesNotSendRpc() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.interruptSession()
            advanceUntilIdle()

            verify(exactly = 0) { HermesWsClient.send(WsMethods.SESSION_INTERRUPT, any(), any()) }
        }

    // ── Error handling ───────────────────────────────────────────────────────

    @Test
    fun testRpcErrorHandling() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            mockEventsFlow.emit(
                WsEvent.RpcError(
                    "req-id-1",
                    JsonRpcError(code = -32603, message = "Internal error during creation"),
                ),
            )
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.errorMessage!!
                    .contains("Internal error during creation"),
            )
        }

    // ── Session mismatch ─────────────────────────────────────────────────────

    @Test
    fun testSessionMismatchEventsAreIgnored() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(
                WsEvent.ToolStart(name = "calculator", data = mapOf("input" to "2+2"), sessionId = "session-other"),
            )
            mockEventsFlow.emit(
                WsEvent.ClarifyRequest(
                    text = "Choose:",
                    options = listOf("Yes"),
                    clarifyId = "clarify-1",
                    sessionId = "session-other",
                ),
            )
            mockEventsFlow.emit(WsEvent.MessageStart("session-other"))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", "session-other"))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.messages.size)
            assertEquals(
                "Session created",
                viewModel.uiState.value.messages[0]
                    .content,
            )
            assertNull(viewModel.streamingState.value.streamingMessage)
            assertNull(viewModel.uiState.value.clarifyRequest)
        }

    // ── Reconnect ────────────────────────────────────────────────────────────

    @Test
    fun testReconnectDoesNotDuplicateEventCollection() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            viewModel.reconnect()
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.MessageStart(sessionId))
            mockEventsFlow.emit(WsEvent.MessageToken("Hello", sessionId))
            advanceUntilIdle()

            assertEquals(
                "Hello",
                viewModel.streamingState.value.streamingMessage
                    ?.content,
            )
        }

    // ── MessageComplete without streaming ────────────────────────────────────

    @Test
    fun testMessageCompleteWithoutStreaming_upsertsAssistantMessage() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(WsEvent.MessageComplete("Fully complete message", sessionId))
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.messages.size)
            assertEquals(
                "Fully complete message",
                viewModel.uiState.value.messages[1]
                    .content,
            )
            assertEquals(
                MessageRole.ASSISTANT,
                viewModel.uiState.value.messages[1]
                    .role,
            )
        }

    // ── Privileged request binding (hermes-agent d90045be2 / a77692158) ──────
    //
    // Every privileged frame is answered on the exact request, runtime session,
    // profile, and socket generation that dispatched it. Anything else is
    // dropped rather than surfaced with controls that would resolve some other
    // request.

    private data class PrivilegedCall(
        val method: String,
        val binding: PrivilegedRequestBinding,
        val params: Map<String, String>,
    )

    /** Route [HermesWsClient.privilegedRequest] into a recording list. */
    private fun capturePrivileged(
        result: CompletableDeferred<Any?> = CompletableDeferred<Any?>(mapOf("status" to "ok")),
    ): MutableList<PrivilegedCall> {
        val calls = mutableListOf<PrivilegedCall>()
        every { HermesWsClient.privilegedRequest(any(), any(), any()) } answers {
            calls += PrivilegedCall(arg(0), arg(1), arg(2))
            result
        }
        return calls
    }

    private fun approvalRequest(
        requestId: String = "approval-1",
        sessionId: String? = "session-123",
        profileId: String? = "profile-a",
        generation: Int? = 7,
        timeoutSeconds: Double = 300.0,
    ) = WsEvent.ApprovalRequest(
        command = "rm -rf /data",
        description = "The agent wants to execute: rm -rf /data",
        patternKeys = listOf("shell:rm"),
        sessionId = sessionId,
        requestId = requestId,
        timeoutSeconds = timeoutSeconds,
        sourceProfileId = profileId,
        connectionGeneration = generation,
    )

    private fun sudoRequest(
        requestId: String = "sudo-1",
        sessionId: String? = "session-123",
        profileId: String? = "profile-a",
        generation: Int? = 7,
    ) = WsEvent.SudoRequest(
        requestId = requestId,
        sessionId = sessionId,
        sourceProfileId = profileId,
        connectionGeneration = generation,
    )

    private fun secretRequest(
        requestId: String = "secret-1",
        sessionId: String? = "session-123",
        profileId: String? = "profile-a",
        generation: Int? = 7,
    ) = WsEvent.SecretRequest(
        requestId = requestId,
        sessionId = sessionId,
        envVar = "GITHUB_TOKEN",
        prompt = "Token for github.com",
        sourceProfileId = profileId,
        connectionGeneration = generation,
    )

    private fun ChatViewModel.approvalMessage(): ChatMessage? =
        uiState.value.messages.lastOrNull { it.approvalInfo != null }

    private fun ChatViewModel.respondToApproval(action: String) {
        val message = requireNotNull(approvalMessage())
        respondToApproval(message.id, requireNotNull(message.approvalInfo).privilegedBinding, action)
    }

    private fun ChatViewModel.cancelApproval() {
        val message = requireNotNull(approvalMessage())
        cancelApproval(message.id, requireNotNull(message.approvalInfo).privilegedBinding)
    }

    private fun serverRequest(
        id: String,
        method: String,
        sessionId: String = "session-123",
        replayed: Boolean = false,
        generation: Int = 7,
        extra: Map<String, Any?> = emptyMap(),
    ) = WsEvent.ServerRequest(
        id = id,
        method = method,
        params = mapOf("session_id" to sessionId) + extra,
        replayed = replayed,
        sourceProfileId = "profile-a",
        connectionGeneration = generation,
    )

    // ── Gateway server-request regressions ─────────────────────────────────

    @Test
    fun gatewayVault_promptsUseExactIdsAndMethodSpecificStringValues() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val bindings = mutableListOf<ServerRequestBinding>()
            val results = mutableListOf<JsonElement>()
            every { HermesWsClient.respondToServerRequest(capture(bindings), capture(results)) } returns true

            mockEventsFlow.emit(serverRequest("code-id", "vault.code"))
            runCurrent()
            viewModel.respondToVault("01A9")
            runCurrent()
            mockEventsFlow.emit(
                serverRequest("login-id", "vault.save_login", extra = mapOf("origin" to "https://example.com")),
            )
            runCurrent()
            viewModel.respondToVaultLogin("alice", "pw")
            runCurrent()

            assertEquals(listOf("code-id", "login-id"), bindings.map { it.requestId })
            assertTrue(bindings.all { it.runtimeSessionId == sessionId })
            assertEquals("{\"value\":\"01A9\"}", results[0].toString())
            assertEquals(
                "{\"value\":\"{\\\"identifier\\\":\\\"alice\\\",\\\"password\\\":\\\"pw\\\"}\"}",
                results[1].toString(),
            )
        }

    @Test
    fun gatewayVault_saveLoginRetainsAndValidatesRequestedOrigin() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.respondToServerRequest(any(), any()) } returns true

            mockEventsFlow.emit(
                serverRequest(
                    "valid",
                    "vault.save_login",
                    extra = mapOf("site" to "https://EXAMPLE.com:8443", "title" to "Misleading title"),
                ),
            )
            runCurrent()
            assertEquals("https://EXAMPLE.com:8443", viewModel.uiState.value.vaultPrompt?.requestedOrigin)
            assertTrue(requireNotNull(viewModel.uiState.value.vaultPrompt).hasValidRequestedOrigin)

            mockEventsFlow.emit(
                serverRequest("missing", "vault.save_login", extra = mapOf("domain" to "example.com")),
            )
            runCurrent()
            assertFalse(requireNotNull(viewModel.uiState.value.vaultPrompt).hasValidRequestedOrigin)
            viewModel.respondToVaultLogin("alice", "pw")
            runCurrent()
            verify(exactly = 0) { HermesWsClient.respondToServerRequest(any(), any()) }

            mockEventsFlow.emit(
                serverRequest(
                    "invalid",
                    "vault.save_login",
                    extra = mapOf("origin" to "https://user@example.com/path?query=1"),
                ),
            )
            runCurrent()
            assertFalse(requireNotNull(viewModel.uiState.value.vaultPrompt).hasValidRequestedOrigin)
        }

    @Test
    fun gatewayVault_originSchemeAndPortValidation() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val cases =
                mapOf(
                    "HTTPS://example.com" to true,
                    "hTtP://example.com:1" to true,
                    "https://example.com:65535" to true,
                    "https://[::1]:443" to true,
                    "https://example.com:" to false,
                    "https://example.com:0" to false,
                    "https://example.com:65536" to false,
                    "https://example.com:-1" to false,
                    "https://[::1]:" to false,
                )
            cases.entries.forEachIndexed { index, (origin, valid) ->
                mockEventsFlow.emit(
                    serverRequest("origin-$index", "vault.save_login", extra = mapOf("origin" to origin)),
                )
                runCurrent()
                val prompt = requireNotNull(viewModel.uiState.value.vaultPrompt)
                assertEquals(origin, prompt.requestedOrigin)
                assertEquals(origin, valid, prompt.hasValidRequestedOrigin)
            }
        }

    @Test
    fun gatewayVault_rejectedSendRetainsEnabledPromptAndDoubleSubmitWritesOnce() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.respondToServerRequest(any(), any()) } returns false
            mockEventsFlow.emit(serverRequest("unlock-id", "vault.unlock_prompt"))
            runCurrent()

            viewModel.respondToVault("pw")
            viewModel.respondToVault("pw")
            runCurrent()

            verify(exactly = 1) { HermesWsClient.respondToServerRequest(any(), any()) }
            assertFalse(requireNotNull(viewModel.uiState.value.vaultPrompt).isSubmitting)
        }

    @Test
    fun gatewayVault_replacementAndCancellationRequireFullIdentity() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(serverRequest("same", "vault.code"))
            runCurrent()
            val original = requireNotNull(viewModel.uiState.value.vaultPrompt)
            mockEventsFlow.emit(serverRequest("same", "vault.code", replayed = true))
            runCurrent()
            assertEquals(original, viewModel.uiState.value.vaultPrompt)
            mockEventsFlow.emit(serverRequest("replacement", "vault.code"))
            runCurrent()
            assertEquals("replacement", viewModel.uiState.value.vaultPrompt?.binding?.requestId)
            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled("replacement", "vault.code", "x", sessionId, "profile-a", 8),
            )
            runCurrent()
            assertNotNull(viewModel.uiState.value.vaultPrompt)
            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled("replacement", "vault.code", "x", sessionId, "profile-a", 7),
            )
            runCurrent()
            assertNull(viewModel.uiState.value.vaultPrompt)
        }

    @Test
    fun gatewayApproval_answersTheExactServerIdWithoutMethodRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val binding = slot<ServerRequestBinding>()
            val result = slot<JsonElement>()
            every { HermesWsClient.respondToServerRequest(capture(binding), capture(result)) } returns true

            mockEventsFlow.emit(serverRequest("wire-approval-42", "approval", extra = mapOf("command" to "pwd")))
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            assertEquals("wire-approval-42", binding.captured.requestId)
            assertEquals(sessionId, binding.captured.runtimeSessionId)
            assertTrue(result.captured.toString().contains("\"choice\":\"once\""))
            verify(exactly = 0) { HermesWsClient.privilegedRequest(any(), any(), any()) }
            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun gatewayApproval_failedResponseRetainsCardAndPermanentChoicesStayBlocked() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.respondToServerRequest(any(), any()) } returns false
            mockEventsFlow.emit(serverRequest("wire-approval-fail", "approval"))
            runCurrent()

            viewModel.respondToApproval("always")
            viewModel.respondToApproval("session")
            verify(exactly = 0) { HermesWsClient.respondToServerRequest(any(), any()) }
            viewModel.respondToApproval("approve")
            runCurrent()

            assertNotNull(viewModel.approvalMessage())
            assertFalse(viewModel.approvalMessage()!!.approvalInfo!!.isSubmitting)
        }

    @Test
    fun gatewayClarify_mergesLockedAnswersWithoutOverwritingThem() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val result = slot<JsonElement>()
            every { HermesWsClient.respondToServerRequest(any(), capture(result)) } returns true
            mockEventsFlow.emit(
                serverRequest(
                    "wire-clarify-9",
                    "clarify",
                    extra =
                        mapOf(
                            "answers" to mapOf("q1" to "locked"),
                            "questions" to
                                listOf(
                                    mapOf("qid" to "q1", "question" to "First?"),
                                    mapOf("qid" to "q2", "question" to "Second?"),
                                ),
                        ),
                ),
            )
            runCurrent()
            val prompt = requireNotNull(viewModel.uiState.value.clarifyRequest)
            viewModel.respondToClarifyBatch(prompt, mapOf("q1" to "overwrite", "q2" to "new"))
            runCurrent()

            assertTrue(result.captured.toString().contains("\"q1\":\"locked\""))
            assertTrue(result.captured.toString().contains("\"q2\":\"new\""))
        }

    @Test
    fun gatewayCancellation_requiresExactSessionProfileGenerationAndId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(serverRequest("wire-cancel", "approval"))
            runCurrent()

            listOf(
                WsEvent.ServerRequestCancelled("wire-cancel", "approval", "x", "other", "profile-a", 7),
                WsEvent.ServerRequestCancelled("wire-cancel", "approval", "x", sessionId, "profile-a", 8),
                WsEvent.ServerRequestCancelled("other", "approval", "x", sessionId, "profile-a", 7),
            ).forEach {
                mockEventsFlow.emit(it)
                runCurrent()
                assertNotNull(viewModel.approvalMessage())
            }
            mockEventsFlow.emit(
                WsEvent.ServerRequestCancelled("wire-cancel", "approval", "x", sessionId, "profile-a", 7),
            )
            runCurrent()
            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun gatewayReconnectReplay_deduplicatesApprovalCardButReusedIdWithNewBindingReplacesIt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            mockEventsFlow.emit(serverRequest("wire-replay", "approval"))
            mockEventsFlow.emit(serverRequest("wire-replay", "approval", replayed = true))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.messages.count { it.approvalInfo != null })

            mockEventsFlow.emit(serverRequest("wire-replay", "approval", generation = 8))
            runCurrent()
            val approvals = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            assertEquals(1, approvals.size)
            assertEquals(8, approvals.single().approvalInfo?.serverRequestBinding?.connectionGeneration)
        }

    @Test
    fun gatewayClarify_identicalReplayPreservesStateButReusedIdWithNewBindingReplacesIt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val request = serverRequest("clarify-replay", "clarify", extra = mapOf("question" to "First?"))
            mockEventsFlow.emit(request)
            runCurrent()
            val original = requireNotNull(viewModel.uiState.value.clarifyRequest)

            mockEventsFlow.emit(request.copy(replayed = true))
            runCurrent()
            assertTrue(original === viewModel.uiState.value.clarifyRequest)

            mockEventsFlow.emit(request.copy(connectionGeneration = 8))
            runCurrent()
            val replacement = requireNotNull(viewModel.uiState.value.clarifyRequest)
            assertEquals(8, replacement.serverRequestBinding?.connectionGeneration)
            assertFalse(original === replacement)
        }

    @Test
    fun gatewaySudoAndSecret_answerExactServerIds_withoutLegacyMethods() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val bindings = mutableListOf<ServerRequestBinding>()
            val results = mutableListOf<JsonElement>()
            every { HermesWsClient.respondToServerRequest(capture(bindings), capture(results)) } returns true

            mockEventsFlow.emit(serverRequest("wire-sudo", "sudo"))
            runCurrent()
            viewModel.respondToSudo("sudo-value")
            runCurrent()
            mockEventsFlow.emit(serverRequest("wire-secret", "secret"))
            runCurrent()
            viewModel.cancelSecret()
            runCurrent()

            assertEquals(listOf("wire-sudo", "wire-secret"), bindings.map { it.requestId })
            assertTrue(bindings.all { it.runtimeSessionId == sessionId })
            assertEquals(listOf("sudo-value", ""), results.map { it.jsonObject["value"]!!.jsonPrimitive.content })
            verify(exactly = 0) { HermesWsClient.privilegedRequest(any(), any(), any()) }
            assertNull(viewModel.uiState.value.sudoPrompt)
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun gatewaySecret_failedSendRetainsPrompt_andDoubleTapSendsOnce() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            every { HermesWsClient.respondToServerRequest(any(), any()) } returns false
            mockEventsFlow.emit(serverRequest("wire-secret-fail", "secret"))
            runCurrent()

            viewModel.respondToSecret("hidden")
            viewModel.respondToSecret("hidden")
            runCurrent()

            verify(exactly = 1) { HermesWsClient.respondToServerRequest(any(), any()) }
            assertEquals("wire-secret-fail", viewModel.uiState.value.secretPrompt?.serverRequestBinding?.requestId)
            assertFalse(viewModel.uiState.value.secretPrompt!!.isSubmitting)
            assertEquals(PRIVILEGED_FAILURE_TEXT, viewModel.uiState.value.errorMessage)
        }

    @Test
    fun gatewaySudo_duplicateRetainsBinding_replacementAndCancellationUseFullIdentity() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            mockEventsFlow.emit(serverRequest("same-id", "sudo"))
            runCurrent()
            val original = requireNotNull(viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(serverRequest("same-id", "sudo", replayed = true))
            runCurrent()
            assertTrue(original === viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(serverRequest("same-id", "sudo", generation = 8))
            runCurrent()
            val replacement = requireNotNull(viewModel.uiState.value.sudoPrompt)
            assertEquals(8, replacement.serverRequestBinding?.connectionGeneration)

            mockEventsFlow.emit(WsEvent.ServerRequestCancelled("same-id", "secret", "x", sessionId, "profile-a", 8))
            mockEventsFlow.emit(WsEvent.ServerRequestCancelled("same-id", "sudo", "x", sessionId, "profile-a", 7))
            runCurrent()
            assertEquals(replacement, viewModel.uiState.value.sudoPrompt)

            mockEventsFlow.emit(WsEvent.ServerRequestCancelled("same-id", "sudo", "x", sessionId, "profile-a", 8))
            runCurrent()
            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    // ── Approval flow ────────────────────────────────────────────────────────

    @Test
    fun approvalRequest_bindsToDispatchingProfileSessionAndGeneration() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest())
            runCurrent()

            val msg = viewModel.approvalMessage()
            assertNotNull(msg)
            assertTrue(msg!!.content.contains("Approval Required"))
            assertEquals("rm -rf /data", msg.approvalInfo?.command)
            assertEquals(
                PrivilegedRequestBinding(
                    requestId = "approval-1",
                    runtimeSessionId = sessionId,
                    profileId = "profile-a",
                    connectionGeneration = 7,
                ),
                msg.approvalInfo?.privilegedBinding,
            )
        }

    @Test
    fun approvalRequest_fromAnotherProfileIsDropped() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest(profileId = "profile-b"))
            runCurrent()

            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun approvalRequest_withoutSocketGenerationIsDropped() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest(generation = null))
            runCurrent()

            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun approvalRequest_forAnotherRuntimeSessionIsDropped() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest(sessionId = "session-other"))
            runCurrent()

            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun respondToApproval_approveSendsOnceOnTheBoundRequest() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            assertEquals(1, calls.size)
            assertEquals(WsMethods.APPROVAL_RESPOND, calls[0].method)
            assertEquals(mapOf("choice" to "once"), calls[0].params)
            assertEquals("approval-1", calls[0].binding.requestId)
            assertEquals(sessionId, calls[0].binding.runtimeSessionId)
            assertEquals("profile-a", calls[0].binding.profileId)
            assertEquals(7, calls[0].binding.connectionGeneration)
        }

    @Test
    fun respondToApproval_denySendsDeny() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("deny")
            runCurrent()

            assertEquals(listOf(mapOf("choice" to "deny")), calls.map { it.params })
        }

    /** A phone cannot show what a standing allow would later authorize. */
    @Test
    fun respondToApproval_refusesSessionAndAlwaysChoices() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("session")
            viewModel.respondToApproval("always")
            viewModel.respondToApproval("once")
            runCurrent()

            assertTrue("no standing allow may reach the gateway", calls.isEmpty())
            assertNotNull("controls stay live", viewModel.approvalMessage())
        }

    @Test
    fun respondToApproval_clearsControlsOnlyAfterTheGatewayAck() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            capturePrivileged(ack)

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            assertNotNull("controls survive an unacknowledged send", viewModel.approvalMessage())

            ack.complete(mapOf("status" to "ok"))
            runCurrent()

            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun respondToApproval_keepsControlsLiveWhenTheSendFails() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val rejected = CompletableDeferred<Any?>()
            rejected.completeExceptionally(IllegalStateException("no longer active"))
            capturePrivileged(rejected)

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            assertNotNull(
                "an unsent approval must remain answerable",
                viewModel.approvalMessage(),
            )
            assertEquals("no longer active", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun cancelApproval_sendsTypedCancelNotAChoice() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.cancelApproval()
            runCurrent()

            assertEquals(1, calls.size)
            assertEquals(WsMethods.APPROVAL_CANCEL, calls[0].method)
            assertTrue("cancel carries no choice", calls[0].params.isEmpty())
            assertEquals("approval-1", calls[0].binding.requestId)
            assertNull(viewModel.approvalMessage())
        }

    @Test
    fun approvalControls_retireAtTheGatewayPublishedTimeout() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest(timeoutSeconds = 30.0))
            runCurrent()
            assertNotNull(viewModel.approvalMessage())

            advanceTimeBy(29_000)
            runCurrent()
            assertNotNull("controls live until the server's own deadline", viewModel.approvalMessage())

            advanceTimeBy(2_000)
            runCurrent()
            assertNull("controls retire once the request is gone", viewModel.approvalMessage())
        }

    @Test
    fun approvalExpiry_neverRetiresALaterRequestsControls() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(approvalRequest(requestId = "approval-1", timeoutSeconds = 10.0))
            runCurrent()
            mockEventsFlow.emit(approvalRequest(requestId = "approval-2", timeoutSeconds = 600.0))
            runCurrent()
            assertEquals(2, viewModel.uiState.value.messages.count { it.approvalInfo != null })

            advanceTimeBy(11_000)
            runCurrent()

            val live = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            assertEquals(1, live.size)
            assertEquals("approval-2", live[0].approvalInfo?.privilegedBinding?.requestId)
        }

    @Test
    fun reusedApprovalId_keepsIndependentCardTimersAndStaleAckCannotCancelTheNewTimer() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            capturePrivileged(ack)
            mockEventsFlow.emit(approvalRequest(requestId = "reused", timeoutSeconds = 10.0))
            runCurrent()
            val old = requireNotNull(viewModel.approvalMessage())
            viewModel.respondToApproval(old.id, old.approvalInfo!!.privilegedBinding, "approve")
            runCurrent()
            mockEventsFlow.emit(approvalRequest(requestId = "reused", timeoutSeconds = 20.0))
            runCurrent()
            ack.complete(mapOf("status" to "ok"))
            runCurrent()

            assertEquals(1, viewModel.uiState.value.messages.count { it.approvalInfo != null })
            advanceTimeBy(21_000)
            runCurrent()
            assertEquals(0, viewModel.uiState.value.messages.count { it.approvalInfo != null })
        }

    @Test
    fun olderApprovalCardClickTargetsThatExactRequestAndDisablesOnlyThatCard() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            val calls = capturePrivileged(ack)
            mockEventsFlow.emit(approvalRequest(requestId = "approval-old"))
            runCurrent()
            mockEventsFlow.emit(approvalRequest(requestId = "approval-new"))
            runCurrent()
            val cards = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            val older = cards.first()
            val olderBinding = requireNotNull(older.approvalInfo).privilegedBinding

            viewModel.respondToApproval(older.id, olderBinding, "approve")
            viewModel.respondToApproval(older.id, olderBinding, "deny")
            runCurrent()

            assertEquals(listOf("approval-old"), calls.map { it.binding.requestId })
            val pending = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            assertTrue(requireNotNull(pending.first { it.id == older.id }.approvalInfo).isSubmitting)
            assertFalse(requireNotNull(pending.first { it.id != older.id }.approvalInfo).isSubmitting)

            ack.complete(mapOf("status" to "ok"))
            runCurrent()
            val live = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            assertEquals(listOf("approval-new"), live.map { it.approvalInfo!!.privilegedBinding.requestId })
        }

    @Test
    fun failedOlderApprovalRestoresOnlyThatExactCard() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val rejected = CompletableDeferred<Any?>()
            val calls = capturePrivileged(rejected)
            mockEventsFlow.emit(approvalRequest(requestId = "approval-old"))
            runCurrent()
            mockEventsFlow.emit(approvalRequest(requestId = "approval-new"))
            runCurrent()
            val older = viewModel.uiState.value.messages.first { it.approvalInfo != null }
            val binding = older.approvalInfo!!.privilegedBinding

            viewModel.cancelApproval(older.id, binding)
            runCurrent()
            rejected.completeExceptionally(IllegalStateException("not acknowledged"))
            runCurrent()

            assertEquals(listOf("approval-old"), calls.map { it.binding.requestId })
            val cards = viewModel.uiState.value.messages.filter { it.approvalInfo != null }
            assertFalse(cards.any { it.approvalInfo!!.isSubmitting })
            assertEquals("not acknowledged", viewModel.uiState.value.errorMessage)
        }

    // ── Sudo / secret prompt flow (issue #524) ───────────────────────────

    @Test
    fun testSudoRequest_setsPromptState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.sudoPrompt
            assertNotNull(prompt)
            assertEquals("sudo-1", prompt?.requestId)
            assertEquals(sessionId, prompt?.binding?.runtimeSessionId)
            assertEquals("profile-a", prompt?.binding?.profileId)
            assertEquals(7, prompt?.binding?.connectionGeneration)
        }

    @Test
    fun sudoRequest_fromAnotherProfileIsDropped() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            mockEventsFlow.emit(sudoRequest(profileId = "profile-b"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testRespondToSudo_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertEquals(1, calls.size)
            assertEquals(WsMethods.SUDO_RESPOND, calls[0].method)
            assertEquals(mapOf("password" to "hunter2"), calls[0].params)
            assertEquals("sudo-1", calls[0].binding.requestId)
            assertEquals(sessionId, calls[0].binding.runtimeSessionId)
            assertEquals(7, calls[0].binding.connectionGeneration)
        }

    @Test
    fun testRespondToSudo_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            capturePrivileged()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.sudoPrompt)

            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun respondToSudo_keepsPromptUntilTheGatewayAcks() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            capturePrivileged(ack)

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertNotNull("prompt survives an unacknowledged send", viewModel.uiState.value.sudoPrompt)

            ack.complete(mapOf("status" to "ok"))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun sudoSubmission_doubleSendAndCancelDispatchExactlyOnce() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            val calls = capturePrivileged(ack)
            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()

            viewModel.respondToSudo("hunter2")
            viewModel.respondToSudo("hunter2")
            viewModel.cancelSudo()
            runCurrent()

            assertEquals(listOf(WsMethods.SUDO_RESPOND), calls.map { it.method })
            assertTrue(viewModel.uiState.value.sudoPrompt!!.isSubmitting)
        }

    /** A gateway error string could echo the submitted value; `errorMessage` is durable UI state. */
    @Test
    fun respondToSudo_failureNeverSurfacesTheGatewayString() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val rejected = CompletableDeferred<Any?>()
            rejected.completeExceptionally(IllegalStateException("rejected value hunter2"))
            capturePrivileged(rejected)

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.respondToSudo("hunter2")
            advanceUntilIdle()

            assertEquals(PRIVILEGED_FAILURE_TEXT, viewModel.uiState.value.errorMessage)
            assertFalse(
                viewModel.uiState.value.errorMessage
                    .orEmpty()
                    .contains("hunter2"),
            )
            assertNotNull(viewModel.uiState.value.sudoPrompt)
            assertFalse(viewModel.uiState.value.sudoPrompt!!.isSubmitting)
        }

    @Test
    fun respondToSudo_ignoresABlankPassword() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.respondToSudo("   ")
            advanceUntilIdle()

            assertTrue(calls.isEmpty())
            assertNotNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun cancelSudo_sendsTypedCancel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.cancelSudo()
            advanceUntilIdle()

            assertEquals(listOf(WsMethods.SUDO_CANCEL), calls.map { it.method })
            assertTrue(calls[0].params.isEmpty())
            assertEquals("sudo-1", calls[0].binding.requestId)
            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    /** A back gesture is neither an answer nor a cancellation. */
    @Test
    fun dismissSudo_isANoOp() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()
            viewModel.dismissSudo()
            advanceUntilIdle()

            assertTrue(calls.isEmpty())
            assertNotNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun sudoExpire_clearsOnlyTheExactBoundRequest() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(sudoRequest())
            advanceUntilIdle()

            val mismatches =
                listOf(
                    WsEvent.SudoExpire("sudo-1", null, "profile-a", 7),
                    WsEvent.SudoExpire("sudo-1", "   ", "profile-a", 7),
                    WsEvent.SudoExpire("sudo-2", sessionId, "profile-a", 7),
                    WsEvent.SudoExpire("sudo-1", sessionId, "profile-b", 7),
                    WsEvent.SudoExpire("sudo-1", sessionId, "profile-a", 8),
                    WsEvent.SudoExpire("sudo-1", "session-other", "profile-a", 7),
                )
            mismatches.forEach {
                mockEventsFlow.emit(it)
                advanceUntilIdle()
                assertNotNull(
                    "a non-matching expiry must not clear the prompt: $it",
                    viewModel.uiState.value.sudoPrompt,
                )
            }

            mockEventsFlow.emit(WsEvent.SudoExpire("sudo-1", sessionId, "profile-a", 7))
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.sudoPrompt)
        }

    @Test
    fun testSecretRequest_setsPromptState() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()

            val prompt = viewModel.uiState.value.secretPrompt
            assertNotNull(prompt)
            assertEquals("secret-1", prompt?.requestId)
            assertEquals("GITHUB_TOKEN", prompt?.envVar)
            assertEquals(sessionId, prompt?.binding?.runtimeSessionId)
            assertEquals(7, prompt?.binding?.connectionGeneration)
        }

    @Test
    fun privilegedRequestsWithoutExactRuntimeSessionAreIgnored() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            listOf<String?>(null, "   ").forEach { missingSession ->
                mockEventsFlow.emit(approvalRequest(sessionId = missingSession))
                mockEventsFlow.emit(sudoRequest(sessionId = missingSession))
                mockEventsFlow.emit(secretRequest(sessionId = missingSession))
                advanceUntilIdle()
            }

            assertFalse(viewModel.uiState.value.messages.any { it.approvalInfo != null })
            assertNull(viewModel.uiState.value.sudoPrompt)
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun testRespondToSecret_sendsRpc() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertEquals(1, calls.size)
            assertEquals(WsMethods.SECRET_RESPOND, calls[0].method)
            assertEquals(mapOf("value" to "super-secret-token"), calls[0].params)
            assertEquals("secret-1", calls[0].binding.requestId)
            assertEquals(sessionId, calls[0].binding.runtimeSessionId)
        }

    @Test
    fun testRespondToSecret_clearsPrompt() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            capturePrivileged()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun secretSubmission_doubleSendAndCancelDispatchExactlyOnceThenAckClears() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val ack = CompletableDeferred<Any?>()
            val calls = capturePrivileged(ack)
            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()

            viewModel.respondToSecret("super-secret-token")
            viewModel.cancelSecret()
            viewModel.respondToSecret("other")
            runCurrent()

            assertEquals(listOf(WsMethods.SECRET_RESPOND), calls.map { it.method })
            assertTrue(viewModel.uiState.value.secretPrompt!!.isSubmitting)
            ack.complete(mapOf("status" to "ok"))
            runCurrent()
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun respondToSecret_failureNeverSurfacesTheGatewayString() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val rejected = CompletableDeferred<Any?>()
            rejected.completeExceptionally(IllegalStateException("rejected super-secret-token"))
            capturePrivileged(rejected)

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            viewModel.respondToSecret("super-secret-token")
            advanceUntilIdle()

            assertEquals(PRIVILEGED_FAILURE_TEXT, viewModel.uiState.value.errorMessage)
            assertFalse(
                viewModel.uiState.value.errorMessage
                    .orEmpty()
                    .contains("super-secret-token"),
            )
            assertNotNull(viewModel.uiState.value.secretPrompt)
            assertFalse(viewModel.uiState.value.secretPrompt!!.isSubmitting)
        }

    @Test
    fun cancelSecret_sendsTypedCancel() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            viewModel.cancelSecret()
            advanceUntilIdle()

            assertEquals(listOf(WsMethods.SECRET_CANCEL), calls.map { it.method })
            assertTrue(calls[0].params.isEmpty())
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun dismissSecret_isANoOp() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val calls = capturePrivileged()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()
            viewModel.dismissSecret()
            advanceUntilIdle()

            assertTrue(calls.isEmpty())
            assertNotNull(viewModel.uiState.value.secretPrompt)
        }

    @Test
    fun secretExpire_clearsOnlyTheExactBoundRequest() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            mockEventsFlow.emit(secretRequest())
            advanceUntilIdle()

            mockEventsFlow.emit(WsEvent.SecretExpire("secret-1", null, "profile-a", 7))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire("secret-1", "session-other", "profile-a", 7))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire("secret-1", sessionId, "profile-b", 7))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire("secret-1", sessionId, "profile-a", 9))
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.secretPrompt)

            mockEventsFlow.emit(WsEvent.SecretExpire("secret-1", sessionId, "profile-a", 7))
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.secretPrompt)
        }

    /** A frame the parser refused to bind reaches the VM as an inert event. */
    @Test
    fun privilegedRequestRejected_changesNothing() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()
            val before = viewModel.uiState.value

            mockEventsFlow.emit(WsEvent.PrivilegedRequestRejected("approval.request", "session-123"))
            mockEventsFlow.emit(WsEvent.PrivilegedRequestRejected("sudo.request", "session-123"))
            advanceUntilIdle()

            assertEquals(before.messages, viewModel.uiState.value.messages)
            assertNull(viewModel.uiState.value.sudoPrompt)
            assertNull(viewModel.uiState.value.secretPrompt)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun testRefreshSettings_updatesUiState() =
        runTest {
            // Given the default setup, init{} already calls refreshSettings() once,
            // so the initial state reflects the setUp defaults (typingEffectEnabled=true,
            // typingEffectDelayMs=30).
            val viewModel = createViewModel()
            advanceUntilIdle()
            with(viewModel.uiState.value) {
                assertTrue(typingEffectEnabled)
                assertEquals(30, typingEffectDelayMs)
            }

            // When settings change after construction and refreshSettings() is re-invoked,
            // the UI state must reflect the NEW values — this proves refreshSettings()
            // re-reads AuthManager live (the real regression scenario).
            every { AuthManager.isTypingEffectEnabled() } returns false
            every { AuthManager.getTypingEffectDelayMs() } returns 50
            viewModel.refreshSettings()
            advanceUntilIdle()

            // Then
            val state = viewModel.uiState.value
            assertFalse(state.typingEffectEnabled)
            assertEquals(50, state.typingEffectDelayMs)
        }

    @Test
    fun testToggleSearch() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSearchActive)

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSearchActive)

            viewModel.setSearchQuery("test")
            advanceUntilIdle()

            viewModel.toggleSearch()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSearchActive)
            assertEquals("", viewModel.uiState.value.searchQuery)
        }

    @Test
    fun testSendMessage_readContentUriThrowsException_handlesGracefully() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // Android framework Uri.parse throws "not mocked" in plain unit tests,
            // so stub it (no Robolectric here). Then make the resolver throw on read.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri

            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } throws
                SecurityException("Permission denied")

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Error path is logged so we can diagnose the failed read.
            verify {
                Log.e(
                    any(),
                    match { it.contains("SecurityException") },
                )
            }

            // Graceful handling: the message is still sent even though the
            // attachment bytes couldn't be read (no crash, no lost message).
            val sent =
                viewModel.uiState.value.messages
                    .firstOrNull { it.id == sessionId } != null ||
                    viewModel.uiState.value.messages
                        .any { it.content == "Here is an image" }
            assertTrue("Message should still be sent despite attachment read failure", sent)
        }

    /**
     * Regression for the "session not found" (code 4001) error when sending an
     * image: the mobile image-attach path must pass `session_id` to
     * `image.attach_bytes` (the gateway resolves the session from it; desktop
     * does the same). Without it the backend 4001s and the image is dropped.
     * Also asserts the image attach is AWAITED (staged before prompt.submit),
     * not fire-and-forget.
     */
    @Test
    fun testSendMessage_imageAttachment_sendsSessionId() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()

            // sendRpcAndAwait calls HermesWsClient.request(method, params) — the
            // 2-arg form (Kotlin synthesizes request(String, Map)). Stub that
            // exact signature, capture params, and return a completed deferred
            // so the await resolves without hanging.
            val paramsSlot = slot<Map<String, Any>>()
            every {
                HermesWsClient.request(any(), capture(paramsSlot), any())
            } returns CompletableDeferred<Any?>(mapOf("attached" to true))

            // Mock Android's Base64OutputStream constructor to avoid "Stub!" exception in JVM tests
            io.mockk.mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(
                    any<ByteArray>(),
                    any(),
                    any(),
                )
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit

            // The image bytes read via ContentResolver must succeed.
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://dummy") } returns mockUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

            viewModel.addAttachment("content://dummy", "test.png", "image/png", 1000)
            advanceUntilIdle()
            assertTrue(
                "pendingAttachments must contain the image before send",
                viewModel.uiState.value.pendingAttachments
                    .isNotEmpty(),
            )
            assertTrue(
                "attached png must have isImage=true",
                viewModel.uiState.value.pendingAttachments
                    .first()
                    .isImage,
            )

            viewModel.sendMessage("Here is an image")
            advanceUntilIdle()

            // Verify the image attach RPC was issued with session_id.
            verify { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any()) }
            assertEquals(
                "session_id must be forwarded to image.attach_bytes",
                sessionId,
                paramsSlot.captured["session_id"],
            )
        }

    @Test
    fun testSendMessage_suspendedAttachmentDoesNotDispatchAfterSameSessionAba() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val attachmentResult = CompletableDeferred<Any?>()
            every {
                HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any())
            } returns attachmentResult

            mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any())
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            mockkStatic(Uri::class)
            val mockUri = mockk<Uri>()
            every { Uri.parse("content://suspended") } returns mockUri
            val contentResolver = mockk<ContentResolver>()
            every { app.contentResolver } returns contentResolver
            every { contentResolver.openInputStream(any()) } returns
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

            viewModel.addAttachment("content://suspended", "test.png", "image/png", 4)
            viewModel.sendMessage("stale prompt")
            runCurrent()

            viewModel.switchSession("other-session")
            viewModel.switchSession(sessionId)
            attachmentResult.complete(mapOf("attached" to true))
            advanceUntilIdle()

            verify(exactly = 0) {
                HermesWsClient.sendMessage(any(), match { it.contains("stale prompt") }, any(), any())
            }
        }

    @Test
    fun testSendMessage_fileAttachResultsPreserveReferencesForJsonAndMap() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val results =
                ArrayDeque<Any?>(
                    listOf(
                        Json.parseToJsonElement("""{"ref_text":"@file:json-ref"}"""),
                        mapOf("ref_text" to "@file:map-ref"),
                    ),
                )
            every { HermesWsClient.request(WsMethods.FILE_ATTACH, any(), any()) } answers {
                CompletableDeferred(results.removeFirst())
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any())
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            mockkStatic(Uri::class)
            every { Uri.parse(any<String>()) } returns mockk()
            val resolver = mockk<ContentResolver>()
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(any()) } answers {
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }

            viewModel.addAttachment("content://first", "first.txt", "text/plain", 3)
            viewModel.addAttachment("content://second", "second.txt", "text/plain", 3)
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("hello"))
            advanceUntilIdle()

            assertTrue(results.isEmpty())
            verify(exactly = 1) {
                HermesWsClient.sendMessageForConnection(
                    any(),
                    sessionId,
                    "@file:json-ref\n@file:map-ref\n\nhello",
                    any(),
                )
            }
        }

    @Test
    fun testSendMessage_imageAttachResultsRecognizeJsonAndMapAcknowledgements() =
        runTest {
            val (viewModel, sessionId) = createViewModelWithSession()
            val results =
                ArrayDeque<Any?>(
                    listOf(Json.parseToJsonElement("""{"attached":true}"""), mapOf("attached" to true)),
                )
            every { HermesWsClient.request(WsMethods.IMAGE_ATTACH_BYTES, any(), any()) } answers {
                CompletableDeferred(results.removeFirst())
            }
            mockkConstructor(android.util.Base64OutputStream::class)
            every {
                anyConstructed<android.util.Base64OutputStream>().write(any<ByteArray>(), any(), any())
            } returns Unit
            every { anyConstructed<android.util.Base64OutputStream>().close() } returns Unit
            mockkStatic(Uri::class)
            every { Uri.parse(any<String>()) } returns mockk()
            val resolver = mockk<ContentResolver>()
            every { app.contentResolver } returns resolver
            every { resolver.openInputStream(any()) } answers {
                java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }

            viewModel.addAttachment("content://first", "first.png", "image/png", 3)
            viewModel.addAttachment("content://second", "second.png", "image/png", 3)
            advanceUntilIdle()
            assertTrue(viewModel.sendMessage("hello"))
            advanceUntilIdle()

            assertTrue(results.isEmpty())
            verify(exactly = 0) { Log.w(any(), "Image attachment request failed") }
            verify(exactly = 1) {
                HermesWsClient.sendMessageForConnection(any(), sessionId, "hello", any())
            }
        }

    // ── Pending request timeout + rejectAllPending (issue #526) ───────────

    /**
     * On disconnect (RECONNECTING) the ViewModel must run rejectAllPending
     * without throwing and stay usable — mirroring desktop
     * JsonRpcGatewayClient.rejectAllPending invoked on socket close. This is
     * what prevents callers awaiting a CompletableDeferred from hanging
     * across a socket drop.
     */
    @Test
    fun testDisconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            capturePrivileged()
            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            // Simulate socket drop → reconnecting (triggers rejectAllPending).
            mockConnectionStatus.value = ConnectionStatus.RECONNECTING
            advanceUntilIdle()

            // No exception propagated; VM remains usable.
            assertNull(viewModel.uiState.value.errorMessage)
        }

    /**
     * viewModel.reconnect() calls rejectAllPending() before wsClient.disconnect(),
     * so any in-flight awaited RPC is failed fast instead of hanging until its
     * own timeout.
     */
    @Test
    fun testReconnect_rejectsPendingWithoutError() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            capturePrivileged()
            mockEventsFlow.emit(approvalRequest())
            runCurrent()
            viewModel.respondToApproval("approve")
            runCurrent()

            // User-initiated reconnect must not throw / hang.
            viewModel.reconnect()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.errorMessage)
            verify { HermesWsClient.disconnect() }
        }

    @Test
    fun sameMessages_detectsReasoningChanges() {
        val viewModel = createViewModel()
        val before =
            ChatMessage(
                id = "rest-session-0",
                role = MessageRole.ASSISTANT,
                content = "Answer",
                reasoningText = "",
            )
        val after = before.copy(reasoningText = "Restored reasoning")

        assertFalse(viewModel.sameMessages(listOf(before), listOf(after)))
    }

    @Test
    fun sameMessagesDetectsDisplayKindChange() {
        val viewModel = createViewModel()
        val before = ChatMessage(role = MessageRole.USER, content = "marker")
        val after = before.copy(displayKind = "internal_notification")

        assertFalse(viewModel.sameMessages(listOf(before), listOf(after)))
    }

    @Test
    fun `openAttachment GATEWAY success fires ACTION_VIEW with FileProvider uri`() =
        runTest {
            val cacheDir =
                java.io.File(
                    System.getProperty("java.io.tmpdir"),
                    "hermes_open_test_${System.nanoTime()}",
                )
            cacheDir.mkdirs()
            every { app.cacheDir } returns cacheDir

            mockkObject(GatewayFileClient)
            val bytes = "hello".toByteArray()
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns
                GatewayFileResult.Success(
                    GatewayFile(
                        "note.txt",
                        "text/plain",
                        java.io.File(cacheDir, "note.txt").apply { writeBytes(bytes) },
                    ),
                )

            val intentSlot = slot<Intent>()
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(any(), any(), any())
            } returns mockk(relaxed = true)
            every { app.getApplicationContext() } returns app
            every { app.applicationContext } returns app
            every { app.startActivity(capture(intentSlot)) } returns Unit

            val viewModel = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayPath = "/tmp/note.txt",
                    source = AttachmentSource.GATEWAY,
                )

            viewModel.openAttachment(attachment)
            advanceUntilIdle()

            coVerify { GatewayFileClient.fetch(any(), any()) }
            verify { app.startActivity(any()) }
            verify { intentSlot.captured.setDataAndType(any(), eq("text/plain")) }
            verify { intentSlot.captured.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            assertNull(viewModel.uiState.value.openError)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `openAttachment GATEWAY not-found surfaces openError`() =
        runTest {
            val cacheDir =
                java.io.File(
                    System.getProperty("java.io.tmpdir"),
                    "hermes_open_missing_test_${System.nanoTime()}",
                ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.getApplicationContext() } returns app
            every { app.applicationContext } returns app
            mockkObject(GatewayFileClient)
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.NotFound
            every {
                app.getString(R.string.attachment_error_not_found, "missing.pdf")
            } returns "File not found on gateway: missing.pdf"

            val viewModel = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "missing.pdf",
                    mimeType = "application/pdf",
                    gatewayPath = "/tmp/missing.pdf",
                    source = AttachmentSource.GATEWAY,
                )

            viewModel.openAttachment(attachment)
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.openError)
            assertTrue(viewModel.uiState.value.openError!!.contains("missing.pdf"))
            cacheDir.deleteRecursively()
        }

    // ── session.create liveness ──────────────────────────────────────────
    //
    // session.create is fire-and-forget: HermesWsClient.send() queues the frame
    // while the socket is down and drops it once credentials were cleared, and
    // neither path reports back. Chat has already discarded the old session by
    // then, so a lost create leaves it with no session at all — Send disabled,
    // nothing in flight — until the user backs out of the screen and returns.

    /** Retry delay injected so these tests opt into the liveness timer. */
    private val createRetryDelayMs = 1_000L

    /** Mirrors SESSION_CREATE_MAX_ATTEMPTS in the ViewModel. */
    private val createMaxAttempts = 3

    private fun createViewModelWithCreateRetry(): ChatViewModel =
        ChatViewModel(
            app,
            false,
            fakeRepo,
            fakeSlashUsageStore,
            testDispatcher,
            createRetryDelayMs,
        )

    @Test
    fun `session create is retried while the gateway never answers`() =
        runTest {
            val viewModel = createViewModelWithCreateRetry()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()

            // The first attempt went out and nothing acknowledged it.
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertFalse(viewModel.uiState.value.isSessionReady)

            advanceTimeBy(createRetryDelayMs + 1)
            runCurrent()

            verify(exactly = 2) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
        }

    @Test
    fun `session create surfaces an error once its attempts are spent`() =
        runTest {
            every {
                app.getString(R.string.chat_new_session_failed)
            } returns "Couldn't start a new chat."

            val viewModel = createViewModelWithCreateRetry()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            verify(exactly = createMaxAttempts) {
                HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any())
            }
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals("Couldn't start a new chat.", viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `an acknowledged session create stands its retry down`() =
        runTest {
            val viewModel = createViewModelWithCreateRetry()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            runCurrent()

            // req-id-3: loadSessions and fetchCommandCatalog precede the create.
            mockEventsFlow.emit(
                WsEvent.RpcResult("req-id-3", mapOf("session_id" to "session-123")),
            )
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSessionReady)
            assertEquals("session-123", viewModel.uiState.value.currentSessionId)
            verify(exactly = 1) { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) }
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `a superseded session create result cannot install itself`() =
        runTest {
            val (viewModel, _) = createViewModelWithSession()

            // Bind to the id the create actually used. A hard-coded position is
            // wrong here: handleRpcResult(SESSION_CREATE) issues its own
            // loadSessions, so the helper leaves the counter one ahead of what
            // the request sequence looks like from the outside — and a result
            // aimed at session.list is discarded by its own handler, passing
            // this test without ever reaching the generation fence.
            val createIds = mutableListOf<String>()
            every { HermesWsClient.send(WsMethods.SESSION_CREATE, any(), any()) } answers {
                reqCount++
                val id = "req-id-$reqCount"
                createIds += id
                arg<((String) -> Unit)?>(2)?.invoke(id)
                id
            }

            viewModel.createNewSession()
            advanceUntilIdle()
            val outstanding = createIds.single()

            // The user opens a different conversation before the answer lands.
            viewModel.switchSession("session-456")
            advanceUntilIdle()
            assertEquals("session-456", viewModel.uiState.value.currentSessionId)

            mockEventsFlow.emit(
                WsEvent.RpcResult(outstanding, mapOf("session_id" to "late-session")),
            )
            advanceUntilIdle()

            assertEquals("session-456", viewModel.uiState.value.currentSessionId)
        }
}
