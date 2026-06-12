package org.example.sqlol

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.invoke.MethodHandle
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.Locale

/**
 * Thin CoreFoundation CFRunLoop reference backed by the Java Foreign Function & Memory API.
 */
class CFRunLoopRef private constructor(
    private val rawPointer: MemorySegment
) {

    init {
        require(rawPointer != MemorySegment.NULL) { "CFRunLoop pointer must not be NULL" }
    }

    fun raw(): MemorySegment = rawPointer

    fun stop() {
        invokeVoid(Native.stopHandle, rawPointer)
    }

    fun wakeUp() {
        invokeVoid(Native.wakeUpHandle, rawPointer)
    }

    private fun invokeVoid(handle: MethodHandle, pointer: MemorySegment) {
        try {
            handle.invoke(pointer)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to invoke CFRunLoop function", t)
        }
    }

    companion object {
        private const val CORE_FOUNDATION_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        private val os = System.getProperty("os.name", "unknown").lowercase(Locale.getDefault())
        val isSupported: Boolean = os.contains("mac")

        private object Native {
            val linker: Linker by lazy {
                ensureSupported()
                Linker.nativeLinker()
            }
            val lookupArena: Arena by lazy {
                ensureSupported()
                Arena.ofAuto()
            }
            val symbolLookup: SymbolLookup by lazy {
                ensureSupported()
                System.load(CORE_FOUNDATION_PATH)
                SymbolLookup.libraryLookup(CORE_FOUNDATION_PATH, lookupArena)
            }

            val getCurrentHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopGetCurrent", FunctionDescriptor.of(ADDRESS))
            }
            val getMainHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopGetMain", FunctionDescriptor.of(ADDRESS))
            }
            val runHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopRun", FunctionDescriptor.ofVoid())
            }
            val stopHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopStop", FunctionDescriptor.ofVoid(ADDRESS))
            }
            val wakeUpHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopWakeUp", FunctionDescriptor.ofVoid(ADDRESS))
            }
            val runInModeHandle: MethodHandle by lazy(LazyThreadSafetyMode.NONE) {
                downcall("CFRunLoopRunInMode", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_BYTE))
            }
            val defaultModeGlobal: MemorySegment by lazy(LazyThreadSafetyMode.NONE) {
                // Global symbols are exposed as address-only segments, so widen it before dereference.
                lookupSymbol("kCFRunLoopDefaultMode").reinterpret(ADDRESS.byteSize())
            }

            val commonModeGlobal: MemorySegment by lazy(LazyThreadSafetyMode.NONE) {
                lookupSymbol("kCFRunLoopCommonMode").reinterpret(ADDRESS.byteSize())
            }

            private fun ensureSupported() {
                if (!isSupported) {
                    throw UnsupportedOperationException("CFRunLoopRef is only supported on macOS")
                }
            }

            private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle {
                val symbol = lookupSymbol(name)
                return linker.downcallHandle(symbol, descriptor)
            }

            private fun lookupSymbol(name: String): MemorySegment {
                return symbolLookup.find(name)
                    .orElseThrow { UnsatisfiedLinkError("CoreFoundation symbol not found: $name") }
            }
        }

        private fun requireSupported() {
            if (!isSupported) {
                throw UnsupportedOperationException("CFRunLoopRef is only supported on macOS")
            }
        }

        fun current(): CFRunLoopRef {
            requireSupported()
            return CFRunLoopRef(invokeAddress(Native.getCurrentHandle))
        }

        fun main(): CFRunLoopRef {
            requireSupported()
            return CFRunLoopRef(invokeAddress(Native.getMainHandle))
        }

        fun run() {
            requireSupported()
            try {
                Native.runHandle.invoke()
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to invoke CFRunLoopRun", t)
            }
        }

        fun runDefaultMode(seconds: Double, returnAfterSourceHandled: Boolean): RunResult {
            requireSupported()
            val mode = Native.defaultModeGlobal.get(ADDRESS, 0L)
            val handledByte: Byte = if (returnAfterSourceHandled) 1 else 0
            val code = try {
                Native.runInModeHandle.invokeExact(mode, seconds, handledByte) as Int
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to invoke CFRunLoopRunInMode", t)
            }
            return RunResult.from(code)
        }

        private fun invokeAddress(handle: MethodHandle): MemorySegment {
            val pointer = try {
                handle.invokeExact() as MemorySegment
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to invoke CFRunLoop function", t)
            }
            require(pointer != MemorySegment.NULL) { "Native CFRunLoop function returned NULL" }
            return pointer
        }
    }

    enum class RunResult(val nativeCode: Int) {
        Finished(1),
        Stopped(2),
        TimedOut(3),
        HandledSource(4),
        Unknown(Int.MIN_VALUE);

        companion object {
            fun from(code: Int): RunResult {
                return entries.firstOrNull { it.nativeCode == code } ?: Unknown
            }
        }
    }

}