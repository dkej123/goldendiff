package com.github.dkwasniak.goldendiff.app.mac

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Minimal Objective-C runtime bridge.
 *
 * Only what [MacUserNotifications] needs: sending messages, bridging strings, synthesising blocks and
 * defining one delegate class. Everything here is 64-bit macOS only and is never touched on other
 * platforms — callers guard on [com.github.dkwasniak.goldendiff.platform.Os].
 *
 * Why JNA rather than the FFM API: `:app` is on JDK 21, where FFM is still a preview feature. Preview
 * classfiles are pinned to the exact JDK that produced them, which does not survive a `jpackage`
 * build. JNA needs no flags and the app already ships its own runtime.
 */
internal object ObjC {
    private val runtime = NativeLibrary.getInstance("objc")
    private val system = NativeLibrary.getInstance("System")

    private val msgSend = runtime.getFunction("objc_msgSend")
    private val getClass = runtime.getFunction("objc_getClass")
    private val registerName = runtime.getFunction("sel_registerName")
    private val allocateClassPair = runtime.getFunction("objc_allocateClassPair")
    private val registerClassPair = runtime.getFunction("objc_registerClassPair")
    private val addMethod = runtime.getFunction("class_addMethod")

    /** Interned by the runtime, so caching them is just avoiding repeated JNA hops. */
    private val selectors = HashMap<String, Pointer>()
    private val classes = HashMap<String, Pointer?>()

    /** Blocks and synthesised classes must outlive every call the runtime may make into them. */
    private val retained = mutableListOf<Any>()

    fun loadFramework(path: String): Boolean = runCatching { NativeLibrary.getInstance(path) }.isSuccess

    /** Null when the class is not registered — i.e. its framework was never loaded. */
    @Synchronized
    fun classNamed(name: String): Pointer? =
        classes.getOrPut(name) { getClass.invokePointer(arrayOf(name)) }

    @Synchronized
    fun selector(name: String): Pointer =
        selectors.getOrPut(name) { registerName.invokePointer(arrayOf(name)) }

    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? {
        if (receiver == null) return null
        return msgSend.invokePointer(arrayOf(receiver, selector(selector), *args))
    }

    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long {
        if (receiver == null) return 0
        return msgSend.invokeLong(arrayOf(receiver, selector(selector), *args))
    }

    fun sendVoid(receiver: Pointer?, selector: String, vararg args: Any?) {
        if (receiver == null) return
        msgSend.invokeVoid(arrayOf(receiver, selector(selector), *args))
    }

    /** Autoreleased `NSString`; valid for the duration of the call it is passed to. */
    fun nsString(value: String): Pointer? =
        send(classNamed("NSString"), "stringWithUTF8String:", Native.toByteArray(value, "UTF-8"))

    fun stringFrom(nsString: Pointer?): String? =
        send(nsString, "UTF8String")?.getString(0)

    /**
     * Wraps [callback] in a global Objective-C block.
     *
     * A block is an isa pointer, flags, an invoke function pointer and a descriptor. Marking it global
     * means the runtime never copies or frees it, which is what lets a JNA callback stand in for one —
     * the JVM side owns the lifetime instead (see [retained]).
     */
    @Synchronized
    fun block(callback: Callback, signature: String): Pointer {
        // reserved (8) + size (8) + signature pointer (8).
        val signatureText = Memory((signature.length + 1).toLong()).apply { setString(0, signature) }
        val descriptor = Memory(24).apply {
            setLong(0, 0)
            setLong(8, BLOCK_SIZE.toLong())
            setPointer(16, signatureText)
        }
        val literal = Memory(BLOCK_SIZE.toLong()).apply {
            setPointer(0, system.getGlobalVariableAddress("_NSConcreteGlobalBlock"))
            setInt(8, BLOCK_IS_GLOBAL or BLOCK_HAS_SIGNATURE)
            setInt(12, 0)
            setPointer(16, CallbackReference.getFunctionPointer(callback))
            setPointer(24, descriptor)
        }
        retained.addAll(listOf(callback, signatureText, descriptor, literal))
        return literal
    }

    /**
     * Invokes a block handed to us by the runtime — used to satisfy AppKit completion handlers.
     *
     * [args] are appended after the block itself, which is always the hidden first parameter.
     */
    fun invokeBlock(block: Pointer?, vararg args: Any?) {
        if (block == null || Pointer.nativeValue(block) == 0L) return
        // Field order is fixed by the ABI: invoke lives one isa + flags + reserved in.
        val invoke = block.getPointer(16) ?: return
        Function.getFunction(invoke).invokeVoid(arrayOf(block, *args))
    }

    /**
     * Registers a subclass of `NSObject` with [methods] attached, and returns one instance of it.
     *
     * Returns null if the name is already taken, which only happens if this is called twice — the
     * caller keeps the instance instead of asking again.
     */
    @Synchronized
    fun defineDelegate(name: String, methods: Map<String, Pair<Callback, String>>): Pointer? {
        val superclass = classNamed("NSObject") ?: return null
        val created = allocateClassPair.invokePointer(arrayOf(superclass, name, 0.toLong()))
        if (created == null || Pointer.nativeValue(created) == 0L) return null
        methods.forEach { (selectorName, method) ->
            val (callback, signature) = method
            retained += callback
            addMethod.invokeInt(
                arrayOf(
                    created,
                    selector(selectorName),
                    CallbackReference.getFunctionPointer(callback),
                    signature,
                ),
            )
        }
        registerClassPair.invokeVoid(arrayOf(created))
        val instance = send(send(created, "alloc"), "init")
        instance?.let { retained += it }
        return instance
    }

    private const val BLOCK_SIZE = 32
    private const val BLOCK_IS_GLOBAL = 1 shl 28
    private const val BLOCK_HAS_SIGNATURE = 1 shl 30
}
