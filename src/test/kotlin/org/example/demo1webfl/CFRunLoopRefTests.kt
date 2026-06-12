package org.example.demo1webfl

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.lang.foreign.MemorySegment

@EnabledOnOs(OS.MAC)
class CFRunLoopRefTests {

    @Test
    fun currentAndMainRunLoopPointersAreAvailable() {
        assertTrue(CFRunLoopRef.isSupported)

        val current = CFRunLoopRef.current()
        val main = CFRunLoopRef.main()

        assertNotEquals(MemorySegment.NULL, current.raw())
        assertNotEquals(MemorySegment.NULL, main.raw())
    }

    @Test
    fun runDefaultModeCanBeInvokedWithoutBlockingForever() {
        val result = CFRunLoopRef.runDefaultMode(0.0, true)

        assertTrue(
            result == CFRunLoopRef.RunResult.TimedOut ||
                result == CFRunLoopRef.RunResult.HandledSource ||
                result == CFRunLoopRef.RunResult.Stopped ||
                result == CFRunLoopRef.RunResult.Finished
        )
    }
}

