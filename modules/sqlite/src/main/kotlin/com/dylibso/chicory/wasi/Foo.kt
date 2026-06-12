package com.dylibso.chicory.wasi

//import com.dylibso.chicory.annotations.Buffer
//import com.dylibso.chicory.annotations.HostModule
//import com.dylibso.chicory.annotations.WasmExport
import com.dylibso.chicory.log.BasicLogger
import com.dylibso.chicory.log.Logger
import com.dylibso.chicory.log.SystemLogger
import com.dylibso.chicory.runtime.ExecutionCompletedException
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.WasmRuntimeException
import com.dylibso.chicory.wasi.Descriptors.*
import com.dylibso.chicory.wasm.ChicoryException
import java.io.Closeable
import java.io.IOException
import java.lang.Long
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.util.*
import java.util.Map
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.Byte
import kotlin.ByteArray
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.Number
import kotlin.RuntimeException
import kotlin.String
import kotlin.UnsupportedOperationException
import kotlin.arrayOf
import kotlin.arrayOfNulls
import kotlin.code
import kotlin.collections.ArrayList
import kotlin.collections.HashSet
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.minus
import kotlin.collections.mutableListOf
import kotlin.compareTo
import kotlin.math.max
import kotlin.math.min
import kotlin.require
import kotlin.sequences.minus
import kotlin.use
//
////class Foo {
////
////
////
////    fun asdf() {
////        Descriptors()
////        WasiPreview1::toHostFunctions
////        WasiErrno::entries
////    }
////}
////package com.dylibso.chicory.wasi
//
////import com.dylibso.chicory.annotations.Buffer
////import com.dylibso.chicory.annotations.HostModule
////import com.dylibso.chicory.annotations.WasmExport
//
///**
// * [WASI preview 1](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md) implementation
// */
////@HostModule("wasi_snapshot_preview1")
//class WasiPreview1 private constructor(logger: Logger, opts: WasiOptions) : Closeable {
//    private val logger: Logger
//    private val random: Random
//    private val clock: Clock
//    private val arguments: MutableList<ByteArray>
//    private val environment: MutableList<MutableMap.MutableEntry<ByteArray?, ByteArray?>>
//    private val descriptors = Descriptors()
//    private val throwOnExit0: Boolean
//
//    init {
//        // TODO by default everything should by blocked
//        // this works now because streams are null.
//        // maybe we want a more explicit way of doing this though
//        this.logger = Objects.requireNonNull<Logger?>(logger)
//        this.random = opts.random()
//        this.clock = opts.clock()
//        this.arguments =
//            opts.arguments().stream().map<ByteArray> { value: String? -> value!!.toByteArray(StandardCharsets.UTF_8) }
//                .collect(
//                    Collectors.toList()
//                )
//        this.environment =
//            opts.environment().entries.stream()
//                .map<@org.jetbrains.annotations.Unmodifiable MutableMap.MutableEntry<ByteArray?, ByteArray?>?> { x: MutableMap.MutableEntry<String?, String?>? ->
//                    Map.entry<ByteArray, ByteArray>(
//                        x!!.key!!.toByteArray(StandardCharsets.UTF_8),
//                        x.value!!.toByteArray(StandardCharsets.UTF_8)
//                    )
//                }
//                .collect(Collectors.toList())
//        this.throwOnExit0 = opts.throwOnExit0()
//
//        descriptors.allocate(InStream(opts.stdin(), opts.stdinIsTty()))
//        descriptors.allocate(OutStream(opts.stdout(), opts.stdoutIsTty()))
//        descriptors.allocate(OutStream(opts.stderr(), opts.stderrIsTty()))
//
//        for (entry in opts.directories().entries) {
//            val name = entry.key.toByteArray(StandardCharsets.UTF_8)
//            descriptors.allocate(PreopenedDirectory(name, entry.value))
//        }
//    }
//
//    class Builder private constructor() {
//        private var logger: Logger? = null
//        private var opts: WasiOptions? = null
//
//        fun withLogger(logger: Logger?): Builder {
//            this.logger = logger
//            return this
//        }
//
//        fun withOptions(opts: WasiOptions?): Builder {
//            this.opts = opts
//            return this
//        }
//
//        fun build(): WasiPreview1 {
//            if (logger == null && isAndroid) {
//                logger = BasicLogger()
//            } else if (logger == null) {
//                logger = SystemLogger()
//            }
//            if (opts == null) {
//                opts = WasiOptions.builder().build()
//            }
//            return WasiPreview1(logger!!, opts!!)
//        }
//
//        companion object {
//            private val isAndroid: Boolean
//                get() {
//                    try {
//                        Class.forName("android.os.Build")
//                        return true
//                    } catch (e: ClassNotFoundException) {
//                        // Fallback: check known system property
//                        val runtime = System.getProperty("java.runtime.name")
//                        return runtime != null && runtime.lowercase().contains("android")
//                    }
//                }
//        }
//    }
//
//    override fun close() {
//        descriptors.closeAll()
//    }
//
//    @WasmExport
//    fun adapterCloseBadfd(fd: Int): Int {
//        logger.tracef("adapter_close_badfd: [%s]", fd)
//        throw WasmRuntimeException("We don't yet support this WASI call: adapter_close_badfd")
//    }
//
//    @WasmExport
//    fun adapterOpenBadfd(fd: Int): Int {
//        logger.tracef("adapter_open_badfd: [%s]", fd)
//        throw WasmRuntimeException("We don't yet support this WASI call: adapter_open_badfd")
//    }
//
//    @WasmExport
//    fun argsGet(memory: Memory, argv: Int, argvBuf: Int): Int {
//        var argv = argv
//        var argvBuf = argvBuf
//        logger.tracef("args_get: [%s, %s]", argv, argvBuf)
//        for (argument in arguments) {
//            memory.writeI32(argv, argvBuf)
//            argv += 4
//            memory.write(argvBuf, argument)
//            argvBuf += argument.size
//            memory.writeByte(argvBuf, 0.toByte())
//            argvBuf++
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun argsSizesGet(memory: Memory, argc: Int, argvBufSize: Int): Int {
//        logger.tracef("args_sizes_get: [%s, %s]", argc, argvBufSize)
//        val bufSize = arguments.stream().mapToInt { x: ByteArray -> x.size + 1 }.sum()
//        memory.writeI32(argc, arguments.size)
//        memory.writeI32(argvBufSize, bufSize)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun clockResGet(memory: Memory, clockId: Int, resultPtr: Int): Int {
//        logger.tracef("clock_res_get: [%s, %s]", clockId, resultPtr)
//        when (clockId) {
//            WasiClockId.REALTIME, WasiClockId.MONOTONIC -> {
//                memory.writeLong(resultPtr, 1L)
//                return wasiResult(WasiErrno.ESUCCESS)
//            }
//
//            WasiClockId.PROCESS_CPUTIME_ID, WasiClockId.THREAD_CPUTIME_ID -> return wasiResult(WasiErrno.ENOTSUP)
//            else -> return wasiResult(WasiErrno.EINVAL)
//        }
//    }
//
//    @WasmExport
//    fun clockTimeGet(memory: Memory, clockId: Int, precision: Long, resultPtr: Int): Int {
//        logger.tracef("clock_time_get: [%s, %s, %s]", clockId, precision, resultPtr)
//        when (clockId) {
//            WasiClockId.REALTIME, WasiClockId.MONOTONIC -> {
//                memory.writeLong(resultPtr, clockTime(clockId))
//                return wasiResult(WasiErrno.ESUCCESS)
//            }
//
//            WasiClockId.PROCESS_CPUTIME_ID, WasiClockId.THREAD_CPUTIME_ID -> return wasiResult(WasiErrno.ENOTSUP)
//            else -> return wasiResult(WasiErrno.EINVAL)
//        }
//    }
//
//    @WasmExport
//    fun environGet(memory: Memory, environ: Int, environBuf: Int): Int {
//        var environ = environ
//        var environBuf = environBuf
//        logger.tracef("environ_get: [%s, %s]", environ, environBuf)
//        for (entry in environment) {
//            val name: ByteArray = entry.key!!
//            val value: ByteArray = entry.value!!
//            val data = ByteArray(name.size + value.size + 2)
//            System.arraycopy(name, 0, data, 0, name.size)
//            data[name.size] = '='.code.toByte()
//            System.arraycopy(value, 0, data, name.size + 1, value.size)
//            data[data.size - 1] = '\u0000'.code.toByte()
//
//            memory.writeI32(environ, environBuf)
//            environ += 4
//            memory.write(environBuf, data)
//            environBuf += data.size
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun environSizesGet(memory: Memory, environCount: Int, environBufSize: Int): Int {
//        logger.tracef("environ_sizes_get: [%s, %s]", environCount, environBufSize)
//        val bufSize =
//            environment.stream()
//                .mapToInt { x: MutableMap.MutableEntry<ByteArray?, ByteArray?>? -> x!!.key!!.size + x.value!!.size + 2 }
//                .sum()
//        memory.writeI32(environCount, environment.size)
//        memory.writeI32(environBufSize, bufSize)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdAdvise(fd: Int, offset: Long, len: Long, advice: Int): Int {
//        logger.tracef("fd_advise: [%s, %s, %s, %s]", fd, offset, len, advice)
//
//        if (len < 0 || offset < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.ESPIPE)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        // do nothing: advise is optional, and Java does not support it
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdAllocate(fd: Int, offset: Long, len: Long): Int {
//        logger.tracef("fd_allocate: [%s, %s, %s]", fd, offset, len)
//
//        if (len <= 0 || offset < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        val channel = descriptor.channel()
//        try {
//            val size = offset + len
//            if (size > channel.size()) {
//                val position = channel.position()
//                try {
//                    channel.position(size - 1)
//                    if (channel.write(ByteBuffer.wrap(ByteArray(1))) != 1) {
//                        return wasiResult(WasiErrno.EIO)
//                    }
//                } finally {
//                    channel.position(position)
//                }
//            }
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdClose(fd: Int): Int {
//        logger.tracef("fd_close: [%s]", fd)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        descriptors.free(fd)
//        try {
//            if (descriptor is Closeable) {
//                (descriptor as Closeable).close()
//            }
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdDatasync(fd: Int): Int {
//        logger.tracef("fd_datasync: [%s]", fd)
//        return wasiResult(fileSync(fd, false))
//    }
//
//    @WasmExport
//    fun fdFdstatGet(memory: Memory, fd: Int, buf: Int): Int {
//        logger.tracef("fd_fdstat_get: [%s, %s]", fd, buf)
//        var flags = 0
//        val rightsBase: Long
//        var rightsInheriting: Long = 0
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        val fileType: WasiFileType?
//        if (descriptor is InStream) {
//            val inStream = descriptor
//            fileType = if (inStream.isTty()) WasiFileType.CHARACTER_DEVICE else WasiFileType.UNKNOWN
//            rightsBase = WasiRights.FD_READ.toLong()
//        } else if (descriptor is OutStream) {
//            val outStream = descriptor
//            fileType = if (outStream.isTty()) WasiFileType.CHARACTER_DEVICE else WasiFileType.UNKNOWN
//            rightsBase = WasiRights.FD_WRITE.toLong()
//        } else if (descriptor is Descriptors.Directory) {
//            fileType = WasiFileType.DIRECTORY
//            rightsBase = WasiRights.DIRECTORY_RIGHTS_BASE.toLong()
//            rightsInheriting = rightsBase or WasiRights.FILE_RIGHTS_BASE.toLong()
//        } else if (descriptor is OpenFile) {
//            val file = descriptor
//            fileType = WasiFileType.REGULAR_FILE
//            rightsBase = file.rights() and WasiRights.FILE_RIGHTS_BASE.toLong()
//            flags = file.fdFlags()
//        } else {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        memory.write(buf, ByteArray(8))
//        memory.writeByte(buf, fileType.value().toByte())
//        memory.writeShort(buf + 2, flags.toShort())
//        memory.writeLong(buf + 8, rightsBase)
//        memory.writeLong(buf + 16, rightsInheriting)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdFdstatSetFlags(fd: Int, flags: Int): Int {
//        logger.tracef("fd_fdstat_set_flags: [%s, %s]", fd, flags)
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//        if ((descriptor is OpenDirectory) || (descriptor is PreopenedDirectory)) {
//            return wasiResult(WasiErrno.ESUCCESS)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        // we don't support changing flags
//        if (flags != descriptor.fdFlags()) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        }
//
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdFdstatSetRights(fd: Int, rightsBase: Long, rightsInheriting: Long): Int {
//        logger.errorf(
//            "fd_fdstat_set_rights operation not supported: [%s, %s, %s]",
//            fd, rightsBase, rightsInheriting
//        )
//        return wasiResult(WasiErrno.ENOTSUP)
//    }
//
//    @WasmExport
//    fun fdFilestatGet(memory: Memory, fd: Int, buf: Int): Int {
//        logger.tracef("fd_filestat_get: [%s, %s]", fd, buf)
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            val attributes =
//                Map.of<String?, Any?>(
//                    "dev", 0L,
//                    "ino", 0L,
//                    "nlink", 1L,
//                    "size", 0L,
//                    "lastAccessTime", FileTime.from(Instant.EPOCH),
//                    "lastModifiedTime", FileTime.from(Instant.EPOCH),
//                    "ctime", FileTime.from(Instant.EPOCH)
//                )
//            writeFileStat(memory, buf, attributes, WasiFileType.CHARACTER_DEVICE)
//            return wasiResult(WasiErrno.ESUCCESS)
//        }
//
//        val path: Path
//        if (descriptor is OpenFile) {
//            path = descriptor.path()
//        } else if (descriptor is OpenDirectory) {
//            path = descriptor.path()
//        } else {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        val attributes: MutableMap<String?, Any?>?
//        try {
//            attributes = Files.readAttributes(path, "unix:*")
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        writeFileStat(memory, buf, attributes, getFileType(attributes))
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdFilestatSetSize(fd: Int, size: Long): Int {
//        logger.tracef("fd_filestat_set_size: [%s, %s]", fd, size)
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        val channel: SeekableByteChannel = descriptor.channel()
//        try {
//            val position = channel.position()
//            try {
//                if (size <= channel.size()) {
//                    channel.truncate(size)
//                } else {
//                    channel.position(size - 1)
//                    if (channel.write(ByteBuffer.wrap(ByteArray(1))) != 1) {
//                        return wasiResult(WasiErrno.EIO)
//                    }
//                }
//            } finally {
//                channel.position(position)
//            }
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdFilestatSetTimes(fd: Int, accessTime: Long, modifiedTime: Long, fstFlags: Int): Int {
//        logger.tracef(
//            "fd_filestat_set_times: [%s, %s, %s, %s]", fd, accessTime, modifiedTime, fstFlags
//        )
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val path: Path
//        if (descriptor is OpenFile) {
//            path = descriptor.path()
//        } else if (descriptor is Descriptors.Directory) {
//            path = (descriptor as Descriptors.Directory).path()
//        } else {
//            throw unhandledDescriptor(descriptor)
//        }
//
//        return wasiResult(setFileTimes(path, modifiedTime, accessTime, fstFlags))
//    }
//
//    @WasmExport
//    fun fdPread(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, offset: Long, nreadPtr: Int): Int {
//        var offset = offset
//        logger.tracef("fd_pread: [%s, %s, %s, %s, %s]", fd, iovs, iovsLen, offset, nreadPtr)
//
//        if (offset < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor is InStream) {
//            return wasiResult(WasiErrno.ESPIPE)
//        }
//        if (descriptor is OutStream) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val file = descriptor
//
//        var totalRead = 0
//        for (i in 0..<iovsLen) {
//            val base = iovs + (i * 8)
//            val iovBase = memory.readInt(base)
//            val iovLen = memory.readInt(base + 4)
//            try {
//                val data = ByteArray(iovLen)
//                val read = file.read(data, offset)
//                if (read < 0) {
//                    break
//                }
//                memory.write(iovBase, data, 0, read)
//                offset += read.toLong()
//                totalRead += read
//                if (read < iovLen) {
//                    break
//                }
//            } catch (e: NonReadableChannelException) {
//                return wasiResult(WasiErrno.ENOTCAPABLE)
//            } catch (e: IOException) {
//                return wasiResult(WasiErrno.EIO)
//            }
//        }
//
//        memory.writeI32(nreadPtr, totalRead)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdPrestatDirName(memory: Memory, fd: Int, path: Int, pathLen: Int): Int {
//        logger.tracef("fd_prestat_dir_name: [%s, %s, %s]", fd, path, pathLen)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is PreopenedDirectory) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        val name = descriptor.name()
//
//        if (pathLen < name.size) {
//            return wasiResult(WasiErrno.ENAMETOOLONG)
//        }
//
//        memory.write(path, name)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdPrestatGet(memory: Memory, fd: Int, buf: Int): Int {
//        logger.tracef("fd_prestat_get: [%s, %s]", fd, buf)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is PreopenedDirectory) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        val length = descriptor.name().size
//
//        memory.writeI32(buf, 0) // preopentype::dir
//        memory.writeI32(buf + 4, length)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdPwrite(
//        memory: Memory, fd: Int, iovs: Int, iovsLen: Int, offset: Long, nwrittenPtr: Int
//    ): Int {
//        var offset = offset
//        logger.tracef("fd_pwrite: [%s, %s, %s, %s, %s]", fd, iovs, iovsLen, offset, nwrittenPtr)
//
//        if (offset < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor is InStream) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (descriptor is OutStream) {
//            return wasiResult(WasiErrno.ESPIPE)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val file = descriptor
//
//        if (flagSet(file.fdFlags().toLong(), WasiFdFlags.APPEND.toLong())) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        }
//
//        var totalWritten = 0
//        for (i in 0..<iovsLen) {
//            val base = iovs + (i * 8)
//            val iovBase = memory.readInt(base)
//            val iovLen = memory.readInt(base + 4)
//            val data = memory.readBytes(iovBase, iovLen)
//            try {
//                val written = file.write(data, offset)
//                offset += written.toLong()
//                totalWritten += written
//                if (written < iovLen) {
//                    break
//                }
//            } catch (e: NonWritableChannelException) {
//                return wasiResult(WasiErrno.ENOTCAPABLE)
//            } catch (e: IOException) {
//                return wasiResult(WasiErrno.EIO)
//            }
//        }
//
//        memory.writeI32(nwrittenPtr, totalWritten)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdRead(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nreadPtr: Int): Int {
//        logger.tracef("fd_read: [%s, %s, %s, %s]", fd, iovs, iovsLen, nreadPtr)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor is OutStream) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is Descriptors.DataReader) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val reader = descriptor as Descriptors.DataReader
//
//        var totalRead = 0
//        for (i in 0..<iovsLen) {
//            val base = iovs + (i * 8)
//            val iovBase = memory.readInt(base)
//            val iovLen = memory.readInt(base + 4)
//            try {
//                val data = ByteArray(iovLen)
//                val read = reader.read(data)
//                if (read < 0) {
//                    break
//                }
//                memory.write(iovBase, data, 0, read)
//                totalRead += read
//                if (read < iovLen) {
//                    break
//                }
//            } catch (e: NonReadableChannelException) {
//                return wasiResult(WasiErrno.ENOTCAPABLE)
//            } catch (e: IOException) {
//                return wasiResult(WasiErrno.EIO)
//            }
//        }
//
//        memory.writeI32(nreadPtr, totalRead)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdReaddir(
//        memory: Memory, dirFd: Int, buf: Int, bufLen: Int, cookie: Long, bufUsedPtr: Int
//    ): Int {
//        var cookie = cookie
//        logger.tracef("fd_readdir: [%s, %s, %s, %s, %s]", dirFd, buf, bufLen, cookie, bufUsedPtr)
//        if (cookie < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        var used = 0
//        try {
//            Files.list(directory).use { stream ->
//                val special = Stream.of<Path?>(directory.resolve("."), directory.resolve(".."))
//                val iterator = Stream.concat<Path?>(special, stream).skip(cookie).iterator()
//                while (iterator.hasNext()) {
//                    val entryPath = iterator.next()
//                    val name = entryPath.getFileName().toString().toByteArray(StandardCharsets.UTF_8)
//                    cookie++
//
//                    val attributes: MutableMap<String?, Any?>?
//                    try {
//                        attributes = Files.readAttributes(entryPath, "unix:*")
//                    } catch (e: UnsupportedOperationException) {
//                        return wasiResult(WasiErrno.ENOTSUP)
//                    } catch (e: NoSuchFileException) {
//                        continue
//                    }
//
//                    val entry =
//                        ByteBuffer.allocate(24 + name.size).order(ByteOrder.LITTLE_ENDIAN)
//                    entry.putLong(0, cookie)
//                    entry.putLong(8, (attributes.get("ino") as Number).toLong())
//                    entry.putInt(16, name.size)
//                    entry.put(20, getFileType(attributes).value().toByte())
//                    entry.position(24)
//                    entry.put(name)
//
//                    val writeSize = min(entry.capacity(), bufLen - used)
//                    memory.write(buf + used, entry.array(), 0, writeSize)
//                    used += writeSize
//
//                    if (used == bufLen) {
//                        break
//                    }
//                }
//            }
//        } catch (e: NotDirectoryException) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        memory.writeI32(bufUsedPtr, used)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdRenumber(from: Int, to: Int): Int {
//        logger.tracef("fd_renumber: [%s, %s]", from, to)
//
//        val fromDescriptor = descriptors.get(from)
//        if (fromDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (from == to) {
//            return wasiResult(WasiErrno.ESUCCESS)
//        }
//
//        val toDescriptor = descriptors.get(to)
//        if (toDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        try {
//            if (toDescriptor is Closeable) {
//                (toDescriptor as Closeable).close()
//            }
//        } catch (e: IOException) {
//            // ignored
//        }
//
//        descriptors.free(from)
//        descriptors.set(to, fromDescriptor)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdSeek(memory: Memory, fd: Int, offset: Long, whence: Int, newOffsetPtr: Int): Int {
//        logger.tracef("fd_seek: [%s, %s, %s, %s]", fd, offset, whence, newOffsetPtr)
//        if (whence < 0 || whence > 2) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.ESPIPE)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val channel: SeekableByteChannel = descriptor.channel()
//
//        val newOffset: Long
//        try {
//            when (whence) {
//                WasiWhence.SET -> channel.position(offset)
//                WasiWhence.CUR -> channel.position(channel.position() + offset)
//                WasiWhence.END -> channel.position(channel.size() + offset)
//            }
//            newOffset = channel.position()
//        } catch (e: IllegalArgumentException) {
//            return wasiResult(WasiErrno.EINVAL)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        memory.writeLong(newOffsetPtr, newOffset)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdSync(fd: Int): Int {
//        logger.tracef("fd_sync: [%s]", fd)
//        return wasiResult(fileSync(fd, true))
//    }
//
//    @WasmExport
//    fun fdTell(memory: Memory, fd: Int, offsetPtr: Int): Int {
//        logger.tracef("fd_tell: [%s, %s]", fd, offsetPtr)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if ((descriptor is InStream) || (descriptor is OutStream)) {
//            return wasiResult(WasiErrno.ESPIPE)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val channel: SeekableByteChannel = descriptor.channel()
//
//        val offset: Long
//        try {
//            offset = channel.position()
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        memory.writeLong(offsetPtr, offset)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun fdWrite(memory: Memory, fd: Int, iovs: Int, iovsLen: Int, nwrittenPtr: Int): Int {
//        logger.tracef("fd_write: [%s, %s, %s, %s]", fd, iovs, iovsLen, nwrittenPtr)
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor is InStream) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (descriptor is Descriptors.Directory) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//        if (descriptor !is Descriptors.DataWriter) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val writer = descriptor as Descriptors.DataWriter
//
//        var totalWritten = 0
//        for (i in 0..<iovsLen) {
//            val base = iovs + (i * 8)
//            val iovBase = memory.readInt(base)
//            val iovLen = memory.readInt(base + 4)
//            val data = memory.readBytes(iovBase, iovLen)
//            try {
//                val written = writer.write(data)
//                totalWritten += written
//                if (written < iovLen) {
//                    break
//                }
//            } catch (e: NonWritableChannelException) {
//                return wasiResult(WasiErrno.ENOTCAPABLE)
//            } catch (e: IOException) {
//                return wasiResult(WasiErrno.EIO)
//            }
//        }
//
//        memory.writeI32(nwrittenPtr, totalWritten)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathCreateDirectory(dirFd: Int, @Buffer rawPath: String): Int {
//        logger.tracef("path_create_directory: [%s, \"%s\"]", dirFd, rawPath)
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        try {
//            Files.createDirectory(path)
//        } catch (e: FileAlreadyExistsException) {
//            return wasiResult(WasiErrno.EEXIST)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathFilestatGet(
//        memory: Memory, dirFd: Int, lookupFlags: Int, @Buffer rawPath: String, buf: Int
//    ): Int {
//        logger.tracef("path_filestat_get: [%s, %s, \"%s\", %s]", dirFd, lookupFlags, rawPath, buf)
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        var path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        val linkOptions = toLinkOptions(lookupFlags)
//
//        val attributes: MutableMap<String?, Any?>?
//        try {
//            if (flagSet(lookupFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong())) {
//                val resolved = resolveSymlinks(path)
//                if (resolved.isError) {
//                    return resolved.error()
//                } else {
//                    path = resolved.resolved()
//                }
//            }
//            attributes = Files.readAttributes(path, "unix:*", *linkOptions)
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        writeFileStat(memory, buf, attributes, getFileType(attributes))
//
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathFilestatSetTimes(
//        fd: Int,
//        lookupFlags: Int,
//        @Buffer rawPath: String,
//        accessTime: Long,
//        modifiedTime: Long,
//        fstFlags: Int
//    ): Int {
//        logger.tracef(
//            "path_filestat_set_times: [%s, %s, \"%s\", %s, %s, %s]",
//            fd, lookupFlags, rawPath, accessTime, modifiedTime, fstFlags
//        )
//
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        return wasiResult(setFileTimes(path, modifiedTime, accessTime, fstFlags))
//    }
//
//    private class ResolvedSymlink {
//        private val resolved: Path?
//        private val errorcode: Int
//
//        internal constructor(resolved: Path?) {
//            this.resolved = resolved
//            this.errorcode = 0
//        }
//
//        internal constructor(errcode: Int) {
//            this.resolved = null
//            this.errorcode = errcode
//        }
//
//        val isError: Boolean
//            get() = (this.resolved == null)
//
//        fun error(): Int {
//            return errorcode
//        }
//
//        fun resolved(): Path? {
//            return resolved
//        }
//    }
//
//    private fun resolveSymlinks(path: Path): ResolvedSymlink {
//        var path = path
//        val visited: MutableList<Path?> = ArrayList<Path?>()
//        while (Files.isSymbolicLink(path)) {
//            if (visited.contains(path)) {
//                return ResolvedSymlink(wasiResult(WasiErrno.ELOOP))
//            } else {
//                visited.add(path)
//            }
//
//            try {
//                path = Files.readSymbolicLink(path)
//            } catch (e: IOException) {
//                return ResolvedSymlink(wasiResult(WasiErrno.EIO))
//            }
//        }
//
//        return ResolvedSymlink(path)
//    }
//
//    @WasmExport
//    fun pathLink(
//        oldFd: Int,
//        oldFlags: Int,
//        @Buffer rawOldPath: String,
//        newFd: Int,
//        @Buffer rawNewPath: String
//    ): Int {
//        logger.tracef(
//            "path_link: [%s, %s, \"%s\", %s, \"%s\"]",
//            oldFd, oldFlags, rawOldPath, newFd, rawNewPath
//        )
//        val oldDescriptor = descriptors.get(oldFd)
//        if (oldDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (oldDescriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val oldDirectory = (oldDescriptor as Descriptors.Directory).path()
//
//        val newDescriptor = descriptors.get(newFd)
//        if (newDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (newDescriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val newDirectory = (newDescriptor as Descriptors.Directory).path()
//
//        var oldPath = resolvePath(oldDirectory, rawOldPath)
//        if (oldPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        if (rawNewPath.endsWith("/")) {
//            return wasiResult(WasiErrno.ENOENT)
//        }
//        val newPath = resolvePath(newDirectory, rawNewPath)
//        if (newPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//        if (Files.exists(newPath)) {
//            return wasiResult(WasiErrno.EEXIST)
//        }
//        if (Files.isDirectory(oldPath)) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        if (Files.isDirectory(oldPath) && Files.isRegularFile(newPath, LinkOption.NOFOLLOW_LINKS)) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        if (Files.isRegularFile(oldPath, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(newPath)) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//
//        try {
//            if (flagSet(oldFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong())) {
//                val resolved = resolveSymlinks(oldPath)
//                if (resolved.isError) {
//                    return resolved.error()
//                } else {
//                    oldPath = resolved.resolved()
//                }
//            }
//            Files.createLink(newPath, oldPath)
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: AtomicMoveNotSupportedException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: DirectoryNotEmptyException) {
//            return wasiResult(WasiErrno.ENOTEMPTY)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathOpen(
//        memory: Memory,
//        dirFd: Int,
//        lookupFlags: Int,
//        @Buffer rawPath: String,
//        openFlags: Int,
//        rightsBase: Long,
//        rightsInheriting: Long,
//        fdFlags: Int,
//        fdPtr: Int
//    ): Int {
//        logger.tracef(
//            "path_open: [%s, %s, \"%s\", %s, %s, %s, %s, %s]",
//            dirFd,
//            lookupFlags,
//            rawPath,
//            openFlags,
//            rightsBase,
//            rightsInheriting,
//            fdFlags,
//            fdPtr
//        )
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        if (rawPath.endsWith("\u0000")) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        var path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EPERM)
//        }
//
//        if (flagSet(openFlags.toLong(), WasiOpenFlags.DIRECTORY.toLong())
//            && !flagSet(lookupFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong()) && Files.isSymbolicLink(path)
//        ) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//
//        if (!flagSet(lookupFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong()) && Files.isSymbolicLink(path)) {
//            return wasiResult(WasiErrno.ELOOP)
//        }
//
//        if (flagSet(lookupFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong())) {
//            val resolved = resolveSymlinks(path)
//            if (resolved.isError) {
//                return resolved.error()
//            } else {
//                path = resolved.resolved()
//            }
//        }
//
//        if (Files.isDirectory(path)) {
//            if (flagSet(rightsBase, WasiRights.FD_WRITE.toLong())) {
//                return wasiResult(WasiErrno.EISDIR)
//            }
//            val fd = descriptors.allocate(OpenDirectory(path))
//            memory.writeI32(fdPtr, fd)
//            return wasiResult(WasiErrno.ESUCCESS)
//        }
//
//        if (rawPath.endsWith("/")) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        if (flagSet(openFlags.toLong(), WasiOpenFlags.DIRECTORY.toLong()) && Files.exists(path)) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//
//        val openOptions: MutableSet<OpenOption?> = HashSet<OpenOption?>(mutableListOf<OpenOption?>())
//
//        val append = flagSet(fdFlags.toLong(), WasiFdFlags.APPEND.toLong())
//        val truncate = flagSet(openFlags.toLong(), WasiOpenFlags.TRUNC.toLong())
//
//        if (append && truncate) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        }
//        if (!append && flagSet(rightsBase, WasiRights.FD_READ.toLong())) {
//            openOptions.add(StandardOpenOption.READ)
//        }
//        if (flagSet(rightsBase, WasiRights.FD_WRITE.toLong())) {
//            openOptions.add(StandardOpenOption.WRITE)
//        }
//
//        if (flagSet(openFlags.toLong(), WasiOpenFlags.CREAT.toLong())) {
//            if (flagSet(openFlags.toLong(), WasiOpenFlags.EXCL.toLong())) {
//                openOptions.add(StandardOpenOption.CREATE_NEW)
//            } else {
//                openOptions.add(StandardOpenOption.CREATE)
//            }
//            openOptions.add(StandardOpenOption.WRITE)
//        }
//        if (truncate) {
//            openOptions.add(StandardOpenOption.TRUNCATE_EXISTING)
//        }
//        if (append) {
//            openOptions.add(StandardOpenOption.APPEND)
//        }
//        if (flagSet(fdFlags.toLong(), WasiFdFlags.SYNC.toLong())) {
//            openOptions.add(StandardOpenOption.SYNC)
//        }
//        if (flagSet(fdFlags.toLong(), WasiFdFlags.DSYNC.toLong())) {
//            openOptions.add(StandardOpenOption.DSYNC)
//        }
//
//        // ignore WasiFdFlags.RSYNC and WasiFdFlags.NONBLOCK
//        val fd: Int
//        try {
//            val channel = FileChannel.open(path, openOptions)
//            fd = descriptors.allocate(OpenFile(path, channel, fdFlags, rightsBase))
//        } catch (e: FileAlreadyExistsException) {
//            return wasiResult(WasiErrno.EEXIST)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        memory.writeI32(fdPtr, fd)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathReadlink(
//        memory: Memory, dirFd: Int, @Buffer rawPath: String, buf: Int, bufLen: Int, bufUsedPtr: Int
//    ): Int {
//        logger.tracef(
//            "path_readlink: [%s, \"%s\", %s, %s, %s]", dirFd, rawPath, buf, bufLen, bufUsedPtr
//        )
//
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        val link: Path
//        try {
//            link = Files.readSymbolicLink(path)
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: NotLinkException) {
//            return wasiResult(WasiErrno.EINVAL)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//
//        val name = link.getFileName().toString().toByteArray(StandardCharsets.UTF_8)
//        val used = min(name.size, bufLen)
//        memory.write(buf, name, 0, used)
//        memory.writeI32(bufUsedPtr, used)
//
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathRemoveDirectory(dirFd: Int, @Buffer rawPath: String): Int {
//        logger.tracef("path_remove_directory: [%s, \"%s\"]", dirFd, rawPath)
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        try {
//            val attributes =
//                Files.readAttributes<BasicFileAttributes?>(
//                    path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
//                )
//            if (!attributes.isDirectory()) {
//                return wasiResult(WasiErrno.ENOTDIR)
//            }
//            Files.delete(path)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: DirectoryNotEmptyException) {
//            return wasiResult(WasiErrno.ENOTEMPTY)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathRename(
//        oldFd: Int, @Buffer oldRawPath: String, newFd: Int, @Buffer newRawPath: String
//    ): Int {
//        logger.tracef(
//            "path_rename: [%s, \"%s\", %s, \"%s\"]", oldFd, oldRawPath, newFd, newRawPath
//        )
//        val oldDescriptor = descriptors.get(oldFd)
//        if (oldDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (oldDescriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val oldDirectory = (oldDescriptor as Descriptors.Directory).path()
//
//        val newDescriptor = descriptors.get(newFd)
//        if (newDescriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (newDescriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val newDirectory = (newDescriptor as Descriptors.Directory).path()
//
//        val oldPath = resolvePath(oldDirectory, oldRawPath)
//        if (oldPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        val newPath = resolvePath(newDirectory, newRawPath)
//        if (newPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        if (Files.isDirectory(oldPath) && Files.isRegularFile(newPath, LinkOption.NOFOLLOW_LINKS)) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        if (Files.isRegularFile(oldPath, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(newPath)) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//
//        try {
//            Files.move(
//                oldPath,
//                newPath,
//                StandardCopyOption.REPLACE_EXISTING,
//                StandardCopyOption.ATOMIC_MOVE,
//                StandardCopyOption.COPY_ATTRIBUTES
//            )
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: AtomicMoveNotSupportedException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: DirectoryNotEmptyException) {
//            return wasiResult(WasiErrno.ENOTEMPTY)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathSymlink(@Buffer oldRawPath: String, dirFd: Int, @Buffer newRawPath: String): Int {
//        logger.tracef("path_symlink: [\"%s\", %s, \"%s\"]", oldRawPath, dirFd, newRawPath)
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val oldPath = resolvePath(directory, oldRawPath)
//        if (oldPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        if (newRawPath.endsWith("/")) {
//            return wasiResult(WasiErrno.EEXIST)
//        }
//
//        val newPath = resolvePath(directory, newRawPath)
//        if (newPath == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        if (Files.exists(newPath)) {
//            return wasiResult(WasiErrno.EEXIST)
//        }
//
//        if (Files.isDirectory(oldPath) && Files.isRegularFile(newPath, LinkOption.NOFOLLOW_LINKS)) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        if (Files.isRegularFile(oldPath, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(newPath)) {
//            return wasiResult(WasiErrno.EISDIR)
//        }
//
//        try {
//            Files.createSymbolicLink(newPath, oldPath)
//        } catch (e: UnsupportedOperationException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: AtomicMoveNotSupportedException) {
//            return wasiResult(WasiErrno.ENOTSUP)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: DirectoryNotEmptyException) {
//            return wasiResult(WasiErrno.ENOTEMPTY)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pathUnlinkFile(dirFd: Int, @Buffer rawPath: String): Int {
//        logger.tracef("path_unlink_file: [%s, \"%s\"]", dirFd, rawPath)
//        val descriptor = descriptors.get(dirFd)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//
//        if (descriptor !is Descriptors.Directory) {
//            return wasiResult(WasiErrno.ENOTDIR)
//        }
//        val directory = (descriptor as Descriptors.Directory).path()
//
//        val path = resolvePath(directory, rawPath)
//        if (path == null) {
//            return wasiResult(WasiErrno.EACCES)
//        }
//
//        try {
//            val attributes =
//                Files.readAttributes<BasicFileAttributes?>(
//                    path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
//                )
//            if (attributes.isDirectory()) {
//                return wasiResult(WasiErrno.EISDIR)
//            }
//            if (rawPath.endsWith("/")) {
//                return wasiResult(WasiErrno.ENOTDIR)
//            }
//            Files.delete(path)
//        } catch (e: NoSuchFileException) {
//            return wasiResult(WasiErrno.ENOENT)
//        } catch (e: IOException) {
//            return wasiResult(WasiErrno.EIO)
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun pollOneoff(
//        memory: Memory, inPtr: Int, outPtr: Int, nsubscriptions: Int, neventsPtr: Int
//    ): Int {
//        var inPtr = inPtr
//        var outPtr = outPtr
//        logger.tracef("poll_oneoff: [%s, %s, %s, %s]", inPtr, outPtr, nsubscriptions, neventsPtr)
//        if (nsubscriptions <= 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        var nevents = 0
//        val clockSubs: MutableList<MutableMap.MutableEntry<Long?, Long?>> =
//            ArrayList<MutableMap.MutableEntry<Long?, Long?>>()
//        val readSubs: MutableList<MutableMap.MutableEntry<InStream, Long?>> =
//            ArrayList<MutableMap.MutableEntry<InStream, Long?>>()
//
//        // collect clock and read subscriptions
//        for (i in 0..<nsubscriptions) {
//            val userData = memory.readLong(inPtr)
//            val eventType = memory.read(inPtr + 8)
//            inPtr += 16
//            when (eventType) {
//                WasiEventType.CLOCK -> {
//                    val clockId = memory.readInt(inPtr)
//                    val timeout = memory.readLong(inPtr + 8)
//                    val flags = memory.readShort(inPtr + 24)
//                    if (clockId != WasiClockId.REALTIME && clockId != WasiClockId.MONOTONIC) {
//                        return wasiResult(WasiErrno.EINVAL)
//                    }
//                    if (flagSet(flags.toLong(), WasiSubClockFlags.SUBSCRIPTION_CLOCK_ABSTIME.toLong())) {
//                        timeout -= clockTime(clockId)
//                    }
//                    clockSubs.add(Map.entry<Long?, Long?>(timeout, userData))
//                }
//
//                WasiEventType.FD_READ, WasiEventType.FD_WRITE -> {
//                    val fd = memory.readInt(inPtr)
//                    if (fd < 0) {
//                        return wasiResult(WasiErrno.EBADF)
//                    }
//                    val descriptor = descriptors.get(fd)
//                    if (descriptor is InStream && eventType == WasiEventType.FD_READ) {
//                        readSubs.add(Map.entry<InStream?, Long?>(descriptor, userData))
//                    } else if (descriptor is OutStream
//                        && eventType == WasiEventType.FD_WRITE
//                    ) {
//                        // assume output streams are always writable
//                        writeEvent(memory, outPtr, userData, eventType, WasiErrno.ESUCCESS)
//                        outPtr += 32
//                        nevents++
//                    } else {
//                        val errno: WasiErrno?
//                        if (descriptor == null) {
//                            errno = WasiErrno.EBADF
//                        } else if (descriptor is OpenFile) {
//                            // per specification: this event always triggers for regular files
//                            errno = WasiErrno.ESUCCESS
//                        } else {
//                            errno = WasiErrno.ENOTSUP
//                        }
//                        writeEvent(memory, outPtr, userData, eventType, errno)
//                        outPtr += 32
//                        nevents++
//                    }
//                }
//
//                else -> return wasiResult(WasiErrno.EINVAL)
//            }
//            inPtr += 32
//        }
//
//        // sleep until the earliest clock sub, or forever if we only have read subs
//        val minTimeout =
//            clockSubs.stream().mapToLong { obj: MutableMap.MutableEntry<Long?, Long?>? -> obj!!.key!! }.min().orElse(
//                Long.MAX_VALUE
//            )
//
//        // loop until at least one event is triggered
//        val start = System.nanoTime()
//        do {
//            // process available read events
//            for (entry in readSubs) {
//                val stream = entry.key
//                val userData: Long = entry.value!!
//                try {
//                    val available = stream.available()
//                    if (available <= 0) {
//                        continue
//                    }
//                    writeEvent(memory, outPtr, userData, WasiEventType.FD_READ, WasiErrno.ESUCCESS)
//                    memory.writeLong(outPtr + 16, available.toLong())
//                    outPtr += 32
//                    nevents++
//                } catch (e: IOException) {
//                    writeEvent(memory, outPtr, userData, WasiEventType.FD_READ, WasiErrno.EIO)
//                    outPtr += 32
//                    nevents++
//                }
//            }
//
//            // sleep if no events have triggered
//            val elapsed = System.nanoTime() - start
//            if (nevents == 0) {
//                var duration = max(minTimeout, 0) - elapsed
//                // poll if we have read subs, rather than waiting for the full clock timeout
//                if (!readSubs.isEmpty()) {
//                    duration = min(duration, TimeUnit.MILLISECONDS.toNanos(100))
//                }
//                try {
//                    TimeUnit.NANOSECONDS.sleep(duration)
//                } catch (e: InterruptedException) {
//                    throw ChicoryException("Thread interrupted", e)
//                }
//            }
//
//            // process available clock events
//            for (entry in clockSubs) {
//                val timeout: Long = entry.key!!
//                val userData: Long = entry.value!!
//                if (timeout <= elapsed) {
//                    writeEvent(memory, outPtr, userData, WasiEventType.CLOCK, WasiErrno.ESUCCESS)
//                    outPtr += 32
//                    nevents++
//                }
//            }
//        } while (nevents == 0)
//
//        memory.writeI32(neventsPtr, nevents)
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun procExit(code: Int) {
//        logger.tracef("proc_exit: [%s]", code)
//        if (code == 0 && !throwOnExit0) {
//            throw ExecutionCompletedException("proc_exit: 0")
//        }
//        throw WasiExitException(code)
//    }
//
//    @WasmExport
//    fun procRaise(sig: Int): Int {
//        logger.tracef("proc_raise: [%s]", sig)
//        throw WasmRuntimeException("We don't yet support this WASI call: proc_raise")
//    }
//
//    @WasmExport
//    fun randomGet(memory: Memory, buf: Int, bufLen: Int): Int {
//        logger.tracef("random_get: [%s, %s]", buf, bufLen)
//        if (bufLen < 0) {
//            return wasiResult(WasiErrno.EINVAL)
//        }
//
//        var data = ByteArray(min(bufLen, 4096))
//        var written = 0
//        while (written < bufLen) {
//            if (Thread.currentThread().isInterrupted()) {
//                throw ChicoryException("Thread interrupted")
//            }
//            val size = min(data.size, bufLen - written)
//            if (size < data.size) {
//                data = ByteArray(size)
//            }
//            random.nextBytes(data)
//            memory.write(buf + written, data, 0, size)
//            written += size
//        }
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun schedYield(): Int {
//        logger.trace("sched_yield")
//        // do nothing here
//        return wasiResult(WasiErrno.ESUCCESS)
//    }
//
//    @WasmExport
//    fun sockAccept(sock: Int, fdFlags: Int, roFdPtr: Int): Int {
//        logger.tracef("sock_accept: [%s, %s, %s]", sock, fdFlags, roFdPtr)
//        throw WasmRuntimeException("We don't yet support this WASI call: sock_accept")
//    }
//
//    @WasmExport
//    fun sockRecv(
//        sock: Int, riDataPtr: Int, riDataLen: Int, riFlags: Int, roDataLenPtr: Int, roFlagsPtr: Int
//    ): Int {
//        logger.tracef(
//            "sock_recv: [%s, %s, %s, %s, %s, %s]",
//            sock, riDataPtr, riDataLen, riFlags, roDataLenPtr, roFlagsPtr
//        )
//        throw WasmRuntimeException("We don't yet support this WASI call: sock_recv")
//    }
//
//    @WasmExport
//    fun sockSend(sock: Int, siDataPtr: Int, siDataLen: Int, siFlags: Int, retDataLenPtr: Int): Int {
//        logger.tracef(
//            "sock_send: [%s, %s, %s, %s, %s]",
//            sock, siDataPtr, siDataLen, siFlags, retDataLenPtr
//        )
//        throw WasmRuntimeException("We don't yet support this WASI call: sock_send")
//    }
//
//    @WasmExport
//    fun sockShutdown(sock: Int, how: Int): Int {
//        logger.tracef("sock_shutdown: [%s, %s]", sock, how)
//        val descriptor = descriptors.get(sock)
//        if (descriptor == null) {
//            return wasiResult(WasiErrno.EBADF)
//        }
//        // sockets are not supported, so this cannot be a socket
//        return wasiResult(WasiErrno.ENOTSOCK)
//    }
//
//    fun toHostFunctions(): Array<HostFunction?> {
//        return WasiPreview1_ModuleFactory.toHostFunctions(this)
//    }
//
//    private fun wasiResult(errno: WasiErrno): Int {
//        if (errno != WasiErrno.ESUCCESS) {
//            logger.tracef("result = %s", errno.name)
//        }
//        return errno.value()
//    }
//
//    private fun clockTime(clockId: Int): Long {
//        when (clockId) {
//            WasiClockId.REALTIME -> {
//                val now = clock.instant()
//                return TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano()
//            }
//
//            WasiClockId.MONOTONIC -> return System.nanoTime()
//            else -> throw IllegalArgumentException("Invalid clockId: " + clockId)
//        }
//    }
//
//    private fun setFileTimes(path: Path, modifiedTime: Long, accessTime: Long, flags: Int): WasiErrno {
//        val modifiedSet = flagSet(flags.toLong(), WasiFstFlags.MTIM.toLong())
//        val modifiedNow = flagSet(flags.toLong(), WasiFstFlags.MTIM_NOW.toLong())
//        val accessSet = flagSet(flags.toLong(), WasiFstFlags.ATIM.toLong())
//        val accessNow = flagSet(flags.toLong(), WasiFstFlags.ATIM_NOW.toLong())
//
//        if ((modifiedSet && modifiedNow) || (accessSet && accessNow)) {
//            return WasiErrno.EINVAL
//        }
//
//        val lastModifiedTime = toFileTime(modifiedTime, modifiedSet, modifiedNow)
//        val lastAccessTime = toFileTime(accessTime, accessSet, accessNow)
//
//        try {
//            Files.getFileAttributeView<BasicFileAttributeView?>(path, BasicFileAttributeView::class.java)
//                .setTimes(lastModifiedTime, lastAccessTime, null)
//        } catch (e: IOException) {
//            return WasiErrno.EIO
//        }
//        return WasiErrno.ESUCCESS
//    }
//
//    private fun toFileTime(time: Long, set: Boolean, now: Boolean): FileTime? {
//        if (set) {
//            return FileTime.from(time, TimeUnit.NANOSECONDS)
//        }
//        if (now) {
//            return FileTime.from(clock.instant())
//        }
//        return null
//    }
//
//    private fun fileSync(fd: Int, metadata: Boolean): WasiErrno {
//        val descriptor = descriptors.get(fd)
//        if (descriptor == null) {
//            return WasiErrno.EBADF
//        }
//
//        if ((descriptor is InStream)
//            || (descriptor is OutStream)
//            || (descriptor is Descriptors.Directory)
//        ) {
//            return WasiErrno.EINVAL
//        }
//
//        if (descriptor !is OpenFile) {
//            throw unhandledDescriptor(descriptor)
//        }
//        val channel = descriptor.channel()
//
//        try {
//            channel.force(metadata)
//        } catch (e: IOException) {
//            return WasiErrno.EIO
//        }
//        return WasiErrno.ESUCCESS
//    }
//
//    companion object {
//        fun builder(): Builder {
//            return WasiPreview1.Builder()
//        }
//
//        private fun writeEvent(
//            memory: Memory, index: Int, userData: Long, eventType: Byte, errno: WasiErrno
//        ) {
//            memory.fill(0.toByte(), index, index + 32)
//            memory.writeLong(index, userData)
//            memory.writeShort(index + 8, errno.value().toShort())
//            memory.writeByte(index + 10, eventType)
//        }
//
//        private fun resolvePath(directory: Path, rawPathString: String): Path? {
//            val rawPath: Path?
//            try {
//                rawPath = directory.getFileSystem().getPath(rawPathString)
//            } catch (e: InvalidPathException) {
//                return null
//            }
//
//            if (rawPath.isAbsolute()) {
//                return null
//            }
//
//            val normalized = rawPath.normalize().toString()
//            if (normalized == ".." || normalized.startsWith("../")) {
//                return null
//            }
//
//            return directory.resolve(normalized)
//        }
//
//        private fun writeFileStat(
//            memory: Memory, buf: Int, attributes: MutableMap<String?, Any?>, fileType: WasiFileType
//        ) {
//            memory.writeLong(buf, attributes.get("dev") as Long)
//            memory.writeLong(buf + 8, (attributes.get("ino") as Number).toLong())
//            memory.write(buf + 16, ByteArray(8))
//            memory.writeByte(buf + 16, fileType.value().toByte())
//            memory.writeLong(buf + 24, (attributes.get("nlink") as Number).toLong())
//            memory.writeLong(buf + 32, attributes.get("size") as Long)
//            memory.writeLong(buf + 40, fileTimeToNanos(attributes, "lastAccessTime"))
//            memory.writeLong(buf + 48, fileTimeToNanos(attributes, "lastModifiedTime"))
//            memory.writeLong(buf + 56, fileTimeToNanos(attributes, "ctime"))
//        }
//
//        private fun fileTimeToNanos(attributes: MutableMap<String?, Any?>, name: String?): Long {
//            return (attributes.get(name) as FileTime).to(TimeUnit.NANOSECONDS)
//        }
//
//        private fun getFileType(attributes: MutableMap<String?, Any?>): WasiFileType {
//            if (attributes.get("isSymbolicLink") as Boolean) {
//                return WasiFileType.SYMBOLIC_LINK
//            }
//            if (attributes.get("isDirectory") as Boolean) {
//                return WasiFileType.DIRECTORY
//            }
//            if (attributes.get("isRegularFile") as Boolean) {
//                return WasiFileType.REGULAR_FILE
//            }
//            return WasiFileType.UNKNOWN
//        }
//
//        private fun toLinkOptions(lookupFlags: Int): Array<LinkOption?> {
//            return if (flagSet(lookupFlags.toLong(), WasiLookupFlags.SYMLINK_FOLLOW.toLong()))
//                arrayOfNulls<LinkOption>(0)
//            else
//                arrayOf<LinkOption>(LinkOption.NOFOLLOW_LINKS)
//        }
//
//        private fun flagSet(flags: Long, mask: Long): Boolean {
//            require(Long.bitCount(mask) == 1) { "mask must be a single bit" }
//            return (flags and mask) != 0L
//        }
//
//        private fun unhandledDescriptor(descriptor: Descriptors.Descriptor): RuntimeException {
//            return WasmRuntimeException("Unhandled descriptor: " + descriptor.javaClass.getName())
//        }
//    }
//}