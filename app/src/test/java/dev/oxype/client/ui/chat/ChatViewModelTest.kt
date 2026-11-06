package dev.oxype.client.ui.chat

import dev.oxype.client.data.AuthRestoreState
import dev.oxype.client.data.oxype.AuthCredentials
import dev.oxype.client.data.oxype.HttpResponse
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.testsupport.FakeSessionStorage
import dev.oxype.client.testsupport.ScriptedTransport
import dev.oxype.client.testsupport.repositoryWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun signedInStorage() = FakeSessionStorage().apply {
        themeMode = ThemeMode.SYSTEM
        saveAuth(AuthCredentials(1L, "tok"), "alice")
    }

    /** Session 9 has one message; session 10 fails to load. */
    private fun transport() = ScriptedTransport { _, route ->
        when {
            route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
            route == "/lastMessage/9" ->
                HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

            route == "/getMessages/9" -> HttpResponse(
                200,
                """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
            )

            route == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")
            route == "/lastMessage/10" ->
                HttpResponse(200, """{"data":{"hasMessage":false}}""")

            route == "/getMessages/10" -> HttpResponse(500, """{"error_message":"boom"}""")
            else -> null
        }
    }

    @Test
    fun `opening a chat clears the spinner and keeps a retryable error`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "1 位成员")

        assertFalse("spinner must stop", viewModel.state.value.isLoadingInitial)
        assertEquals(1, viewModel.state.value.messages.size)
        assertEquals(9L, viewModel.state.value.sessionId)
    }

    @Test
    fun `a failed initial load stops the spinner and reports the error`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(10, "标题", "1 位成员")

        assertFalse(viewModel.state.value.isLoadingInitial)
        assertNotNull("error must be recoverable and visible", viewModel.state.value.error)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun `switching sessions clears the previous transcript`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "旧会话", "1 位成员")
        assertEquals(1, viewModel.state.value.messages.size)

        viewModel.open(10, "新会话", "1 位成员")

        assertEquals(10L, viewModel.state.value.sessionId)
        assertTrue("old messages must not leak", viewModel.state.value.messages.isEmpty())
        assertFalse(viewModel.state.value.isLoadingInitial)
    }

    @Test
    fun `sending without a session resets the busy flag`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(10, "标题", "")
        viewModel.setDraft("hello")
        viewModel.send()

        assertFalse("a refused send must not leave the button spinning", viewModel.state.value.isSending)
        assertEquals("hello", viewModel.state.value.draft)
    }

    @Test
    fun `sending while signed out resets the busy flag`() = runTest {
        // No credentials were ever restored: repository.sendText throws early.
        val repository = repositoryWith(FakeSessionStorage(), transport(), backgroundScope)
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "")
        viewModel.setDraft("hello")

        viewModel.send()

        assertFalse(viewModel.state.value.isSending)
        assertEquals("hello", viewModel.state.value.draft)
    }

    @Test
    fun `backgrounding stops polling and foregrounding resumes it`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "1 位成员")
        assertTrue("an open chat polls while visible", repository.isPolling)

        viewModel.onBackground()
        assertFalse("leaving the foreground must stop network polling", repository.isPolling)

        viewModel.onForeground()
        assertTrue("returning to the foreground resumes polling", repository.isPolling)
    }

    @Test
    fun `reopening the same chat after a rotation does not reload or duplicate pollers`() = runTest {
        val transport = transport()
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "1 位成员")
        val loadsAfterFirstOpen = transport.requests.count { it.url.contains("/getMessages/9") }
        val messages = viewModel.state.value.messages

        // The screen re-binds after a configuration change.
        viewModel.open(9, "标题", "1 位成员")

        assertEquals(
            "same session must not be refetched",
            loadsAfterFirstOpen,
            transport.requests.count { it.url.contains("/getMessages/9") }
        )
        assertEquals(messages, viewModel.state.value.messages)
        assertTrue(repository.isPolling)
    }

    @Test
    fun `rebinding a chat whose first load failed does not start polling`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(10, "标题", "1 位成员")
        assertNotNull("the failed load must stay visible", viewModel.state.value.error)
        assertFalse(repository.isPolling)

        // A configuration change re-binds the same screen: it must not pretend the
        // failed load succeeded and start a poller with no history window.
        viewModel.open(10, "标题", "1 位成员")

        assertFalse(
            "polling without a history window can only come back empty",
            repository.isPolling
        )
        assertNotNull("the retryable error must survive the rebuild", viewModel.state.value.error)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertFalse(viewModel.state.value.isLoadingInitial)
    }

    @Test
    fun `refreshing after a failed first load rebinds and recovers`() = runTest {
        var failing = true
        val transport = ScriptedTransport { _, route ->
            when {
                route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                route == "/getMessages/9" -> if (failing) {
                    HttpResponse(500, """{"error_message":"boom"}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"back"}]}]}"""
                    )
                }

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "1 位成员")
        viewModel.open(9, "标题", "1 位成员")
        assertFalse("a failed open must not be polling", repository.isPolling)

        failing = false
        viewModel.refresh()
        testScheduler.runCurrent()

        assertEquals(listOf(1L), viewModel.state.value.messages.map { it.id })
        assertNull(viewModel.state.value.error)
        assertTrue(repository.isPolling)
    }

    @Test
    fun `closing the chat stops polling`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "")
        assertTrue(repository.isPolling)

        viewModel.close()

        assertFalse(repository.isPolling)
    }

    @Test
    fun `signing out invalidates the open chat state`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")
        assertEquals(1, viewModel.state.value.messages.size)

        repository.logout()

        assertEquals("session id must be invalidated", 0L, viewModel.state.value.sessionId)
        assertTrue("transcript must be dropped", viewModel.state.value.messages.isEmpty())
        assertNull("the old chat snapshot must not survive", repository.chat.value)
        assertFalse("polling must stop", repository.isPolling)
    }

    @Test
    fun `reopening the same chat after signing out loads it again`() = runTest {
        val transport = transport()
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")
        val loadsAfterFirstOpen = transport.requests.count { it.url.contains("/getMessages/9") }

        // The token expires (or the user signs out) and the app returns to auth.
        repository.logout()
        // A fresh login by the same account, then the same conversation is opened.
        repository.login(1L, "secret1")
        viewModel.open(9, "标题", "1 位成员")

        assertTrue(
            "the same id must be fetched again after a sign-out, not reused",
            transport.requests.count { it.url.contains("/getMessages/9") } > loadsAfterFirstOpen
        )
        assertEquals(9L, viewModel.state.value.sessionId)
        assertEquals(1, viewModel.state.value.messages.size)
    }

    @Test
    fun `switching accounts invalidates the chat bound to the previous account`() = runTest {
        val transport = transport()
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")

        // A different account signs in without an intermediate sign-out.
        repository.login(2L, "secret2")

        assertEquals(0L, viewModel.state.value.sessionId)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertNull(repository.chat.value)
    }

    @Test
    fun `a manual refresh that hits a rejected token signs out`() = runTest {
        val storage = signedInStorage()
        var reject = false
        val transport = ScriptedTransport { _, route ->
            when {
                route == "/lastMessage/9" ->
                    HttpResponse(
                        200,
                        if (reject) {
                            """{"data":{"hasMessage":true,"id":7}}"""
                        } else {
                            """{"data":{"hasMessage":true,"id":1}}"""
                        }
                    )

                route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")

                route == "/getMessages/9" -> if (reject) {
                    HttpResponse(401, """{"error_message":"expired"}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
                    )
                }

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")
        assertEquals(9L, viewModel.state.value.sessionId)

        reject = true
        // The next poll's history request is rejected: the manual refresh must route the
        // rejection through the same credential signal the list uses.
        viewModel.refresh()
        testScheduler.runCurrent()

        assertNull("a rejected token must drop the login", repository.credentials.value)
        assertTrue("the app must know the login expired", repository.sessionExpired.value)
    }

    @Test
    fun `opening a chat waits for the stored token check to finish`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val aliveGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> {
                    aliveGate.await()
                    HttpResponse(200, """{"data":{"is_alive":true}}""")
                }

                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val restore = backgroundScope.launch { repository.restoreSession() }
        testScheduler.runCurrent()

        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")
        testScheduler.runCurrent()

        // The token is still being verified: no history request, and certainly no
        // premature "请先登录" that would stick on a restored chat screen.
        assertEquals(0, transport.requests.count { it.url.contains("/getMessages/9") })
        assertNull(viewModel.state.value.error)

        aliveGate.complete(Unit)
        restore.join()
        testScheduler.runCurrent()

        assertEquals(1, viewModel.state.value.messages.size)
        assertNull("a verified login must not report a login error", viewModel.state.value.error)
    }

    @Test
    fun `opening a chat after a failed restore does not request history`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "dead"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":false}}""")
                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        assertFalse(repository.restoreSession())

        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "")
        testScheduler.runCurrent()

        // Navigation moves to Auth; the chat must not have gone looking for messages.
        assertEquals(0, transport.requests.count { it.url.contains("/getMessages/9") })
        assertFalse(viewModel.state.value.isLoadingInitial)
        assertEquals("the dead binding must be dropped", 0L, viewModel.state.value.sessionId)
    }

    @Test
    fun `a chat opened before the restore is loaded once the login resolves`() = runTest {
        val storage = FakeSessionStorage().apply {
            saveAuth(AuthCredentials(1L, "tok"), "alice")
        }
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/isAlive/1" -> HttpResponse(200, """{"data":{"is_alive":true}}""")
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        val viewModel = ChatViewModel(repository)

        // Composed before restoreSession ran (process death straight into the chat).
        viewModel.open(9, "标题", "1 位成员")
        testScheduler.runCurrent()
        assertTrue("the screen must keep waiting, not report a login error", viewModel.state.value.isLoadingInitial)

        assertTrue(repository.restoreSession())
        testScheduler.runCurrent()

        assertEquals(9L, viewModel.state.value.sessionId)
        assertEquals(1, viewModel.state.value.messages.size)
        assertNull(viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoadingInitial)
    }

    @Test
    fun `refreshing after a failed initial load retries the first page`() = runTest {
        var failing = true
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> if (failing) {
                    HttpResponse(500, """{"error_message":"boom"}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":1,
                            "pieces":[{"type":"text","text":"back"}]}]}"""
                    )
                }

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "标题", "1 位成员")
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertNotNull(viewModel.state.value.error)

        failing = false
        viewModel.refresh()
        testScheduler.runCurrent()

        assertEquals(
            "the toolbar refresh must be able to recover a failed first load",
            listOf(1L),
            viewModel.state.value.messages.map { it.id }
        )
        assertNull(viewModel.state.value.error)
        assertTrue(
            "recovering the first load must also restart the foreground poller",
            repository.isPolling
        )
    }

    @Test
    fun `switching sessions drops the unsent draft`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "旧会话", "1 位成员")
        viewModel.setDraft("写给 A 的话")

        viewModel.open(10, "新会话", "1 位成员")

        assertEquals(
            "text typed for A must not be sent to B",
            "",
            viewModel.state.value.draft
        )
    }

    @Test
    fun `closing the chat drops the unsent draft`() = runTest {
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "1 位成员")
        viewModel.setDraft("未发送")

        viewModel.close()

        assertEquals("", viewModel.state.value.draft)
    }

    @Test
    fun `a send that fails after a session switch does not touch the new chat`() = runTest {
        val sendStarted = CompletableDeferred<Unit>()
        val sendGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { _, route ->
            when {
                route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                route == "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":1,"pieces":[{"type":"text","text":"old"}]}]}"""
                )

                route == "/sendMessage/9" -> {
                    sendStarted.complete(Unit)
                    sendGate.await()
                    // Fails after the user has already moved to another conversation.
                    HttpResponse(500, """{"error_message":"boom"}""")
                }

                route == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/10" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":500}}""")

                route == "/getMessages/10" -> HttpResponse(
                    200,
                    """{"data":[{"id":500,"sender":2,"pieces":[{"type":"text","text":"b"}]}]}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "A", "1 位成员")
        viewModel.setDraft("hi")

        viewModel.send()
        sendStarted.await()
        viewModel.open(10, "B", "1 位成员")
        sendGate.complete(Unit)
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals(10L, state.sessionId)
        assertNull("A's failure must not surface in B", state.error)
        assertFalse("A's send must not leave B's composer busy", state.isSending)
        assertEquals("A's draft must not be restored into B", "", state.draft)
        assertNull("A's failure must not reach B's snapshot", repository.chat.value!!.error)
        assertFalse(repository.chat.value!!.isSending)
    }

    @Test
    fun `the chat state carries the member directory and group flag`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":2}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[
                        {"id":1,"sender":2,"pieces":[{"type":"text","text":"a"}]},
                        {"id":2,"sender":3,"pieces":[{"type":"text","text":"b"}]}
                    ]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(
                    200,
                    """{"data":{"users":[
                        {"userid":2,"username":"bob"},
                        {"userid":3,"username":"carol","avatarUrl":"https://example.test/c.png"}
                    ]}}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "群聊", "3 位成员")
        testScheduler.runCurrent()

        val state = viewModel.state.value
        assertEquals("carol", state.participants[3L]?.username)
        assertEquals("https://example.test/c.png", state.participants[3L]?.avatarUrl)
        assertTrue("two other senders make it a group", state.isGroup)
    }

    @Test
    fun `switching chats clears the previous member directory`() = runTest {
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":1}}""")

                "/getMessages/9" -> HttpResponse(
                    200,
                    """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"a"}]}]}"""
                )

                "/getSessionUsers/9" -> HttpResponse(
                    200,
                    """{"data":{"users":[{"userid":2,"username":"bob"}]}}"""
                )

                "/lastMessage/10" -> HttpResponse(200, """{"data":{"hasMessage":false}}""")
                "/getMessages/10" -> HttpResponse(200, """{"data":[]}""")
                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "A", "")
        testScheduler.runCurrent()
        assertEquals(setOf(2L), viewModel.state.value.participants.keys)

        viewModel.open(10, "B", "")
        testScheduler.runCurrent()

        assertTrue(
            "A's directory must not label B's transcript",
            viewModel.state.value.participants.isEmpty()
        )
    }

    @Test
    fun `a session the account may not read shows an error and does not poll`() = runTest {
        // Real Oxype answer for a non-member: `lastMessage` is 403 while
        // `receivedMessageSeq` soft-returns 0 and `getMessages` would answer empty.
        // The screen must say so instead of showing a quiet empty transcript.
        val storage = signedInStorage()
        var messageRequests = 0
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                "/lastMessage/9" -> HttpResponse(403, """{"error_message":"forbidden"}""")
                "/getMessages/9" -> {
                    messageRequests++
                    HttpResponse(200, """{"data":[]}""")
                }

                else -> null
            }
        }
        val repository = repositoryWith(storage, transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "无权限", "0 位成员")
        testScheduler.runCurrent()

        assertNotNull("a denied session must show a visible error", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoadingInitial)
        assertTrue(viewModel.state.value.messages.isEmpty())
        assertFalse("a denied session must not start a poller", repository.isPolling)
        assertEquals(
            "a 403 must not degrade into speculative history requests",
            0,
            messageRequests
        )
        assertNotNull("a per-session denial must keep the global login", repository.credentials.value)
        assertFalse(repository.sessionExpired.value)

        // A manual refresh retries the open rather than polling a denied conversation.
        viewModel.refresh()
        testScheduler.runCurrent()
        assertNotNull(viewModel.state.value.error)
        assertFalse(repository.isPolling)
    }

    @Test
    fun `an older page that fails after a session switch does not show its error`() = runTest {
        val olderStarted = CompletableDeferred<Unit>()
        val olderGate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport { request, route ->
            when {
                route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":130}}""")

                route == "/getMessages/9" -> {
                    val start = org.json.JSONObject(request.body!!).getLong("start").coerceAtLeast(1L)
                    if (start < 100L) {
                        olderStarted.complete(Unit)
                        olderGate.await()
                        HttpResponse(500, """{"error_message":"boom"}""")
                    } else {
                        val end = org.json.JSONObject(request.body).getLong("end")
                        val items = (start..end).joinToString(",") {
                            """{"id":$it,"sender":1,"pieces":[{"type":"text","text":"a$it"}]}"""
                        }
                        HttpResponse(200, """{"data":[$items]}""")
                    }
                }

                route == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/10" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":500}}""")

                route == "/getMessages/10" -> HttpResponse(
                    200,
                    """{"data":[{"id":500,"sender":2,"pieces":[{"type":"text","text":"b"}]}]}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "A", "1 位成员")

        viewModel.loadOlder()
        olderStarted.await()
        viewModel.open(10, "B", "1 位成员")
        olderGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(10L, viewModel.state.value.sessionId)
        assertNull("A's paging failure must not surface in B", viewModel.state.value.error)
        assertFalse(viewModel.state.value.isLoadingOlder)
        assertNull(repository.chat.value!!.error)
    }

    @Test
    fun `a refresh that fails after a session switch does not show its error`() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        var failFetch = false
        var serverNewest = 1L
        val transport = ScriptedTransport { _, route ->
            when {
                route == "/receivedMessageSeq/1/9" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                route == "/getMessages/9" -> {
                    if (failFetch) {
                        refreshStarted.complete(Unit)
                        refreshGate.await()
                        HttpResponse(500, """{"error_message":"boom"}""")
                    } else {
                        HttpResponse(
                            200,
                            """{"data":[{"id":1,"sender":1,
                                "pieces":[{"type":"text","text":"old"}]}]}"""
                        )
                    }
                }

                route == "/receivedMessageSeq/1/10" -> HttpResponse(200, """{"data":{"seq":0}}""")
                route == "/lastMessage/10" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":500}}""")

                route == "/getMessages/10" -> HttpResponse(
                    200,
                    """{"data":[{"id":500,"sender":2,"pieces":[{"type":"text","text":"b"}]}]}"""
                )

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)
        viewModel.open(9, "A", "1 位成员")
        viewModel.onBackground()

        // Something new arrived, and re-reading it fails after the switch.
        serverNewest = 5L
        failFetch = true
        viewModel.refresh()
        refreshStarted.await()
        viewModel.open(10, "B", "1 位成员")
        refreshGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(10L, viewModel.state.value.sessionId)
        assertNull("A's refresh failure must not surface in B", viewModel.state.value.error)
        assertNull(repository.chat.value!!.error)
    }

    @Test
    fun `a dismissed error is not resurrected by the next snapshot`() = runTest {
        // The screen renders the repository snapshot, so clearing only the ViewModel's
        // copy would be undone as soon as the repository published anything else — here
        // the member directory finishing its load.
        val membersStarted = CompletableDeferred<Unit>()
        val membersGate = CompletableDeferred<Unit>()
        var serverNewest = 1L
        var failPoll = false
        val transport = ScriptedTransport { _, route ->
            when (route) {
                "/lastMessage/9" ->
                    HttpResponse(200, """{"data":{"hasMessage":true,"id":$serverNewest}}""")

                "/getMessages/9" -> if (failPoll) {
                    HttpResponse(500, """{"error_message":"boom"}""")
                } else {
                    HttpResponse(
                        200,
                        """{"data":[{"id":1,"sender":2,"pieces":[{"type":"text","text":"a"}]}]}"""
                    )
                }

                "/getSessionUsers/9" -> {
                    membersStarted.complete(Unit)
                    membersGate.await()
                    HttpResponse(200, """{"data":{"users":[{"userid":2,"username":"bob"}]}}""")
                }

                else -> null
            }
        }
        val repository = repositoryWith(signedInStorage(), transport, backgroundScope)
        repository.login(1L, "secret1")
        val viewModel = ChatViewModel(repository)

        viewModel.open(9, "标题", "2 位成员")
        testScheduler.runCurrent()
        membersStarted.await()
        assertEquals(1, viewModel.state.value.messages.size)

        // A poll fails: the error appears in both the snapshot and the screen.
        serverNewest = 2L
        failPoll = true
        viewModel.refresh()
        testScheduler.runCurrent()
        assertNotNull(viewModel.state.value.error)
        assertNotNull(repository.chat.value!!.error)

        // The user closes the strip.
        viewModel.consumeError()
        assertNull(viewModel.state.value.error)
        assertNull("the repository's copy must be cleared too", repository.chat.value!!.error)

        // The member directory now answers. That publish copies the snapshot, which is
        // where a stale error would come back from.
        failPoll = false
        membersGate.complete(Unit)
        testScheduler.runCurrent()

        assertNull("a closed error must stay closed", viewModel.state.value.error)
        assertNull(repository.chat.value!!.error)
        assertEquals("bob", viewModel.state.value.participants[2L]?.username)
    }

    @Test
    fun `a stale dismissal does not clear a newly opened conversation's error`() = runTest {
        // A's dismissal can arrive after B was opened (the strip of A is still on screen
        // for a frame). It must be pinned to the conversation instance it was made for.
        val repository = repositoryWith(signedInStorage(), transport(), backgroundScope)
        repository.login(1L, "secret1")
        // Session 10 fails to load, which is the error this test dismisses.
        runCatching { repository.openChat(10) }
        val staleVersion = repository.chatVersion
        assertNotNull(repository.chat.value!!.error)

        // The conversation is opened again — same id, but a new instance with its own
        // error.
        runCatching { repository.openChat(10) }
        testScheduler.runCurrent()
        assertNotNull(repository.chat.value!!.error)

        val cleared = repository.dismissChatError(10, staleVersion)

        assertFalse("a dismissal from the previous instance must be ignored", cleared)
        assertNotNull("the new conversation's error must survive", repository.chat.value!!.error)
        assertTrue(
            "the current instance can still dismiss its own error",
            repository.dismissChatError(10, repository.chatVersion)
        )
        assertNull(repository.chat.value!!.error)
    }
}
