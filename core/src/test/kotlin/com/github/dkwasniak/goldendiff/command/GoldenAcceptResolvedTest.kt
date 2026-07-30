package com.github.dkwasniak.goldendiff.command

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GoldenAcceptResolvedTest {
    private fun tempProject(): File = Files.createTempDirectory("gd-resolved-accept").toFile()

    @Test
    fun `accepts the exact frozen source and removes it`() {
        val root = tempProject()
        val golden = File(root, "goldens/Login.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val generated = File(root, "output/Login_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2, 3)) }
        val frozen = GoldenAccept.freeze(golden, generated).getOrThrow()

        val outcome = GoldenAccept.acceptFrozen(frozen)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.Success)
        assertArrayEquals(byteArrayOf(2, 3), golden.readBytes())
        assertFalse(generated.exists())
    }

    @Test
    fun `same path is a typed nothing to accept and is not deleted`() {
        val root = tempProject()
        val golden = File(root, "goldens/Login.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val identity = GoldenAccept.resolvedSourceIdentity(golden).getOrThrow()

        val outcome = GoldenAccept.acceptResolved(golden, golden, identity)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.NothingToAccept)
        assertTrue(golden.exists())
        assertArrayEquals(byteArrayOf(1), golden.readBytes())
    }

    @Test
    fun `changed source is stale even when size and timestamp are preserved`() {
        val root = tempProject()
        val golden = File(root, "goldens/Login.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val generated = File(root, "output/Login_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2, 2)) }
        val frozen = GoldenAccept.freeze(golden, generated).getOrThrow()
        val timestamp = generated.lastModified()
        generated.writeBytes(byteArrayOf(9, 9))
        generated.setLastModified(timestamp)

        val outcome = GoldenAccept.acceptFrozen(frozen)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.StaleSource)
        assertArrayEquals(byteArrayOf(1), golden.readBytes())
        assertTrue(generated.exists())
    }

    @Test
    fun `missing or moved frozen source is stale before write`() {
        val root = tempProject()
        val golden = File(root, "goldens/Login.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val generated = File(root, "output/Login_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2)) }
        val frozen = GoldenAccept.freeze(golden, generated).getOrThrow()
        val moved = File(root, "output/Moved.png")
        assertTrue(generated.renameTo(moved))

        val outcome = GoldenAccept.acceptFrozen(frozen)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.StaleSource)
        assertArrayEquals(byteArrayOf(1), golden.readBytes())
        assertTrue(moved.exists())
    }

    @Test
    fun `write failure leaves exact source untouched`() {
        val root = tempProject()
        val golden = File(root, "goldens/Directory.png").apply { mkdirs() }
        val generated = File(root, "output/Directory_actual.png").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(7))
        }
        val frozen = GoldenAccept.freeze(golden, generated).getOrThrow()

        val outcome = GoldenAccept.acceptFrozen(frozen)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.Io)
        assertTrue(generated.exists())
        assertArrayEquals(byteArrayOf(7), generated.readBytes())
    }

    @Test
    fun `ambiguous source never chooses an arbitrary candidate`() {
        val root = tempProject()
        val golden = File(root, "goldens/Login.png")
        val first = File(root, "one/Login_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val second = File(root, "two/Login_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2)) }
        val candidate = GoldenAccept.FrozenCandidate(
            golden,
            GoldenAccept.FrozenSource.Ambiguous(listOf(first, second, first)),
        )

        val outcome = GoldenAccept.acceptFrozen(candidate)

        assertTrue(outcome is GoldenAccept.ResolvedOutcome.Ambiguous)
        assertEquals(listOf(first, second), (outcome as GoldenAccept.ResolvedOutcome.Ambiguous).candidates)
        assertFalse(golden.exists())
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun `bulk retains every outcome and exposes only unresolved retry subset`() {
        val root = tempProject()
        fun exact(name: String): GoldenAccept.FrozenCandidate {
            val golden = File(root, "goldens/$name.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
            val generated = File(root, "output/${name}_actual.png").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2)) }
            return GoldenAccept.freeze(golden, generated).getOrThrow()
        }
        val success = exact("Success")
        val stale = exact("Stale").also {
            (it.source as GoldenAccept.FrozenSource.Exact).generated.writeBytes(byteArrayOf(3, 3))
        }
        val missing = GoldenAccept.FrozenCandidate(
            File(root, "goldens/Missing.png"),
            GoldenAccept.FrozenSource.Missing,
        )
        val ambiguous = GoldenAccept.FrozenCandidate(
            File(root, "goldens/Ambiguous.png"),
            GoldenAccept.FrozenSource.Ambiguous(listOf(File(root, "one.png"), File(root, "two.png"))),
        )
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = GoldenAccept.acceptAllResolved(listOf(success, stale, missing, ambiguous)) { done, total ->
            progress += done to total
        }

        assertEquals(4, result.items.size)
        assertEquals(listOf(success), result.accepted)
        assertEquals(listOf(stale, ambiguous), result.retryCandidates)
        assertTrue(result.items[0].outcome is GoldenAccept.ResolvedOutcome.Success)
        assertTrue(result.items[1].outcome is GoldenAccept.ResolvedOutcome.StaleSource)
        assertTrue(result.items[2].outcome is GoldenAccept.ResolvedOutcome.NothingToAccept)
        assertTrue(result.items[3].outcome is GoldenAccept.ResolvedOutcome.Ambiguous)
        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
    }
}
