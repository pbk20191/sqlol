package org.example.demo1webfl


import java.io.IOException
import java.lang.foreign.*
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout.*
import java.lang.invoke.MethodHandle
import java.net.Socket


class Iocp {

    companion object {

//        val INVALID_HANDLE_VALUE_INT: Int = -1
//
//        const val INFINITE: Int = -0x1
//
        val NULL: MemorySegment = MemorySegment.NULL
//
//
//
//
//        val _FormatMessage: MethodHandle?
        val _GetLastError: MethodHandle?

        val _CreateIoCompletionPort: MethodHandle?

        val _GetQueuedCompletionStatus: MethodHandle?

        val _PostQueuedCompletionStatus: MethodHandle?

        val _CloseHandle: MethodHandle?


        init {

            // HANDLE, ULONG_PTR, LPOVERLAPPED 는 포인터 크기

            val HANDLE: AddressLayout = ADDRESS

            val ULONG_PTR: AddressLayout = ADDRESS

            val LPOVERLAPPED: AddressLayout = ADDRESS
//            System.loadLibrary("kernel32")
//            Socket().close()
            Arena.ofConfined().use { arena ->
                val k32 = SymbolLookup.loaderLookup() //("kernel32", arena);
                val linker: Linker = Linker.nativeLinker()
//                IOException
                _GetLastError = runCatching {
                    linker.downcallHandle(
                        k32.findOrThrow("GetLastError"),
                        FunctionDescriptor.of(
                            JAVA_INT
                        )
                    )
                }.also { it.exceptionOrNull()?.printStackTrace() }.getOrNull()

//                _FormatMessage = runCatching {
//                    linker.downcallHandle(
//                        k32.findOrThrow("FormatMessage"),
//                        FunctionDescriptor.of(
//                            JAVA_INT,
//                            ADDRESS,
//                            JAVA_INT,
//                            JAVA_INT,
//                            ADDRESS,
//                            JAVA_INT,
//
//                        )
//                    )
//                }
                _CreateIoCompletionPort = runCatching {
                    linker.downcallHandle(
                        k32.find("CreateIoCompletionPort").orElseThrow(),
                        FunctionDescriptor.of(
                            HANDLE,   // return HANDLE
                            HANDLE,   // FileHandle
                            HANDLE,   // ExistingCompletionPort
                            ULONG_PTR,// CompletionKey
                            JAVA_INT  // NumberOfConcurrentThreads (DWORD)
                        )
                    );
                }.getOrNull()

                _GetQueuedCompletionStatus = runCatching {
                    linker.downcallHandle(
                        k32.find("GetQueuedCompletionStatus").orElseThrow(),
                        FunctionDescriptor.of(
                            JAVA_INT,      // BOOL
                            HANDLE,        // CompletionPort
                            ADDRESS,       // LPDWORD lpNumberOfBytesTransferred
                            ADDRESS,       // PULONG_PTR lpCompletionKey
                            ADDRESS,       // LPOVERLAPPED* lpOverlapped
                            JAVA_INT       // dwMilliseconds
                        )
                    );
                }.getOrNull()

                _PostQueuedCompletionStatus = runCatching {
                    linker.downcallHandle(
                        k32.find("PostQueuedCompletionStatus").orElseThrow(),
                        FunctionDescriptor.of(
                            JAVA_INT,      // BOOL
                            HANDLE,        // CompletionPort
                            JAVA_INT,      // dwNumberOfBytesTransferred (DWORD)
                            ULONG_PTR,     // dwCompletionKey (ULONG_PTR)
                            LPOVERLAPPED   // lpOverlapped
                        )
                    );
                }.getOrNull()

                _CloseHandle = runCatching {
                    linker.downcallHandle(
                        k32.find("CloseHandle").orElseThrow(),
                        FunctionDescriptor.of(
                            JAVA_INT, // BOOL
                            HANDLE
                        )
                    );
                }.getOrNull()


            }


        }


        fun CreateIoCompletionPort(
            FileHandle: MemorySegment,
            ExistingCompletionPort: MemorySegment?,
            CompletionKey:MemorySegment,
            NumberOfConcurrentThreads:Int = 0
        ) {
            val port = _CreateIoCompletionPort!!.invokeExact(
                FileHandle,
                ExistingCompletionPort,
                CompletionKey,
                NumberOfConcurrentThreads,
            )
            if (port == NULL) {

            }
        }
    }
}
