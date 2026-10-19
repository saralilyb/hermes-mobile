package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #549 — Layer 1 audit: the client-side slash dispatcher must route
 * exactly the commands it special-cases and forward everything else to the
 * backend via RpcDispatch.
 *
 * These are pure-logic assertions (no Android deps) covering every branch the
 * issue calls out:
 *   - /stop, /interrupt      -> Interrupt
 *   - /new                   -> NewSession
 *   - /NEW (case)           -> NewSession
 *   - "/foo bar" (args)     -> RpcDispatch
 *   - "/" only / empty       -> RpcDispatch
 */
class SlashCommandDispatcherTest {
    private val dispatcher = SlashCommandDispatcher()

    @Test
    fun `management verbs never become queued prompts`() {
        val standalone = listOf("list", "ls", "show", "clear")
        val indexed = listOf("edit", "set", "rm", "remove", "delete", "del", "pop", "move")
        (standalone + indexed + indexed.map { "$it 2" } + "move 1 3").forEach {
            org.junit.Assert.assertFalse(it, dispatcher.dispatch("/queue $it") is SlashResult.QueuePrompt)
        }
        assertEquals(SlashResult.QueuePrompt("list the files"), dispatcher.dispatch("/queue list the files"))
        assertEquals(SlashResult.QueuePrompt("remove dead code"), dispatcher.dispatch("/q remove dead code"))
    }

    @Test
    fun `stop routes to Interrupt`() {
        assertEquals(SlashResult.Interrupt, dispatcher.dispatch("/stop"))
    }

    @Test
    fun `interrupt routes to Interrupt`() {
        assertEquals(SlashResult.Interrupt, dispatcher.dispatch("/interrupt"))
    }

    @Test
    fun `new routes to NewSession`() {
        assertEquals(SlashResult.NewSession, dispatcher.dispatch("/new"))
    }

    @Test
    fun `fork routes to SessionBranch`() {
        // /fork (and /branch) fork the active conversation via session.branch —
        // the backend's command, not command.dispatch. See issue #533.
        assertEquals(SlashResult.SessionBranch, dispatcher.dispatch("/fork"))
        assertEquals(SlashResult.SessionBranch, dispatcher.dispatch("/branch"))
        assertEquals(SlashResult.SessionBranch, dispatcher.dispatch("/FORK hello"))
    }

    @Test
    fun `model routes to ModelSwitch`() {
        // /model is a real backend slash command (issue #589) that must travel as
        // a normal prompt message — NOT via command.dispatch (which 4018s on it,
        // because that RPC only knows quick/plugin/bundle/skill commands).
        assertEquals(SlashResult.ModelSwitch, dispatcher.dispatch("/model"))
        assertEquals(SlashResult.ModelSwitch, dispatcher.dispatch("/MODEL openai/gpt-4o --session"))
        assertEquals(SlashResult.ModelSwitch, dispatcher.dispatch("/Model"))
    }

    @Test
    fun `NEW uppercase still routes to NewSession`() {
        // Dispatcher lower-cases before matching, so case must not matter.
        assertEquals(SlashResult.NewSession, dispatcher.dispatch("/NEW"))
        assertEquals(SlashResult.NewSession, dispatcher.dispatch("/New"))
    }

    @Test
    fun `undo routes to a dedicated result without changing other commands`() {
        assertEquals(SlashResult.Undo(""), dispatcher.dispatch("/undo"))
        assertEquals(SlashResult.Undo("2"), dispatcher.dispatch("/UNDO 2"))
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/help"))
    }

    @Test
    fun `resume and history route to client history navigation`() {
        assertEquals(SlashResult.OpenHistory, dispatcher.dispatch("/resume"))
        assertEquals(SlashResult.OpenHistory, dispatcher.dispatch("/HISTORY"))
    }

    @Test
    fun `queue routes to QueuePrompt with stripped display content`() {
        assertEquals(
            SlashResult.QueuePrompt("do the thing"),
            dispatcher.dispatch("/queue do the thing"),
        )
        assertEquals(
            SlashResult.QueuePrompt("hi"),
            dispatcher.dispatch("/q hi"),
        )
        assertEquals(
            SlashResult.QueuePrompt("hi"),
            dispatcher.dispatch("/QUEUE hi"),
        )
    }

    @Test
    fun `bare queue keeps the raw command as display content`() {
        assertEquals(
            SlashResult.QueuePrompt("/queue"),
            dispatcher.dispatch("/queue"),
        )
        assertEquals(
            SlashResult.QueuePrompt("/q"),
            dispatcher.dispatch("/q"),
        )
    }

    @Test
    fun `command with args forwards to RpcDispatch`() {
        // Anything not /stop, /interrupt, /new goes to the backend.
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/foo bar"))
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/help"))
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/status"))
    }

    @Test
    fun `slash-only and empty forward to RpcDispatch`() {
        // "/" splits to [""], lower-cased "" is not a special case.
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/"))
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch(""))
    }

    @Test
    fun `unknown slash command forwards to RpcDispatch`() {
        assertEquals(SlashResult.RpcDispatch, dispatcher.dispatch("/definitely-not-real"))
    }
}
