package com.blindnav.app.domain.navigation

import com.blindnav.app.data.db.entity.EventType
import org.junit.Assert.*
import org.junit.Test

class RecordedRoutePlayerTest {
    @Test fun `short crossing stays active at its entrance and stops heading guidance at its marked end`() {
        val crossingEnd = GeoCoordinate(41.400045, 2.17) // Approximately five metres.
        val player = RecordedRoutePlayer(listOf(start, start, crossingEnd, corner), mapOf(
            1 to RecordedInstruction("Paso de cebra", targetBearing = 0f, isCrossing = true),
            2 to RecordedInstruction("Fin del paso de cebra grabado.", arrivalRadiusMeters = 1.6f, endsSection = true)))
        start(player)
        fix(player, start, 3000)
        fix(player, start, 4000)
        assertEquals(2, player.index)
        repeat(4) { fix(player, start, 5000L + it * 1000, heading = 30f, accuracy = 8f) }
        assertEquals(2, player.index)
        val correction = fix(player, start, 10000, heading = 30f, accuracy = 8f)
        assertTrue(correction.message!!.contains("izquierda"))
        fix(player, crossingEnd, 11000, heading = 0f)
        val reached = fix(player, crossingEnd, 12000, heading = 0f)
        assertEquals("Fin del paso de cebra grabado.", reached.message)
        assertEquals(3, player.index)
        assertNull(fix(player, crossingEnd, 13000, heading = 90f).headingError)
    }

    @Test fun `straight section provides compass feedback between its two endpoints`() {
        val player = RecordedRoutePlayer(listOf(start, start, corner, end), mapOf(
            1 to RecordedInstruction("Empieza recto", 0f),
            2 to RecordedInstruction("Fin del tramo recto grabado.", endsSection = true)))
        start(player)
        fix(player, start, 3000)
        fix(player, start, 4000)
        val middle = GeoCoordinate(41.40015, 2.17)
        assertTrue(fix(player, middle, 10000, heading = 330f).message!!.contains("derecha"))
        assertEquals(2, player.index)
        fix(player, corner, 11000)
        assertEquals("Fin del tramo recto grabado.", fix(player, corner, 12000).message)
    }

    private val start = GeoCoordinate(41.4, 2.17)
    private val corner = GeoCoordinate(41.4003, 2.17)
    private val end = GeoCoordinate(41.4003, 2.1704)

    private fun fix(player: RecordedRoutePlayer, position: GeoCoordinate, time: Long, heading: Float = 0f,
                    accuracy: Float = 5f) = player.update(position, heading, accuracy, time, time)

    private fun start(player: RecordedRoutePlayer) {
        fix(player, start, 1000)
        fix(player, start, 2000)
    }

    @Test fun `does not speak a turn when still approaching`() {
        val player = RecordedRoutePlayer(listOf(start, corner, end), mapOf(1 to RecordedInstruction("Gira a la derecha", 90f, true)))
        start(player)
        assertNull(fix(player, start, 3000).message)
        assertEquals(1, player.index)
    }

    @Test fun `same GPS fix cannot trigger a recorded instruction twice`() {
        val player = RecordedRoutePlayer(listOf(start, corner), emptyMap())
        fix(player, start, 1000)
        fix(player, start, 1000)
        assertEquals(0, player.index)
    }

    @Test fun `turn sentence is verbatim and completion requires sustained alignment`() {
        val sentence = "Ahora gira a la derecha, dejando el banco a tu izquierda."
        val player = RecordedRoutePlayer(listOf(start, corner, end), mapOf(1 to RecordedInstruction(sentence, 90f, true)))
        start(player)
        fix(player, corner, 3000)
        assertEquals(sentence, fix(player, corner, 4000).message)
        assertEquals(1, player.index)
        val correction = fix(player, corner, 10000, 20f)
        assertTrue(correction.message!!.contains("derecha unos 70"))
        fix(player, corner, 11000, 89f)
        assertEquals(1, player.index)
        fix(player, corner, 13000, 91f)
        assertEquals(2, player.index)
    }

    @Test fun `straight heading corrects left and wraps north`() {
        val player = RecordedRoutePlayer(listOf(start, corner, end), mapOf(1 to RecordedInstruction("Ahora recto", 350f)))
        start(player)
        fix(player, corner, 3000)
        fix(player, corner, 4000)
        val result = fix(player, corner, 10000, 20f)
        assertEquals(-30f, result.headingError!!, 0.01f)
        assertTrue(result.message!!.contains("izquierda"))
    }

    @Test fun `poor GPS pauses and does not consume instruction`() {
        val player = RecordedRoutePlayer(listOf(start, corner), emptyMap())
        val result = fix(player, start, 1000, accuracy = 30f)
        assertFalse(result.reliable)
        fix(player, start, 2000, accuracy = 30f)
        assertEquals(0, player.index)
        assertFalse(player.update(start, 0f, 5f, 1000, 20000).reliable)
        assertFalse(player.update(start, 0f, 5f, 20000, 20000, headingReliable = false).reliable)
    }

    @Test fun `two distinct notes at one point are both spoken in order`() {
        val player = RecordedRoutePlayer(listOf(start, corner, corner, end), mapOf(
            1 to RecordedInstruction("Atención al bordillo"), 2 to RecordedInstruction("Ahora paso de cebra", 90f)))
        start(player)
        fix(player, corner, 3000)
        assertEquals("Atención al bordillo", fix(player, corner, 4000).message)
        fix(player, corner, 5000)
        assertEquals("Ahora paso de cebra", fix(player, corner, 6000).message)
    }

    @Test fun `a later visit to the same corner cannot fire at the start of a loop`() {
        val geometry = listOf(start, corner, end, start)
        val length = RouteGeometry.totalDistanceMeters(geometry)
        val player = RecordedRoutePlayer(listOf(start, start, start),
            mapOf(1 to RecordedInstruction("Ya hemos vuelto", progressMeters = length)), geometry)
        start(player)
        repeat(20) { fix(player, start, 3000L + it * 2000) }
        assertEquals(1, player.index)
    }

    @Test fun `crossing text is not replaced with an instruction to cross now`() {
        val player = RecordedRoutePlayer(listOf(start, corner, end), mapOf(
            1 to RecordedInstruction("Paso de cebra. Espera y comprueba el tráfico.", 90f, isCrossing = true)))
        start(player)
        fix(player, corner, 3000)
        val result = fix(player, corner, 4000)
        assertEquals("Paso de cebra. Espera y comprueba el tráfico.", result.message)
        assertFalse(result.arrived)
    }

    @Test fun `classifier handles natural Spanish without changing the text`() {
        assertEquals(EventType.TURN, RecordedInstructionParser.type("Ahora giro a la derecha"))
        assertEquals(EventType.CROSSING, RecordedInstructionParser.type("Ahora paso de cebra"))
        assertEquals(EventType.INFO, RecordedInstructionParser.type("Ahora continúa recto"))
        assertTrue(RecordedInstructionParser.locksHeading("Ahora continúa recto", EventType.INFO))
        assertFalse(RecordedInstructionParser.locksHeading("Banco a la derecha", EventType.INFO))
        assertEquals(1, RecordedInstructionParser.turnSide("Gira a la derecha dejando el banco a la izquierda"))
        assertEquals(-1, RecordedInstructionParser.turnSide("Ahora giro a la izquierda"))
        assertNull(RecordedInstructionParser.turnSide("Banco a la derecha"))
    }

    @Test fun `next cue waits until the previous sentence has finished`() {
        val player = RecordedRoutePlayer(listOf(start, corner, end), mapOf(1 to RecordedInstruction("Mi frase completa")))
        start(player)
        player.update(corner, 0f, 5f, 3000, 3000, voiceBusy = true)
        val busy = player.update(corner, 0f, 5f, 4000, 4000, voiceBusy = true)
        assertNull(busy.message)
        assertEquals(1, player.index)
        assertEquals("Mi frase completa", fix(player, corner, 5000).message)
    }
}
