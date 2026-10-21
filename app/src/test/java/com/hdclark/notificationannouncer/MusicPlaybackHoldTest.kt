package com.hdclark.notificationannouncer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlaybackHoldTest {
    private class FakeTransport : MusicPlaybackHold.Transport {
        data class State(var playing: Boolean, var preferred: Boolean = false)
        val players = linkedMapOf<String, State>()
        val actions = mutableListOf<String>()
        var discoveryAvailable = true

        override fun sessions(): List<MusicPlaybackHold.Session>? =
            if (!discoveryAvailable) null else players.map { (id, state) ->
                MusicPlaybackHold.Session(id, state.playing, state.preferred)
            }

        override fun pause(id: String) {
            actions.add("pause:$id")
            players[id]?.playing = false
        }

        override fun play(id: String) {
            actions.add("play:$id")
            players[id]?.playing = true
        }

        override fun pauseDefault() { actions.add("pause:default") }
        override fun playDefault() { actions.add("play:default") }
    }

    @Test fun musicIsRepauseWhileSpeakingAndTargetedAfterFocusChanges() {
        val transport = FakeTransport()
        transport.players["spotify"] = FakeTransport.State(playing = true, preferred = true)
        transport.players["podcast"] = FakeTransport.State(playing = false)
        val hold = MusicPlaybackHold(transport)

        // Capture and pause BEFORE taking transient focus.
        hold.begin(true)
        assertEquals(listOf("pause:spotify", "pause:spotify"), transport.actions)
        assertFalse(transport.players.getValue("spotify").playing)

        // TTS or another focus request makes the music app resume prematurely.
        transport.players.getValue("spotify").playing = true
        hold.enforce()
        assertFalse(transport.players.getValue("spotify").playing)

        // Next notification stays inside the same burst; don't overwrite snapshot.
        hold.begin(false)
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertTrue(transport.players.getValue("spotify").playing)
        assertFalse(transport.players.getValue("podcast").playing)
        assertEquals(1, transport.actions.count { it == "play:spotify" })
        assertFalse(hold.isOpen)
    }

    @Test fun noMusicAtStartNeverStartsAnUnrelatedPlayerAtEnd() {
        val transport = FakeTransport()
        transport.players["paused-podcast"] = FakeTransport.State(playing = false, preferred = true)
        val hold = MusicPlaybackHold(transport)
        hold.begin(false)
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertTrue(transport.actions.none { it.startsWith("play:") })
    }

    @Test fun musicTemporarilyPausedByNotificationStillRestoresOriginalSession() {
        val transport = FakeTransport()
        transport.players["spotify"] = FakeTransport.State(playing = false, preferred = true)
        transport.players["unrelated"] = FakeTransport.State(playing = false)
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertEquals(1, transport.actions.count { it == "play:spotify" })
        assertTrue(transport.actions.none { it == "play:unrelated" })
    }

    @Test fun newPlayerDuringSpeechIsPausedButNotAutoResumed() {
        val transport = FakeTransport()
        transport.players["original"] = FakeTransport.State(playing = true)
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        transport.players["new-player"] = FakeTransport.State(playing = true)
        hold.enforce()
        hold.finish(MusicResumeMode.AUTOMATIC, false)
        assertTrue(transport.actions.contains("pause:new-player"))
        assertTrue(transport.actions.none { it == "play:new-player" })
        assertTrue(transport.actions.none { it == "play:original" })
    }

    @Test fun neverResumeKeepsMusicPausedAfterFocusIsReleased() {
        val transport = FakeTransport()
        transport.players["spotify"] = FakeTransport.State(playing = true)
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        transport.players.getValue("spotify").playing = true // focus gain auto-resume
        hold.enforce()
        hold.finish(MusicResumeMode.NEVER, true)
        assertFalse(transport.players.getValue("spotify").playing)
        assertTrue(transport.actions.none { it.startsWith("play:") })
    }

    @Test fun deniedSessionDiscoveryUsesIdempotentPauseFallbackAndRestoresOnlyWhenOwned() {
        val transport = FakeTransport()
        transport.discoveryAvailable = false
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        hold.enforce()
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertEquals(listOf("pause:default", "pause:default", "play:default"), transport.actions)
        hold.begin(false)
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertEquals(1, transport.actions.count { it == "play:default" })
    }

    @Test fun multipleOriginalPlayersResumeButNeverBroadcastToNewPlayer() {
        val transport = FakeTransport()
        transport.players["music-1"] = FakeTransport.State(true)
        transport.players["music-2"] = FakeTransport.State(true)
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        hold.finish(MusicResumeMode.AUTOMATIC, true)
        assertTrue(transport.actions.contains("play:music-1"))
        assertTrue(transport.actions.contains("play:music-2"))
        assertFalse(transport.actions.contains("play:default"))
    }

    @Test fun holdCancelIsIdempotentAndDoesNotResurrectStaleTarget() {
        val transport = FakeTransport()
        transport.players["spotify"] = FakeTransport.State(true)
        val hold = MusicPlaybackHold(transport)
        hold.begin(true)
        hold.cancel(MusicResumeMode.NEVER, false)
        hold.cancel(MusicResumeMode.AUTOMATIC, true)
        assertTrue(transport.actions.none { it.startsWith("play:") })
    }
}
