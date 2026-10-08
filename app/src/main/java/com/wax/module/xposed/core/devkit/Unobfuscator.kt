package com.wax.module.xposed.core.devkit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.SensorEventListener
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.base.OpCodesMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.util.DexSignUtil
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Arrays
import java.util.Collections
import java.util.Date
import java.util.Objects
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

object Unobfuscator {
    private lateinit var bridge: DexKitBridge

    val cacheClasses = ConcurrentHashMap<String, Class<*>>()

    init {
        System.loadLibrary("dexkit")
    }

    /**
     * Whether the engine actually started.
     *
     * A capability provider needs to answer this, and it is the difference between "this WhatsApp
     * build does not have that class" and "the engine never came up". The engine failing to
     * initialise is recorded against the DEXKIT subsystem with its own failure code, so nothing
     * downstream has to infer it from the absence of a lookup result.
     */
    @JvmStatic
    fun isInitialised(): Boolean = ::bridge.isInitialized

    /**
     * Replaces a `!!` on a DexKit class lookup with a failure that names the resolver.
     *
     * A bare `!!` here surfaced as a null-cast NullPointerException with no indication of
     * which resolver broke, which is precisely the failure mode T15 has to explain to a
     * user. The control flow is unchanged — a missing class still throws — but the message
     * now names both the resolver and what it was looking for.
     *
     * This is a step towards the typed [com.wax.module.resolver.Resolution] results
     * rather than the end state: the caller still receives a thrown exception, so the
     * migration to typed results stays incremental.
     */
    private fun requireClass(
        resolver: String,
        hint: String,
        found: Class<*>?,
    ): Class<*> = found ?: throw ClassNotFoundException("$resolver: no class matched [$hint] on this WhatsApp build")

    /**
     * The method counterpart of [requireClass].
     *
     * Separate from [requireClass] so the message says which of the two failed: a missing
     * method and a missing class send the user to different places when a WhatsApp update
     * renames internals.
     */
    private fun requireMethod(
        resolver: String,
        hint: String,
        found: Method?,
    ): Method = found ?: throw NoSuchMethodException("$resolver: no method matched [$hint] on this WhatsApp build")

    @JvmStatic
    fun initWithPath(path: String): Boolean =
        try {
            bridge = DexKitBridge.create(path)
            true
        } catch (_: Exception) {
            false
        }

    /**
     * A successful lookup plus the candidate anchor that produced it.
     *
     * Reported so a caller can say *which* known WhatsApp generation matched, which
     * is what turns "HD Status is not working" into a one-line answer.
     */
    data class AnchorMatch<out T>(
        val value: T,
        val anchor: String,
    )

    /**
     * Tries each candidate anchor on its own and returns the first hit.
     *
     * [findFirstMethodUsingStrings] combines every string it is given into a single
     * AND matcher, so passing three alternatives finds a method containing all
     * three, which never matches. Each candidate must therefore be a separate
     * lookup, tried in the given order. The first candidate is the most specific
     * one for the current WhatsApp generation.
     */
    @JvmStatic
    fun findMethodByAnyAnchor(
        classLoader: ClassLoader,
        type: StringMatchType,
        candidates: List<String>,
        returnType: Class<*>? = null,
    ): AnchorMatch<Method>? {
        for (candidate in candidates) {
            val hit = findFirstMethodUsingStrings(classLoader, type, candidate) ?: continue
            if (returnType != null && hit.returnType != returnType) continue
            return AnchorMatch(hit, candidate)
        }
        return null
    }

    /** The [findMethodByAnyAnchor] counterpart for classes. */
    @JvmStatic
    fun findClassByAnyAnchor(
        classLoader: ClassLoader,
        type: StringMatchType,
        candidates: List<String>,
    ): AnchorMatch<Class<*>>? {
        for (candidate in candidates) {
            val hit = findFirstClassUsingStrings(classLoader, type, candidate) ?: continue
            return AnchorMatch(hit, candidate)
        }
        return null
    }

    /**
     * Resolves a method from an ordered candidate list, failing loudly.
     *
     * The error names the resolver and lists every anchor tried, because a
     * `NoSuchMethodException` with no detail is what made the previous HD Status
     * breakage undiagnosable from a logcat.
     */
    @Throws(NoSuchMethodException::class)
    @JvmStatic
    fun requireMethodByAnyAnchor(
        resolver: String,
        classLoader: ClassLoader,
        type: StringMatchType,
        candidates: List<String>,
        returnType: Class<*>? = null,
    ): Method =
        findMethodByAnyAnchor(classLoader, type, candidates, returnType)?.value
            ?: throw NoSuchMethodException("$resolver: no method matched any of ${candidates.joinToString(", ")}")

    /** The [requireMethodByAnyAnchor] counterpart for classes. */
    @Throws(ClassNotFoundException::class)
    @JvmStatic
    fun requireClassByAnyAnchor(
        resolver: String,
        classLoader: ClassLoader,
        type: StringMatchType,
        candidates: List<String>,
    ): Class<*> =
        findClassByAnyAnchor(classLoader, type, candidates)?.value
            ?: throw ClassNotFoundException("$resolver: no class matched any of ${candidates.joinToString(", ")}")

    @JvmStatic
    fun findFirstMethodUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String,
    ): Method? {
        val result =
            bridge.findMethod {
                matcher {
                    for (string in strings) {
                        addUsingString(string, type)
                    }
                }
            }
        if (result.isEmpty()) return null
        for (methodData in result) {
            if (methodData.isMethod) return methodData.getMethodInstance(classLoader)
        }
        return null
    }

    @Throws(Exception::class)
    @JvmStatic
    fun findFirstMethodUsingStringsFilter(
        classLoader: ClassLoader,
        packageFilter: String,
        type: StringMatchType,
        vararg strings: String,
    ): Method? {
        val result =
            bridge.findMethod {
                searchPackages(packageFilter)
                matcher {
                    for (string in strings) {
                        addUsingString(string, type)
                    }
                }
            }
        if (result.isEmpty()) return null

        for (methodData in result) {
            if (methodData.isMethod) return methodData.getMethodInstance(classLoader)
        }
        throw NoSuchMethodException()
    }

    @JvmStatic
    fun findAllMethodUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String,
    ): Array<Method> {
        val result =
            bridge.findMethod {
                matcher {
                    for (string in strings) {
                        addUsingString(string, type)
                    }
                }
            }
        if (result.isEmpty()) return emptyArray()
        return result
            .stream()
            .filter { it.isMethod }
            .map { methodData -> convertRealMethod(methodData, classLoader) }
            .filter { it != null }
            .map { it!! }
            .toArray { length -> arrayOfNulls<Method>(length) }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun findFirstClassUsingStrings(
        classLoader: ClassLoader,
        type: StringMatchType,
        vararg strings: String,
    ): Class<Any>? {
        val result =
            bridge.findClass {
                matcher {
                    for (string in strings) {
                        addUsingString(string, type)
                    }
                }
            }
        if (result.isEmpty()) return null
        @Suppress("UNCHECKED_CAST")
        return result[0].getInstance(classLoader) as Class<Any>
    }

    @Throws(Exception::class)
    @JvmStatic
    fun findFirstClassUsingName(
        classLoader: ClassLoader,
        type: StringMatchType,
        name: String,
    ): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader, name) {
            val result =
                bridge
                    .findClass {
                        matcher {
                            className(name, type)
                        }
                    }.firstOrNull() ?: throw ClassNotFoundException("Class not found: $name")
            result.getInstance(classLoader)
        }

    @JvmStatic
    fun getMethodDescriptor(method: Method?): String? {
        if (method == null) return null
        return method.declaringClass.name + "->" + method.name + "(" +
            Arrays
                .stream(method.parameterTypes)
                .map { it.name }
                .collect(Collectors.joining(",")) + ")"
    }

    @JvmStatic
    fun getFieldDescriptor(field: Field): String = field.declaringClass.name + "->" + field.name + ":" + field.type.name

    @JvmStatic
    fun convertRealMethod(
        methodData: MethodData,
        classLoader: ClassLoader,
    ): Method? =
        try {
            methodData.getMethodInstance(classLoader)
        } catch (_: Exception) {
            null
        }

    @JvmStatic
    fun convertRealClass(
        classData: ClassData,
        classLoader: ClassLoader,
    ): Class<*>? =
        try {
            classData.getInstance(classLoader)
        } catch (_: Exception) {
            null
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFreezeSeenMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            UnobfuscatorCache.getInstance().getMethod(classLoader) {
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "presencestatemanager/setAvailable/new-state",
                )
            }
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGhostModeMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "HandleMeComposing/sendComposing",
                )
                    ?: error("GhostMode method not found")
            if (method.parameterTypes.size > 2 && method.parameterTypes[2] == Int::class.java) {
                return@getMethod method
            }
            error("GhostMode method not found parameter type")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSharedMessageProcessorHandlePlaintextMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "SharedMessageProcessor/handlePlaintext",
            ) ?: throw NoSuchMethodException("SharedMessageProcessor/handlePlaintext method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadReceiptMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classDeviceJid =
                findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid")
            val classProtocolTreeNode =
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "ProtocolTreeNode/getAttributeJid",
                )

            val methods =
                bridge.findMethod {
                    matcher {
                        addUsingString("receipt")
                        returnType(classProtocolTreeNode!!)
                    }
                }

            for (method in methods) {
                val params = method.paramTypeNames
                val hasRequiredParams = params.contains(classDeviceJid.name)
                if (!hasRequiredParams) continue

                return@getMethod method.getMethodInstance(classLoader)
            }

            throw NoSuchMethodError(
                "Receipt method not found. returnType=" + classProtocolTreeNode?.name +
                    ", requiredParams=[" + classDeviceJid.name + "]",
            )
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadReceiptMessageInfoClass(classLoader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(classLoader) {
            val methodData =
                bridge
                    .findMethod {
                        matcher {
                            addUsingString("ReadReceiptUtils/buildReadReceiptHandler malformed")
                        }
                    }.single()
            val deviceJid =
                findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "jid.DeviceJid")
            for (invoke in methodData.invokes) {
                if (invoke.isConstructor && invoke.paramTypeNames.contains(deviceJid.name)) {
                    return@getClass invoke.getClassInstance(classLoader)
                }
            }
            null
        }
    }

    fun loadForwardTagMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val messageInfoClass = loadFMessageClass(classLoader)
            val methodList =
                bridge.findMethod {
                    matcher {
                        addUsingString("chatInfo/incrementUnseenImportantMessageCount")
                    }
                }
            if (methodList.isEmpty()) error("ForwardTag method support not found")
            val invokes = methodList[0].invokes
            for (invoke in invokes) {
                val method = invoke.getMethodInstance(classLoader)
                if (method.parameterCount == 1 &&
                    (method.parameterTypes[0] == Int::class.java || method.parameterTypes[0] == Long::class.java) &&
                    method.declaringClass == messageInfoClass &&
                    method.returnType == Void.TYPE
                ) {
                    return@getMethod method
                }
            }
            error("ForwardTag method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBroadcastTagField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val fmessage = loadFMessageClass(classLoader)
            val clazzData =
                bridge.findClass {
                    matcher {
                        addUsingString("UPDATE_MESSAGE_MAIN_BROADCAST_SCAN_SQL")
                    }
                }
            if (clazzData.isEmpty()) error("BroadcastTag class not found")

            var methodData =
                bridge.findMethod {
                    searchInClass(clazzData)
                    matcher {
                        usingStrings("participant_hash", "view_mode", "broadcast")
                    }
                }

            if (methodData.isEmpty()) {
                methodData =
                    bridge.findMethod {
                        searchInClass(clazzData)
                        matcher {
                            usingStrings("received_timestamp", "view_mode", "message")
                        }
                    }
                if (!methodData.isEmpty()) {
                    val calledMethods = methodData[0].invokes
                    for (cmethod in calledMethods) {
                        if (Modifier.isStatic(cmethod.modifiers) &&
                            cmethod.paramCount == 2 &&
                            fmessage.name == cmethod.declaredClass?.name
                        ) {
                            val pTypes = cmethod.paramTypes
                            if (pTypes[0].name == ContentValues::class.java.name && pTypes[1].name == fmessage.name) {
                                methodData.clear()
                                methodData.add(cmethod)
                                break
                            }
                        }
                    }
                }
            }

            if (methodData.isEmpty()) error("BroadcastTag method support not found")
            val usingFields = methodData[0].usingFields
            for (ufield in usingFields) {
                val field = ufield.field
                if (field.declaredClass.name == fmessage.name && field.type.name == Boolean::class.java.name) {
                    return@getField field.getFieldInstance(classLoader)
                }
            }
            error("BroadcastTag field not found")
        }
    }

    fun loadForwardClassMethod(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        anyOf {
                            match {
                                usingStrings("UserActions/userActionForwardMessage")
                            }
                            match {
                                usingStrings("UserActionsMessageForwarding/userActionForwardMessage")
                            }
                        }
                    }
                }.firstOrNull()
                ?.getInstance(classLoader)
                ?: throw ClassNotFoundException("ForwardClass method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadHideViewSendReadJob(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classData =
                bridge.getClassData(
                    findFirstClassUsingName(
                        classLoader,
                        StringMatchType.EndsWith,
                        "SendReadReceiptJob",
                    ),
                )
            var methodResult =
                classData!!.findMethod {
                    matcher {
                        addUsingString("receipt", StringMatchType.Equals)
                    }
                }
            if (methodResult.isEmpty()) {
                methodResult =
                    classData.superClass!!.findMethod {
                        matcher {
                            addUsingString("receipt", StringMatchType.Equals)
                        }
                    }
            }
            if (methodResult.isEmpty()) error("HideViewSendReadJob method not found")
            methodResult[0].getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFMessageClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "FMessage/getSenderUserJid/key.id",
            )
                ?: error("Message class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTabListMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val result =
                bridge
                    .findMethod {
                        matcher {
                            addUsingNumber(200)
                            addUsingNumber(300)
                            returnType(ArrayList::class.java)
                        }
                    }.singleOrNull() ?: error("TabList method not found")
            result.getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetTabMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStringsFilter(
                classLoader,
                "X.",
                StringMatchType.Contains,
                "No HomeFragment mapping for community tab id:",
            )
                ?: error("GetTab method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTabFragmentMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val clsFrag =
                XposedHelpers.findClass(
                    "com.whatsapp.conversationslist.ConversationsFragment",
                    classLoader,
                )
            Arrays
                .stream(clsFrag.declaredMethods)
                .parallel()
                .filter { m -> m.parameterTypes.isEmpty() && m.returnType == MutableList::class.java }
                .findFirst()
                .orElse(null) ?: error("TabFragment method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTabNameMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val id = UnobfuscatorCache.getInstance().getOfuscateIDString("updates")
            if (id < 1) error("TabName ID not found")
            val result =
                bridge.findMethod {
                    matcher {
                        returnType(String::class.java)
                        usingNumbers(id)
                    }
                }
            if (result.isEmpty()) error("TabName method not found")
            result[0].getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFabMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classData =
                bridge.getClassData("com.whatsapp.conversationslist.ConversationsFragment")
            Objects.requireNonNull(classData)
            for (clazz in listOf(classData, classData!!.superClass)) {
                val result =
                    clazz
                        ?.findMethod {
                            matcher {
                                paramCount(0)
                                usingNumbers(200)
                                returnType(Int::class.java)
                            }
                        }?.firstOrNull()
                if (result != null) return@getMethod result.getMethodInstance(classLoader)
            }
            error("Fab method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadIconTabMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val id1 = Utils.getID("home_tab_communities_selector", "drawable")
            val id2 = Utils.getID("home_tab_calls_selector", "drawable")
            val id3 = Utils.getID("home_tab_chats_selector", "drawable")

            val methodData =
                bridge
                    .findMethod {
                        searchPackages("X.")
                        matcher {
                            addUsingNumber(id1)
                            addUsingNumber(id2)
                            addUsingNumber(id3)
                        }
                    }.singleOrNull() ?: error("IconTab method not found")
            methodData.getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTabCountMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "required free space should be > 0",
            )
                ?: error("TabCount method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadEnableCountTabMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "Tried to set badge for invalid",
            )
                ?: error("EnableCountTab method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadEnableCountTabBadgeWrapper(classLoader: ClassLoader): Constructor<*> {
        return UnobfuscatorCache.getInstance().getConstructor(classLoader) {
            val countMethod = loadEnableCountTabMethod(classLoader)
            val indiceClass = countMethod.parameterTypes[1]
            val result =
                bridge.findClass {
                    matcher {
                        superClass = indiceClass.name
                        methods {
                            add {
                                name = "<init>"
                                paramCount(1, 2)
                            }
                        }
                    }
                }
            if (result.isEmpty()) error("EnableCountTabBadgeWrapper method not found")
            return@getConstructor result[0].getInstance(classLoader).constructors[0]
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadEnableCountTabBadgeItem(classLoader: ClassLoader): Constructor<*> =
        UnobfuscatorCache.getInstance().getConstructor(classLoader) {
            val countTabConstructor1 = loadEnableCountTabBadgeWrapper(classLoader)
            val indiceClass = countTabConstructor1.parameterTypes[0]
            val result =
                bridge.findClass {
                    matcher {
                        superClass(indiceClass.name)
                        addMethod {
                            paramCount(1)
                            addParamType(Int::class.java)
                        }
                    }
                }
            if (result.isEmpty()) error("EnableCountTab method not found")
            result[0].getInstance(classLoader).constructors[0]
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadEnableCountTabEmptyBadgeClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            val countMethod = loadEnableCountTabMethod(classLoader)
            val indiceClass = countMethod.parameterTypes[1]
            val result =
                bridge.findClass {
                    matcher {
                        superClass(indiceClass.name)
                        addMethod {
                            paramCount(0)
                        }
                    }
                }
            if (result.isEmpty()) error("EnableCountTab method not found")
            result[0].getInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTimeToSecondsMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            bridge
                .findMethod {
                    matcher {
                        usingNumbers(223, 224)
                        modifiers = Modifier.STATIC
                        returnType = "java.lang.String"
                        paramCount = 2
                        paramTypes(null, "java.util.Calendar")
                    }
                }.single()
                .getMethodInstance(classLoader)
        }

    fun loadDndModeMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(classLoader, StringMatchType.Equals, "MessageHandler/start")
                ?: error("DndMode method not found")
        }

    fun loadProcessVideoQualityClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClassByAnyAnchor(
                "loadProcessVideoQualityClass",
                classLoader,
                StringMatchType.StartsWith,
                listOf("ProcessVideoQuality(", "VideoQuality(", "MediaQualityLimits("),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMenuManagerClass(classLoader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(classLoader) {
            val methods =
                findAllMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "MenuPopupHelper cannot be used without an anchor",
                )
            for (method in methods) {
                if (method.returnType == Void.TYPE) return@getClass method.declaringClass
            }
            error("MenuManager class not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMenuStatusMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val id = Utils.getID("menuitem_conversations_message_contact", "id")
            val methods =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(id)
                    }
                }
            if (methods.isEmpty()) error("MenuStatus method not found")
            methods[0].getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadViewOnceMethod(classLoader: ClassLoader): Array<Method> {
        return UnobfuscatorCache.getInstance().getMethods(classLoader) {
            val method =
                bridge.findMethod {
                    matcher {
                        addUsingString("INSERT_VIEW_ONCE_SQL", StringMatchType.Contains)
                    }
                }
            if (method.isEmpty()) error("ViewOnce method not found")
            val methodData = method[0]
            val listMethods = methodData.invokes
            val list = ArrayList<Method>()
            for (m in listMethods) {
                val mInstance = m.getMethodInstance(classLoader)
                if (mInstance.declaringClass.isInterface && mInstance.declaringClass.methods.size == 2) {
                    val listClasses =
                        bridge.findClass {
                            matcher {
                                addInterface(mInstance.declaringClass.name)
                            }
                        }
                    for (c in listClasses) {
                        val clazz = c.getInstance(classLoader)
                        for (m2 in clazz.declaredMethods) {
                            if (m2.parameterCount != 1 ||
                                m2.parameterTypes[0] != Int::class.javaPrimitiveType ||
                                m2.returnType != Void.TYPE
                            ) {
                                continue
                            }
                            list.add(m2)
                        }
                    }
                    if (list.isEmpty()) error("ViewOnce method not found")
                    return@getMethods list.toTypedArray()
                }
            }
            error("ViewOnce method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadViewOnceDownloadMenuMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val id1 = Utils.getID("ic_viewonce", "drawable")
            val setShowAsAction =
                MenuItem::class.java.getDeclaredMethod(
                    "setShowAsAction",
                    Int::class.javaPrimitiveType,
                )
            val methodData =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(id1)
                        addInvoke(DexSignUtil.getMethodDescriptor(setShowAsAction))
                    }
                }
            val result =
                methodData
                    .stream()
                    .filter { m ->
                        m.paramCount > 1 && m.paramTypeNames.contains(Menu::class.java.name)
                    }.findFirst()
            if (!result.isPresent) error("ViewOnceDownloadMenu method not found")
            result.get().getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMaterialShapeDrawableClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(
                loader,
                StringMatchType.Contains,
                "Compatibility shadow requested",
            )
                ?: error("MaterialShapeDrawable class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPropsBooleanMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(loader, StringMatchType.Contains, "Unknown BooleanField")
                ?: error("Props method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPropsIntegerMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(loader, StringMatchType.Contains, "Unknown IntField")
                ?: error("Props method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadHomeConversationFragmentMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val homeClass = ModuleRuntime.homeActivityClass
            val convFragment =
                findFirstClassUsingName(loader, StringMatchType.EndsWith, ".ConversationFragment")
            val method =
                bridge
                    .findMethod {
                        searchInClass(Collections.singletonList(bridge.getClassData(homeClass)))
                        matcher {
                            returnType(convFragment)
                        }
                    }.singleOrNull() ?: error("HomeConversationFragmentMethod not found")
            method.getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAntiRevokeConvFragmentField(loader: ClassLoader): Field =
        UnobfuscatorCache.getInstance().getField(loader) {
            val chatClass =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "conversation/createconversation",
                )
            val conversation =
                findFirstClassUsingName(loader, StringMatchType.EndsWith, ".ConversationFragment")
            ReflectionUtils.getFieldByType(conversation, chatClass)
                ?: error("AntiRevokeConvChat field not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadUserJidConversationDelegate(loader: ClassLoader): Field =
        UnobfuscatorCache.getInstance().getField(loader) {
            val chatClass =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "conversation/createconversation",
                )
            val jidClass = findFirstClassUsingName(loader, StringMatchType.EndsWith, "jid.Jid")
            ReflectionUtils.getFieldByExtendType(chatClass, jidClass)
                ?: error("UserJidConversationDelegate field not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAntiRevokeMessageMethod(loader: ClassLoader): Method? {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            for (s in listOf("msgstore/edit/revoke", "msgstore/revoking/")) {
                val method = findFirstMethodUsingStrings(loader, StringMatchType.Contains, s)
                if (method != null) return@getMethod method
            }
            null
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMessageKeyField(loader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(loader) {
            val classList =
                bridge.findClass {
                    matcher {
                        fieldCount(3)
                        addMethod {
                            addUsingString("Key")
                            name("toString")
                        }
                    }
                }
            if (classList.isEmpty()) error("MessageKey class not found")
            for (classData in classList) {
                val keyMessageClass = classData.getInstance(loader)
                val classMessage = loadFMessageClass(loader)
                val fields = ReflectionUtils.getFieldsByExtendType(classMessage, keyMessageClass)
                if (fields.isEmpty()) continue
                return@getField fields[fields.size - 1]
            }
            error("MessageKey field not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadConversationRowClass(loader: ClassLoader): Class<*>? {
        return UnobfuscatorCache.getInstance().getClass(loader) {
            val clazz =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "ConversationRow/setupUserNameInGroupView/",
                )
            if (clazz != null) return@getClass clazz
            val conversationHeader = Utils.getID("name_in_group", "id")
            val classData =
                bridge.findClass {
                    matcher {
                        addMethod {
                            addUsingNumber(conversationHeader)
                        }
                    }
                }
            for (c in classData) {
                val clazzInstance = c.getInstance(loader)
                if (ViewGroup::class.java.isAssignableFrom(clazzInstance)) return@getClass clazzInstance
            }
            null
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadUnknownStatusPlaybackMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val statusPlaybackClass =
                XposedHelpers.findClass(
                    "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment",
                    loader,
                )
            val refreshCurrentPage =
                bridge.findMethod {
                    matcher {
                        addUsingString("playbackFragment/refreshCurrentPageSubTitle message is empty")
                    }
                }[0]
            val invokes = refreshCurrentPage.invokes
            for (invoke in invokes) {
                val method = invoke.getMethodInstance(loader)
                if (Modifier.isStatic(method.modifiers) &&
                    method.parameterCount > 1 &&
                    listOf(*method.parameterTypes).contains(statusPlaybackClass) &&
                    method.declaringClass === statusPlaybackClass
                ) {
                    return@getMethod method
                }
            }
            error("UnknownStatusPlayback method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusPlaybackViewClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            val ids = listOf(Utils.getID("status_header", "id"), Utils.getID("menu", "id"))
            val clazz =
                bridge.findClass {
                    matcher {
                        addMethod {
                            usingNumbers(ids)
                        }
                    }
                }
            if (clazz.isEmpty()) error("Not Found StatusPlaybackViewClass")
            clazz[0].getInstance(loader)
        }

    fun loadBlueOnReplayMessageJobMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            bridge
                .findMethod {
                    matcher {
                        paramCount(0)
                        usingStrings("SendE2EMessageJob/onRun")
                    }
                }.firstOrNull()
                ?.getMethodInstance(classLoader)
                ?: error("BlueOnReplayMessageJob method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBlueOnReplayWaJobManagerMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val result =
                findFirstClassUsingStrings(loader, StringMatchType.Contains, "WaJobManager/start")
                    ?: error("BlueOnReplayWaJobManager method not found")
            val job = XposedHelpers.findClass("org.whispersystems.jobqueue.Job", loader)
            Arrays
                .stream(result.methods)
                .filter { m -> m.parameterCount == 1 && m.parameterTypes[0] === job }
                .findFirst()
                .orElse(null) ?: error("BlueOnReplayWaJobManager method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadArchiveChatClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            var clazz =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "archive/set-content-indicator-to-empty",
                )
            if (clazz == null) {
                clazz =
                    findFirstClassUsingStrings(
                        loader,
                        StringMatchType.Contains,
                        "archive/Unsupported mode in ArchivePreviewView:",
                    )
            }
            clazz ?: error("ArchiveHideView method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAntiRevokeOnCallReceivedMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "voip/callStateChangedOnUIThread",
            )
                ?: error("OnCallReceiver method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnChangeStatus(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            var method =
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "ConversationViewFiller/setParentGroupProfilePhoto",
                )
                    ?: error("OnChangeStatus method not found")

            if (method.parameterCount < 6) {
                val declaringClassData =
                    bridge.getClassData(method.declaringClass)
                        ?: error("OnChangeStatus method not found")

                val arg1Class = loadWaContactClass(loader)
                val methodData =
                    declaringClassData.findMethod {
                        matcher {
                            paramCount(6, 8)
                        }
                    }

                for (methodItem in methodData) {
                    val paramTypes = methodItem.paramTypes
                    if (paramTypes[0].getInstance(loader) === arg1Class &&
                        paramTypes[1].getInstance(
                            loader,
                        ) === arg1Class
                    ) {
                        method = methodItem.getMethodInstance(loader)
                        break
                    }
                }
            }
            method
        }

    @JvmStatic
    @Throws(Exception::class)
    fun loadViewHolder(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            bridge
                .findMethod {
                    matcher {
                        usingNumbers(
                            Utils.getID("conversations_row_header_stub", "id"),
                            Utils.getID("pin_indicator", "id"),
                            Utils.getID("mute_indicator", "id"),
                            Utils.getID("contact_photo", "id"),
                        )
                    }
                }.firstOrNull { methodData ->
                    methodData.paramTypes.isNotEmpty() &&
                        methodData.paramTypes[0].name == Context::class.java.name
                }?.getClassInstance(loader) ?: throw ClassNotFoundException("View Holder not found!")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadViewHolderField1(loader: ClassLoader): Field =
        UnobfuscatorCache.getInstance().getField(loader) {
            val class1 = loadOnChangeStatus(loader).declaringClass.superclass
            ReflectionUtils.getFieldByType(class1, loadViewHolder(loader))
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusUserMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val id = UnobfuscatorCache.getInstance().getOfuscateIDString("lastseensun%s")
            if (id < 1) error("GetStatusUser ID not found")
            val result =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(id)
                        returnType(String::class.java)
                    }
                }
            if (result.isEmpty()) error("GetStatusUser method not found")
            result[result.size - 1].getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSendPresenceMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val methodData =
                bridge.findMethod {
                    matcher {
                        addUsingString("app/send-presence-subscription jid=")
                    }
                }
            if (methodData.isEmpty()) error("SendPresence method not found")
            var methodCallers = methodData[0].callers
            if (methodCallers.isEmpty()) {
                val method = methodData[0]
                val superMethodInterfaces = method.declaredClass!!.interfaces
                if (superMethodInterfaces.isEmpty()) error("SendPresence method interface list empty")
                val superMethod =
                    superMethodInterfaces[0]
                        .findMethod {
                            matcher {
                                name(method.name)
                            }
                        }.firstOrNull() ?: error("SendPresence method interface method not found")
                methodCallers = superMethod.callers
            }
            val newMethod =
                methodCallers.firstOrNull { it.paramCount == 4 }
                    ?: error("SendPresence method not found 2")
            newMethod.getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPinnedHashSetMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "getPinnedJids/QUERY_CHAT_SETTINGS",
            )
                ?: error("PinnedHashSet method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetFiltersMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val clazzFilters =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "conversations/filter/performFiltering",
                )
                    ?: error("Filters class not found")
            Arrays
                .stream(clazzFilters.declaredMethods)
                .parallel()
                .filter { m -> m.name == "publishResults" }
                .findFirst()
                .orElse(null)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPinnedInChatMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val method =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(3732)
                        returnType(Int::class.java)
                    }
                }
            if (method.isEmpty()) error("PinnedInChat method not found")
            method[0].getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBlueOnReplayViewButtonMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "PLAYBACK_PAGE_ITEM_ON_CREATE_VIEW_END",
            )
                ?: error("BlueOnReplayViewButton method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBlueOnReplayStatusViewMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "StatusPlaybackPage/onViewCreated",
            )
                ?: error("BlueOnReplayViewButton method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadChatLimitDeleteMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val clazz =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "app/time server update processed",
                )
                    ?: error("ChatLimitDelete class not found")
            var method =
                Arrays
                    .stream(clazz.declaredMethods)
                    .filter { m -> m.returnType == Long::class.javaPrimitiveType && Modifier.isStatic(m.modifiers) }
                    .findFirst()
                    .orElse(null)
            if (method == null) {
                val methodList =
                    bridge.getClassData(clazz)?.findMethod {
                        matcher {
                            opCodes(
                                OpCodesMatcher().opNames(
                                    listOf(
                                        "invoke-static",
                                        "move-result-wide",
                                        "iget-wide",
                                        "const-wide/16",
                                        "cmp-long",
                                        "if-eqz",
                                        "iget-wide",
                                        "add-long/2addr",
                                        "return-wide",
                                        "iget-wide",
                                        "cmp-long",
                                        "if-eqz",
                                        "iget-wide",
                                        "goto",
                                        "invoke-static",
                                        "move-result-wide",
                                        "iget-wide",
                                        "sub-long/2addr",
                                        "return-wide",
                                    ),
                                ),
                            )
                        }
                    } ?: return@getMethod null
                if (methodList.isEmpty()) error("ChatLimitDelete method not found")
                method = methodList[0].getMethodInstance(loader)
            }
            method
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadChatLimitDelete2Method(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "dialog/delete no messages",
                "pref_delete_media",
            )
                ?: error("ChatLimitDelete2 method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadNewMessageMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val methodList =
                bridge.findMethod {
                    matcher {
                        usingStrings(listOf("INSERT_TABLE_MESSAGE_QUOTED"), StringMatchType.Equals)
                    }
                }
            if (methodList.isEmpty()) error("NewMessage method not found")

            val methodData = methodList[0]
            val invokes = methodData.invokes
            val clazzMessageName = loadFMessageClass(loader).name

            val method =
                invokes.firstOrNull { invoke ->
                    clazzMessageName == invoke.declaredClass?.name && invoke.returnType?.name == "java.lang.String"
                } ?: error("NewMessage method not found")

            return@getMethod method.getMethodInstance(loader)
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOriginalMessageKey(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "FMessageUtil/getOriginalMessageKeyIfEdited",
            )
                ?: error("MessageEdit method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMessageEditMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "MessageEditInfoStore/insertEditInfo/missing",
            )
                ?: error("MessageEdit method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCallerMessageEditMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val methodData1 = bridge.getMethodData(loadMessageEditMethod(loader))
            val fMessage = loadFMessageClass(loader)
            val invokes = methodData1!!.invokes
            for (methodData in invokes) {
                if (methodData.isConstructor) continue
                val method = methodData.getMethodInstance(loader)
                if (Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == fMessage &&
                    !method.returnType.isPrimitive
                ) {
                    return@getMethod methodData.getMethodInstance(loader)
                }
            }
            error("CallerMessageEdit method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetEditMessageMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val method =
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "MessageEditInfoStore/insertEditInfo/missing",
                )
                    ?: error("GetEditMessage method not found")
            val methodData =
                bridge.getMethodData(DexSignUtil.getMethodDescriptor(method))
                    ?: error("GetEditMessage method not found")
            val invokes = methodData.invokes
            for (invoke in invokes) {
                if (invoke.paramTypes.isEmpty() && invoke.declaredClass == methodData.paramTypes[0]) {
                    return@getMethod invoke.getMethodInstance(loader)
                }
                if (Modifier.isStatic(invoke.getMethodInstance(loader).modifiers) &&
                    invoke.paramTypes[0] == methodData.paramTypes[0] &&
                    invoke.paramTypes[0] != invoke.declaredClass
                ) {
                    return@getMethod invoke.getMethodInstance(loader)
                }
            }
            error("GetEditMessage method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSetEditMessageField(loader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(loader) {
            var method =
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "CoreMessageStore/updateCheckoutMessageWithTransactionInfo",
                )
            if (method == null) {
                method = findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "UPDATE_MESSAGE_ADD_ON_FLAGS_MAIN_SQL",
                ) ?: return@getField null
            }
            val classData = bridge.getClassData(loadFMessageClass(loader))
            val methodData = bridge.getMethodData(DexSignUtil.getMethodDescriptor(method))
            val usingFields = methodData!!.usingFields
            for (f in usingFields) {
                val field = f.field
                if (field.declaredClass == classData && field.type.name == Long::class.javaPrimitiveType?.name) {
                    return@getField field.getFieldInstance(loader)
                }
            }
            error("SetEditMessage method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCoreMessageStore(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            var clazz =
                findFirstClassUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "CoreMessageStore/updateCheckoutMessageWithTransactionInfo",
                )
            if (clazz == null) {
                clazz =
                    findFirstClassUsingStrings(
                        loader,
                        StringMatchType.Contains,
                        "UPDATE_MESSAGE_ADD_ON_FLAGS_MAIN_SQL",
                    )
            }
            clazz ?: error("CoreMessageStore class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadDialogViewClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            val id = Utils.getID("touch_outside", "id")
            val results =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(id)
                        returnType(FrameLayout::class.java)
                    }
                }
            if (results.isEmpty()) error("DialogView class not found")
            results[0].declaredClass!!.getInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadRecreateFragmentConstructor(loader: ClassLoader): Constructor<*> =
        UnobfuscatorCache.getInstance().getConstructor(loader) {
            val data =
                bridge.findMethod {
                    searchPackages("X.")
                    matcher {
                        addUsingString("Instantiated fragment")
                    }
                }
            if (data.isEmpty()) error("RecreateFragment method not found")
            if (!data.single().isConstructor) error("RecreateFragment method not found")
            data.single().getConstructorInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnTabItemAddMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "Maximum number of items supported by",
            )
                ?: error("OnTabItemAdd method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetViewConversationMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val clazz =
                XposedHelpers.findClass(
                    "com.whatsapp.conversationslist.ConversationsFragment",
                    loader,
                )
            Arrays
                .stream(clazz.declaredMethods)
                .filter { m ->
                    m.parameterCount == 3 && m.returnType == View::class.java && m.parameterTypes[1] == LayoutInflater::class.java
                }.findFirst()
                .orElse(null)
                ?: error("GetViewConversation method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnMenuItemSelected(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val aClass = XposedHelpers.findClass("androidx.viewpager.widget.ViewPager", loader)
            val result =
                Arrays
                    .stream(aClass.declaredMethods)
                    .filter { m ->
                        m.parameterCount == 4 &&
                            m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                            m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                            m.parameterTypes[3] == Boolean::class.javaPrimitiveType
                    }.collect(Collectors.toList())
            if (result.isEmpty()) error("OnMenuItemSelected method not found")
            result[1]
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnUpdateStatusChanged(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val clazzData =
                bridge
                    .findClass {
                        matcher {
                            addUsingString("UpdatesViewModel/")
                        }
                    }.firstOrNull()
            val methodSeduleche =
                XposedHelpers.findMethodBestMatch(
                    Timer::class.java,
                    "schedule",
                    TimerTask::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                )
            var result =
                clazzData?.findMethod {
                    matcher {
                        addInvoke(DexSignUtil.getMethodDescriptor(methodSeduleche))
                    }
                } ?: emptyList()
            if (result.isEmpty()) {
                result =
                    bridge.findMethod {
                        matcher {
                            addUsingString("UpdatesViewModel/Scheduled updates list refresh")
                        }
                    }
            }
            if (result.isEmpty()) error("OnUpdateStatusChanged method not found")
            result[0].getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetInvokeField(loader: ClassLoader): Field =
        UnobfuscatorCache.getInstance().getField(loader) {
            val method = loadOnUpdateStatusChanged(loader)
            val methodData = bridge.getMethodData(DexSignUtil.getMethodDescriptor(method))
            val fields = methodData!!.usingFields
            val field =
                fields
                    .stream()
                    .map { it.field }
                    .filter { f -> f.declaredClass == methodData.declaredClass }
                    .findFirst()
                    .orElse(null)
                    ?: error("GetInvokeField method not found")
            field.getFieldInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusInfoClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(loader, StringMatchType.Contains, "ContactStatusDataItem")
                ?: error("StatusInfo class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusListUpdatesClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(loader, StringMatchType.Contains, "StatusListUpdates")
                ?: error("StatusListUpdates class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTabFrameClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(loader, StringMatchType.Contains, "android:menu:presenters")
                ?: error("TabFrame class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadRemoveChannelRecClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(
                loader,
                StringMatchType.Contains,
                "hasNewsletterSubscriptions",
            )
                ?: error("RemoveChannelRec class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFilterAdaperClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            val clazzList =
                bridge.findClass {
                    matcher {
                        addMethod {
                            addUsingString("CONTACTS_FILTER")
                            paramCount(1)
                            addParamType(Int::class.java)
                        }
                    }
                }
            if (clazzList.isEmpty()) error("FilterAdapter class not found")
            clazzList[0].getInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSeeMoreConstructor(loader: ClassLoader): Constructor<*> {
        return UnobfuscatorCache.getInstance().getConstructor(loader) {
            val commentClass =
                findFirstClassUsingName(loader, StringMatchType.EndsWith, "CommentTextView")
            val commentClassData = bridge.getClassData(commentClass)
            val methods = commentClassData!!.methods
            val arrayList = ArrayList<ClassData>()
            methods.forEach { methodData ->
                val invokes = methodData.invokes
                val classes =
                    invokes.stream().map { it.declaredClass!! }.collect(Collectors.toSet())
                arrayList.addAll(classes)
            }

            val clazzData =
                bridge
                    .findClass {
                        searchIn(arrayList)
                        matcher {
                            addMethod {
                                addUsingNumber(16384)
                                addUsingNumber(512)
                                addUsingNumber(64)
                                addUsingNumber(16)
                            }
                        }
                    }.singleOrNull() ?: error("SeeMore constructor 1 not found")

            for (method in clazzData.methods) {
                if (method.paramCount > 1 &&
                    method.isConstructor &&
                    method.paramTypes
                        .stream()
                        .allMatch { c -> c.name == Int::class.javaPrimitiveType?.name }
                ) {
                    return@getConstructor method.getConstructorInstance(loader)
                }
            }
            error("SeeMore constructor 2 not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSendStickerMethods(loader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(loader) {
            findAllMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "StickerGridViewItem.StickerLocal",
            ).ifEmpty { error("SendSticker method not found") }
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMaterialAlertDialog(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val callConfirmationFragment =
                XposedHelpers.findClass(
                    "com.whatsapp.calling.fragment.CallConfirmationFragment",
                    loader,
                )
            val method =
                ReflectionUtils.findMethodUsingFilter(callConfirmationFragment) { m ->
                    m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java
                }
            val methodData = bridge.getMethodData(method)
            val invokes = methodData!!.invokes
            for (invoke in invokes) {
                if (invoke.isMethod &&
                    Modifier.isStatic(invoke.modifiers) &&
                    invoke.paramCount == 1 &&
                    invoke.paramTypes[0].name == Context::class.java.name
                ) {
                    return@getMethod invoke.getMethodInstance(loader)
                }
                if (invoke.isMethod &&
                    invoke.paramCount == 1 &&
                    invoke.paramTypes[0].name == Context::class.java.name
                ) {
                    return@getMethod invoke.getMethodInstance(loader)
                }
            }
            error("MaterialAlertDialog not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadJidFactory(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "lid_me",
                "status_me",
                "s.whatsapp.net",
            )
                ?: error("JidFactory method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGroupCheckAdminMethod(loader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(loader) {
            val classData =
                bridge
                    .findClass {
                        matcher {
                            addUsingString("saveGroupParticipants/INSERT_GROUP_PARTICIPANT_USER")
                        }
                    }.singleOrNull() ?: error("GroupCheckAdmin class data not found")
            val groupChatClass =
                findFirstClassUsingName(loader, StringMatchType.EndsWith, "GroupChatInfoActivity")
            val onCreateMenu =
                ReflectionUtils.findMethodUsingFilter(groupChatClass) { method ->
                    method.name == "onCreateContextMenu"
                }
            val onCreateMenuData = bridge.getMethodData(onCreateMenu)
            val invokes =
                onCreateMenuData!!
                    .invokes
                    .stream()
                    .filter { m -> m.declaredClassName == classData.name }
                    .collect(Collectors.toList())
            for (invoke in invokes) {
                val invokeMethod = invoke.getMethodInstance(loader)
                if (invokeMethod.parameterCount != 2 || invokeMethod.returnType != Boolean::class.javaPrimitiveType) continue
                if (invokeMethod.parameterTypes[1].name.contains("jid.UserJid")) {
                    XposedBridge.log("FIND: $invokeMethod")
                    return@getMethod invokeMethod
                }
            }
            error("GroupCheckAdmin method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStartPrefsConfig(loader: ClassLoader): Constructor<*> =
        UnobfuscatorCache.getInstance().getConstructor(loader) {
            val results =
                bridge.findMethod {
                    matcher {
                        addUsingString("startup_migrated_version")
                    }
                }
            if (results.isEmpty()) error("StartPrefsConfig constructor not found")
            results[0].getConstructorInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCheckOnlineMethod(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            var method =
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "MessageHandler/handleConnectionThreadReady connectionready",
                )
            if (method == null) {
                method =
                    findFirstMethodUsingStrings(
                        loader,
                        StringMatchType.Contains,
                        "app/xmpp/recv/handle_available",
                    )
            }
            method ?: error("CheckOnline method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadEphemeralInsertdb(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val method =
                bridge.findMethod {
                    matcher {
                        addUsingString("expire_timestamp")
                        addUsingString("ephemeral_initiated_by_me")
                        addUsingString("ephemeral_trigger")
                        returnType(ContentValues::class.java)
                    }
                }
            if (method.isEmpty()) error("FieldExpireTime method not found")
            method[0].getMethodInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadVideoViewContainerClass(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            findFirstClassUsingStrings(
                loader,
                StringMatchType.Contains,
                "frame_visibility_serial_worker",
            )
                ?: error("VideoViewContainer class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadImageVewContainerClass(loader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(loader) {
            val clazzList =
                bridge.findClass {
                    matcher {
                        addMethod {
                            addUsingNumber(Utils.getID("hd_invisible_touch", "id"))
                            addUsingNumber(Utils.getID("control_btn", "id"))
                        }
                    }
                }
            if (clazzList.isEmpty()) error("ImageViewContainer class not found")
            for (clazzData in clazzList) {
                val clazz = clazzData.getInstance(loader)
                if (ViewGroup::class.java.isAssignableFrom(clazz)) return@getClass clazz
            }
            throw ClassNotFoundException("Class ImageViewContainer not Found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun getFilterView(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            val filterId = Utils.getID("conversations_swipe_to_reveal_filters_stub", "id")
            val results =
                bridge.findClass {
                    matcher {
                        addMethod {
                            addUsingNumber(filterId)
                        }
                    }
                }
            if (results.isEmpty()) error("FilterView class not found")
            results[0].getInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadActionUser(loader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(loader) {
            val fMessageClass = loadFMessageClass(loader)
            val result =
                bridge
                    .findMethod {
                        matcher {
                            paramTypes(fMessageClass, String::class.java, Boolean::class.javaPrimitiveType)
                            modifiers(Modifier.PUBLIC or Modifier.FINAL)
                            returnType(java.lang.Boolean.TYPE)
                        }
                    }.singleOrNull() ?: error("ActionUser class not found")
            result.declaredClass!!.getInstance(loader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnPlaybackFinished(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "playbackPage/onPlaybackContentFinished",
            )
                ?: error("OnPlaybackFinished method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadNextStatusRunMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val methodList =
                bridge.findMethod {
                    matcher {
                        addUsingString("playMiddleTone")
                        name("run")
                    }
                }
            if (methodList.isEmpty()) error("RunNextStatus method not found")
            methodList[0].getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnInsertReceipt(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                bridge
                    .findMethod {
                        matcher {
                            addUsingString("INSERT_RECEIPT_USER")
                            paramCount(1)
                        }
                    }.singleOrNull() ?: error("OnInsertReceipt method not found")
            method.getMethodInstance(classLoader)
        }

    fun loadMediaTypeMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classMsgReplyAct =
                findFirstClassUsingName(
                    classLoader,
                    StringMatchType.EndsWith,
                    "MessageReplyActivity",
                )
            val method =
                classMsgReplyAct.getMethod(
                    "onActivityResult",
                    Int::class.java,
                    Int::class.java,
                    Intent::class.java,
                )
            val methodData =
                bridge.getMethodData(method)
                    ?: throw NoSuchMethodException("SendAudioType method not found")
            val invokes = methodData.invokes

            for (invoke in invokes) {
                if (!invoke.isMethod) continue
                val m1 = invoke.getMethodInstance(classLoader)
                val params = m1.parameterTypes.toList()
                if (params.contains(List::class.java) &&
                    params.contains(Int::class.java) &&
                    params.contains(
                        Uri::class.java,
                    )
                ) {
                    return@getMethod m1
                }
            }
            throw NoSuchMethodException("SendAudioType method not found")
        }
    }

    fun loadOriginFMessageField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val fMessageClass = loadFMessageClass(classLoader)

            val preferredResult =
                bridge.findMethod {
                    matcher {
                        usingStrings("audio/ogg; codecs=opu")
                        returnType = Boolean::class.java.name
                    }
                }
            for (methodData in preferredResult) {
                for (fieldData in methodData.usingFields) {
                    val field = fieldData.field.getFieldInstance(classLoader)
                    if (fMessageClass.isAssignableFrom(field.declaringClass)) {
                        return@getField field
                    }
                }
            }

            // Compatibility fallback used by the 2.26.32-supported implementation.
            val legacyMarkers =
                arrayOf(
                    "audio/ogg; codecs=opus",
                    "audio/ogg",
                    "audio/amr",
                    "audio/mp4",
                    "audio/aac",
                )
            for (marker in legacyMarkers) {
                val legacyResult =
                    try {
                        bridge.findMethod {
                            matcher {
                                addUsingString(marker, StringMatchType.Contains)
                            }
                        }
                    } catch (_: Exception) {
                        continue
                    }

                for (methodData in legacyResult) {
                    for (fieldData in methodData.usingFields) {
                        val field = fieldData.field.getFieldInstance(classLoader)
                        if (fMessageClass.isAssignableFrom(field.declaringClass)) {
                            return@getField field
                        }
                    }
                }
            }

            error("OriginFMessageField field not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadForwardAudioTypeMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val results =
                findAllMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "FMessageFactory/newFMessageForForward/thumbnail",
                )
            if (results.isEmpty()) error("ForwardAudioType method not found")
            if (results.size > 1) {
                requireMethod(
                    "loadForwardAudioTypeMethod",
                    "forwardable",
                    findFirstMethodUsingStrings(
                        classLoader,
                        StringMatchType.Contains,
                        "forwardable",
                        "FMessageFactory/newFMessageForForward/thumbnail",
                    ),
                )
            } else {
                requireMethod(
                    "loadForwardAudioTypeMethod",
                    "Non-forwardable message(",
                    findFirstMethodUsingStrings(
                        classLoader,
                        StringMatchType.Contains,
                        "Non-forwardable message(",
                    ),
                )
            }
        }

    fun loadPlaybackSpeed(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            bridge
                .findMethod {
                    matcher {
                        anyOf {
                            match {
                                paramTypes(null, Float::class.javaPrimitiveType)
                                usingStrings("FbHeroAudioPlayer/setPlaybackSpeed")
                            }
                            match {
                                paramTypes(Float::class.javaPrimitiveType)
                                usingStrings("setPlaybackSpeed")
                                callerMethods {
                                    add {
                                        usingStrings("FbHeroAudioPlayer/setPlaybackSpeed")
                                    }
                                }
                            }
                        }
                    }
                }.first()
                .getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadListUpdateItems(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                bridge.findMethod {
                    matcher {
                        addUsingString("Running diff util, updates list size", StringMatchType.Contains)
                    }
                }
            if (method.isEmpty()) error("ListUpdateItems method not found")
            method[0].getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadHeaderChannelItemClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "statusTilesEnabled")
                ?: error("HeaderChannelItem class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadListChannelItemClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "isMuteIndicatorEnabled",
            )
                ?: error("NewsletterDataItem class not found")
        }

    @JvmStatic
    @Throws(Exception::class)
    fun loadExpirationClass(classLoader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(classLoader) {
            val methods =
                findAllMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "software_forced_expiration",
                )
            val expirationMethod =
                methods.firstOrNull { it.returnType == Date::class.java }
                    ?: error("Expiration class not found")
            return@getClass expirationMethod.declaringClass
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAbsViewHolder(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "not recyclable")
                ?: error("AbsViewHolder class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFragmentViewMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "this was called before onCreateView()",
            )
                ?: error("FragmentView method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCopiedMessageMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "conversation/copymessage",
            )
                ?: error("CopiedMessage method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSenderPlayedClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "sendmethods/sendClearDirty",
            )
                ?: error("SenderPlayed class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSenderPlayedMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val clazz = loadSenderPlayedClass(classLoader)
            val abstractMediaMessageClass = loadAbstractMediaMessageClass(classLoader)
            val interfaces = abstractMediaMessageClass.interfaces

            val interfacesList = ArrayList<Class<*>>()
            interfacesList.add(abstractMediaMessageClass)
            interfacesList.addAll(listOf(*interfaces))

            var methodResult: Method? = null
            main_loop@ for (method in clazz.methods) {
                if (method.parameterCount != 1) continue
                val parameterType = method.parameterTypes[0]
                for (interfaceClass in interfacesList) {
                    if (interfaceClass.isAssignableFrom(parameterType)) {
                        methodResult = method
                        break@main_loop
                    }
                }
            }

            val fmessageClass = loadFMessageClass(classLoader)
            if (methodResult == null) {
                val method =
                    findFirstMethodUsingStrings(
                        classLoader,
                        StringMatchType.Contains,
                        "mediaHash and fileType not both present for upload URL generation",
                    )
                if (method != null) {
                    val cMethods = bridge.getMethodData(method)!!.invokes
                    Collections.reverse(cMethods)
                    for (cmethod in cMethods) {
                        if (cmethod.isMethod && cmethod.paramCount == 1) {
                            val cParamType = cmethod.paramTypes[0].getInstance(classLoader)
                            if (fmessageClass.isAssignableFrom(cParamType)) {
                                methodResult = cmethod.getMethodInstance(classLoader)
                                break
                            }
                        }
                    }
                }
            }

            methodResult ?: error("SenderPlayed method not found 2")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSenderPlayedBusiness(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val loadSenderPlayed = loadSenderPlayedClass(classLoader)
            ReflectionUtils.findMethodUsingFilter(loadSenderPlayed) { method ->
                method.parameterCount > 0 && method.parameterTypes[0] == Set::class.java
            } ?: error("SenderPlayedBusiness method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMediaTypeField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val methodData =
                bridge.findMethod {
                    matcher {
                        addUsingString("conversation/refresh")
                    }
                }
            if (methodData.isEmpty()) error("MediaType: aux method not found")
            val fclass = bridge.getClassData(loadFMessageClass(classLoader))
            val usingFields = methodData[0].usingFields
            for (f in usingFields) {
                val field = f.field
                if (field.declaredClass == fclass && field.type.name == Int::class.javaPrimitiveType?.name) {
                    return@getField field.getFieldInstance(classLoader)
                }
            }
            error("MediaType field not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBubbleDrawableMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val methodData =
                bridge.findMethod {
                    matcher {
                        addUsingString("Unreachable code: direction=")
                        returnType(Drawable::class.java)
                    }
                }
            if (methodData.isEmpty()) error("BubbleDrawable method not found")
            methodData[0].getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBallonDateDrawable(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val methodData =
                bridge.findMethod {
                    matcher {
                        addUsingString("Unreachable code: direction=")
                        returnType(Rect::class.java)
                    }
                }
            if (methodData.isEmpty()) error("LoadDateWrapper method not found")
            val clazz = methodData[0].getMethodInstance(classLoader).declaringClass
            ReflectionUtils.findMethodUsingFilterIfExists(clazz) { m ->
                listOf(
                    1,
                    2,
                ).contains(m.parameterCount) &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.returnType == Drawable::class.java
            } ?: error("DateWrapper method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBallonBorderDrawable(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val clazz = loadBallonDateDrawable(classLoader).declaringClass
            ReflectionUtils.findMethodUsingFilterIfExists(clazz) { m ->
                m.parameterCount == 3 && m.returnType == Drawable::class.java
            } ?: error("Ballon Border method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadRootDetector(classLoader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(classLoader) {
            val methods =
                findAllMethodUsingStrings(classLoader, StringMatchType.Contains, "/system/bin/su")
            if (methods.isEmpty()) error("RootDetector method not found")
            methods
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCheckEmulator(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "Android SDK built for x86",
            )
                ?: error("CheckEmulator method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCheckCustomRom(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(classLoader, StringMatchType.Contains, "cyanogen")
                ?: error("CheckCustomRom method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTranscribeMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadTranscribeMethod",
                "transcribe: starting transcription",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "transcribe: starting transcription",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCheckSupportLanguage(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadCheckSupportLanguage",
                "Unsupported language",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "Unsupported language",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTranscriptSegment(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadTranscriptSegment",
                "TranscriptionSegment(",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "TranscriptionSegment(",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStateChangeMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadStateChangeMethod",
                "presencestatemanager/startTransitionToUnavailable/new-state",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "presencestatemanager/startTransitionToUnavailable/new-state",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadCachedMessageStoreKey(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            findFirstMethodUsingStrings(
                loader,
                StringMatchType.Contains,
                "CachedMessageStore/getMessage/key",
            )
                ?: error("CachedMessageStore class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAbstractMediaMessageClass(loader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(loader) {
            for (str in listOf(
                "first_viewed_timestamp",
                "Field is set but is null in MediaDataV2",
            )) {
                val classList =
                    bridge.findClass {
                        matcher {
                            addUsingString(str)
                        }
                    }
                for (clazz in classList) {
                    val clazzInstance = clazz.getInstance(loader)
                    if (FMessageWpp.checkUnsafeIsFMessage(
                            loader,
                            clazzInstance,
                        )
                    ) {
                        return@getClass clazzInstance
                    }
                }
            }
            throw ClassNotFoundException("AbstractMediaMessage Not Found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFragmentClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "mFragmentId=#")
                ?: error("Fragment class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMediaQualitySelectionMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethodByAnyAnchor(
                "loadMediaQualitySelectionMethod",
                classLoader,
                StringMatchType.Contains,
                listOf("enable_media_quality_tool", "show_media_quality_toggle", "media_quality_selection_enabled"),
                // The hook replaces the return value with a boolean, so a
                // non-boolean match here would break the call site.
                Boolean::class.javaPrimitiveType,
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFmessageTimestampField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val fmessageClass = loadFMessageClass(classLoader)
            val chatLimitDelete2Method = loadChatLimitDelete2Method(classLoader)
            val usingFields = bridge.getMethodData(chatLimitDelete2Method)!!.usingFields
            for (uField in usingFields) {
                val field = uField.field
                if (field.declaredClass.name == fmessageClass.name && field.type.name == Long::class.javaPrimitiveType?.name) {
                    return@getField field.getFieldInstance(classLoader)
                }
            }
            error("FMessage Timestamp method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadProximitySensorListenerClasses(classLoader: ClassLoader): Array<Class<*>> =
        UnobfuscatorCache.getInstance().getClasses(classLoader) {
            val classDataList =
                bridge.findClass {
                    matcher {
                        addInterface(SensorEventListener::class.java.name)
                    }
                }
            if (classDataList.isEmpty()) error("Class SensorEventListener not found")
            classDataList
                .stream()
                .map { classData -> convertRealClass(classData, classLoader) }
                .filter { it != null }
                .map { it!! }
                .toArray { length -> arrayOfNulls<Class<*>>(length) }
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadTcTokenMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadTcTokenMethod",
                "GET_RECEIVED_TOKEN_AND_TIMESTAMP_BY_JID",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "GET_RECEIVED_TOKEN_AND_TIMESTAMP_BY_JID",
                ),
            )
        }

    fun loadStatusDataClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        addUsingString("StatusData(", StringMatchType.StartsWith)
                    }
                }.single()
                .getInstance(classLoader)
        }

    fun loadStatusProfileMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val statusClass = loadStatusDataClass(classLoader)
            val convClass =
                findFirstClassUsingName(
                    classLoader,
                    StringMatchType.EndsWith,
                    ".ConversationsFragment",
                )
            val convClassData = bridge.getClassData(convClass.name)!!
            convClassData
                .findMethod {
                    matcher {
                        anyOf {
                            statusClass.declaredMethods
                                .filter {
                                    it.returnType == Boolean::class.javaPrimitiveType
                                }.forEach {
                                    match {
                                        addInvoke(DexSignUtil.getMethodDescriptor(it))
                                    }
                                }
                        }
                    }
                }.single {
                    it.paramCount > 0 && !Modifier.isStatic(it.modifiers) && it.paramTypeNames[0] == "android.view.View"
                }.getMethodInstance(classLoader)
        }

    @Throws(ClassNotFoundException::class)
    @JvmStatic
    fun getClassByName(
        className: String,
        classLoader: ClassLoader,
    ): Class<*> {
        if (cacheClasses.containsKey(className)) return cacheClasses[className]!!
        val classDataList =
            bridge.findClass {
                matcher {
                    className(className, StringMatchType.EndsWith)
                }
            }
        if (classDataList.isEmpty()) error("Class $className not found!")
        val clazz = classDataList[0].getInstance(classLoader)
        cacheClasses[className] = clazz
        return clazz
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadVoipManager(classLoader: ClassLoader): Class<*> {
        return UnobfuscatorCache.getInstance().getClass(classLoader) {
            val voipClass = ModuleRuntime.voipManagerClass
            val superClasses =
                bridge.findClass {
                    matcher {
                        superClass(voipClass.name)
                    }
                }
            if (superClasses.isEmpty()) throw ClassNotFoundException("VoipManager Class not found")
            for (supclass in superClasses) {
                if (!Modifier.isAbstract(supclass.modifiers)) {
                    return@getClass supclass.getInstance(
                        classLoader,
                    )
                }
            }
            throw ClassNotFoundException("VoipManager Class not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadWaContactClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadWaContactClass",
                "problematic contact:",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "problematic contact:",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadViewAddSearchBarMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            for (str in listOf(
                "HeaderFooterRecyclerViewAdapter/addHeaderViewItemIfNeeded",
                "HeaderFooterRecyclerViewAdapter/addFooterViewItemAtPositionIfNeeded",
            )) {
                val method = findFirstMethodUsingStrings(classLoader, StringMatchType.Contains, str)
                if (method != null) return@getMethod method
            }
            error("ViewAddSearchBar method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAddOptionSearchBarMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classData =
                bridge.getClassData(ModuleRuntime.homeActivityClass)
                    ?: return@getMethod null
            val methodData =
                classData.findMethod {
                    matcher {
                        addUsingNumber(Utils.getID("menuitem_search", "id"))
                        addUsingNumber(200)
                        paramCount(1)
                        addParamType(Menu::class.java)
                    }
                }
            if (methodData.isEmpty()) throw NoSuchMethodError("MenuSearch not found in HomeActivity")
            methodData[0].getMethodInstance(classLoader)
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAddMenuAndroidX(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadAddMenuAndroidX",
                "Maximum number of items supported by",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "Maximum number of items supported by",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadConvertLidToJid(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            requireMethod(
                "loadConvertLidToJid",
                "WaJidMapRepository/getPhoneJidByAccountUserJid",
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "WaJidMapRepository/getPhoneJidByAccountUserJid",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadConvertJidToLid(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            requireMethod(
                "loadConvertJidToLid",
                "WaJidMapRepository/getAccountUserJidByPhoneJid",
                findFirstMethodUsingStrings(
                    loader,
                    StringMatchType.Contains,
                    "WaJidMapRepository/getAccountUserJidByPhoneJid",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMeManagerClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(classLoader, StringMatchType.StartsWith, "memanager/setMe")
                ?: error("MeManager class not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMySearchBarMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.EndsWith,
                "search_bar_render_start",
            )
                ?: throw NoSuchMethodException("MySearchBar method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAdVerifyMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                bridge
                    .findMethod {
                        matcher {
                            usingStrings("is_wfal_paused")
                            paramCount(1)
                        }
                    }.singleOrNull() ?: throw NoSuchMethodException("loadAdVerify Not Found")
            method.getMethodInstance(classLoader)
        }

    @JvmStatic
    @Throws(Exception::class)
    fun loadFilterDimenId(classLoader: ClassLoader): Int =
        UnobfuscatorCache
            .getInstance()
            .getNumber(classLoader) {
                val methodData =
                    bridge
                        .findClass {
                            matcher {
                                className(".ConversationsFragment", StringMatchType.EndsWith)
                            }
                        }.findMethod {
                            matcher {
                                returnType = "int"
                                paramCount = 0
                                modifiers = Modifier.PRIVATE
                            }
                        }.firstOrNull() ?: error("ConversationsFragment A00 method not found")

                val dimenId =
                    methodData.usingNumbers
                        .map { n ->
                            java.lang.Float.floatToIntBits(n.toFloat())
                        }.firstOrNull { idInt ->
                            (idInt ushr 24) == 0x7F && ((idInt ushr 16) and 0xFF) == 0x07
                        } ?: error("Filter dimen ID not found in ConversationsFragment")
                dimenId
            }.toInt()

    @JvmStatic
    @Throws(Exception::class)
    fun loadChatFilterViewMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val value = Utils.getID("conversations_swipe_to_reveal_filter_recycler_view", "id")
            val method =
                bridge
                    .findMethod {
                        matcher {
                            usingNumbers(value)
                            modifiers = Modifier.PUBLIC or Modifier.STATIC
                        }
                    }.firstOrNull() ?: error("ChatFilterView method not found")
            return@getMethod method.getMethodInstance(classLoader)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun loadConversationsHeightMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val methodData =
                bridge
                    .findClass {
                        matcher {
                            className(".ConversationsFragment", StringMatchType.EndsWith)
                        }
                    }.findMethod {
                        matcher {
                            returnType = "int"
                            paramCount = 0
                            modifiers = Modifier.PRIVATE
                        }
                    }.firstOrNull()
                    ?: error("ConversationsFragment height calculation method not found")
            return@getMethod methodData.getMethodInstance(classLoader)
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun loadConversationsUpdateLayoutMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classData =
                bridge.findClass {
                    matcher {
                        className(".ConversationsFragment", StringMatchType.EndsWith)
                    }
                }
            val methodData =
                classData
                    .findMethod {
                        matcher {
                            paramCount = 1
                            paramTypes(classData.first().name)
                            returnType = "void"
                            modifiers = Modifier.PUBLIC or Modifier.STATIC
                        }
                    }.firstOrNull()
                    ?: error("ConversationsFragment update layout method not found")
            return@getMethod methodData.getMethodInstance(classLoader)
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadNotificationMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val invokedMethod =
                bridge
                    .findMethod {
                        matcher {
                            addUsingString("LastMessageStore/getLastMessagesForNotificationAfterReply")
                        }
                    }.singleOrNull() ?: error("Notification invoked method not found")
            invokedMethod.getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadLockedChatsMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val classData =
                bridge
                    .findClass {
                        matcher {
                            addUsingString("conversationsmgr/replacecontact")
                        }
                    }.singleOrNull() ?: error("ConversationsManager class not found")

            val invokedMethod = bridge.getMethodData(loadNotificationMethod(classLoader))
            for (invoke in invokedMethod!!.invokes) {
                if (!invoke.isMethod) continue
                if (invoke.className != classData.name) continue
                if (invoke.returnType?.name != ArrayList::class.java.name) continue
                return@getMethod invoke.getMethodInstance(classLoader)
            }
            error("LockedChats method not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetProfilePhotoMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadGetProfilePhotoMethod",
                "contactPhotosBitmapManager/getphotostream/",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "contactPhotosBitmapManager/getphotostream/",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadChatCacheClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadChatCacheClass",
                "Chatscache/",
                findFirstClassUsingStrings(classLoader, StringMatchType.StartsWith, "Chatscache/"),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadLoadedContactsMethod(classLoader: ClassLoader): Method? {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val methods =
                bridge.findMethod {
                    matcher {
                        addUsingNumber(8726)
                        paramCount(1)
                        addParamType(Any::class.java)
                    }
                }
            if (methods.isEmpty()) return@getMethod null
            methods[0].getMethodInstance(classLoader)
        }
    }

    /**
     * The method that reports the corrected output resolution of a transcode.
     *
     * Restored for HD Status: this was the anchor behind the real-resolution and
     * 60fps options, which were left in the settings screen after the hooks that
     * consumed them were removed in `6a4df80a`. Several anchors are tried because
     * the method has been renamed at least once; the first hit wins.
     */
    fun loadMediaQualityVideoMethod2(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethodByAnyAnchor(
                "loadMediaQualityVideoMethod2",
                classLoader,
                StringMatchType.Contains,
                listOf("getCorrectedResolution", "correctedResolution", "getOutputResolution"),
            )
        }

    /**
     * The transcode-parameter fields (`targetWidth`, `targetHeight`, `frameRate`),
     * read off the return type of [loadMediaQualityVideoMethod2].
     *
     * These are Kotlin data classes, so the names come from the generated
     * `toString()` and survive obfuscation; the pairing with the field list is by
     * index, which is why a skip here is reported rather than ignored.
     */
    fun loadMediaQualityVideoFields(classLoader: ClassLoader): HashMap<String, Field> =
        UnobfuscatorCache.getInstance().getMapField(classLoader, "loadMediaQualityVideoFields") {
            val method = loadMediaQualityVideoMethod2(classLoader)
            val methodString = method.returnType.getDeclaredMethod("toString")
            val methodData =
                bridge.getMethodData(methodString)
                    ?: error("loadMediaQualityVideoFields: no dexkit data for the return type")
            val usingFields = methodData.usingFields
            val usingStrings = methodData.usingStrings
            val result = HashMap<String, Field>()
            var idxFields = 0
            for (i in usingStrings.indices) {
                if (idxFields >= usingFields.size) break
                // Not a field label: it is a literal compared inside the template.
                if (usingStrings[i] == "outputAspectRatio") continue
                val name = usingStrings[i].trim()
                if (name.isEmpty()) continue
                result[name] = usingFields[idxFields].field.getFieldInstance(classLoader)
                idxFields++
            }
            result
        }

    /**
     * The source-resolution fields (`widthPx`, `heightPx`, `rotationAngle`) read off
     * the first parameter of [loadMediaQualityVideoMethod2].
     */
    fun loadMediaQualityOriginalVideoFields(classLoader: ClassLoader): HashMap<String, Field> {
        return UnobfuscatorCache.getInstance().getMapField(classLoader, "loadMediaQualityOriginalVideoFields") {
            val method = loadMediaQualityVideoMethod2(classLoader)
            val paramType =
                method.parameterTypes.firstOrNull()
                    ?: return@getMapField HashMap()
            val methodString =
                try {
                    paramType.getDeclaredMethod("toString")
                } catch (_: Exception) {
                    return@getMapField HashMap()
                }
            val methodData = bridge.getMethodData(methodString)
            if (methodData == null || methodData.usingStrings.isEmpty()) return@getMapField HashMap()
            val usingFields = methodData.usingFields
            val usingStrings = methodData.usingStrings
            val result = HashMap<String, Field>()
            for (i in usingStrings.indices) {
                if (i >= usingFields.size) break
                val name = usingStrings[i].trim()
                if (name.isEmpty()) continue
                result[name] = usingFields[i].field.getFieldInstance(classLoader)
            }
            result
        }
    }

    fun loadVideoTranscoderStartMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethodByAnyAnchor(
                "loadVideoTranscoderStartMethod",
                classLoader,
                StringMatchType.Contains,
                listOf(
                    "VideoTranscoder/transcodeVideoNew/",
                    "VideoTranscoder/transcodeVideo/",
                    "VideoTranscoder/startTranscode",
                ),
            )
        }

    fun loadMediaTranscoderStart(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethodByAnyAnchor(
                "loadMediaTranscoderStart",
                classLoader,
                StringMatchType.Contains,
                listOf("MediaTranscode/Starting", "MediaTranscode/start"),
            )
        }

    /**
     * The non-throwing counterpart of [loadMediaTranscoderStart].
     *
     * HD Status uses this rather than the throwing variant. The original code ended
     * in `.first()` on a dexkit result, so a WhatsApp build without the anchor threw
     * `NoSuchElementException` from the middle of the video hook block and every
     * override after it silently never ran. Returning null keeps a missing anchor
     * local to the one override that needed it.
     *
     * Not routed through [UnobfuscatorCache] because that cache cannot store a
     * negative result; the lookup happens once per process start, which is the same
     * cost as the first call of the cached path.
     */
    @JvmStatic
    fun loadMediaTranscoderStartOrNull(classLoader: ClassLoader): Method? =
        try {
            findMethodByAnyAnchor(
                classLoader,
                StringMatchType.Contains,
                listOf("MediaTranscode/Starting", "MediaTranscode/start"),
            )?.value
        } catch (_: Throwable) {
            null
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadWaContactGetWaNameField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val method =
                bridge
                    .findMethod {
                        matcher {
                            addUsingString("ContactManagerDatabase/updateContactWAName")
                        }
                    }.singleOrNull() ?: throw NoSuchMethodException("WaContactGetWaName field not found")

            val waContact = loadWaContactClass(classLoader).name
            val usingFields = method.usingFields
            for (usingField in usingFields) {
                val field = usingField.field
                if (field.className == waContact && field.type.name == String::class.java.name) {
                    return@getField field.getFieldInstance(classLoader)
                }
            }
            val waContactData = loadWaContactDataClass(classLoader).name
            for (usingField in usingFields) {
                val field = usingField.field
                if (field.className == waContactData && field.type.name == String::class.java.name) {
                    return@getField field.getFieldInstance(classLoader)
                }
            }
            throw NoSuchMethodException("WaContactGetWaName field not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadWaContactDataClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadWaContactDataClass",
                "WaContactData",
                findFirstClassUsingStrings(classLoader, StringMatchType.EndsWith, "WaContactData"),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadWaContactDataDisplayNameMethod(classLoader: ClassLoader): Field? {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val methods =
                bridge.findMethod {
                    matcher {
                        addUsingString("ContactManagerDatabase/updateGroupInfo")
                    }
                }
            if (methods.isEmpty()) throw NoSuchMethodException("WaContactDiplayName not found")

            val waContactDataClassName = loadWaContactDataClass(classLoader).name
            val waContactClassName = loadWaContactClass(classLoader).name
            val invokes = methods[0].invokes
            for (invoke in invokes) {
                if (invoke.className != waContactClassName) continue
                if (invoke.returnTypeName == String::class.java.name) {
                    for (usingFieldData in invoke.usingFields) {
                        if (usingFieldData.field.declaredClassName != waContactDataClassName) continue
                        if (usingFieldData.field.typeName == String::class.java.name) {
                            return@getField usingFieldData.field.getFieldInstance(classLoader)
                        }
                    }
                }
            }
            for (usingFieldData in methods[0].usingFields) {
                if (usingFieldData.field.declaredClassName != waContactDataClassName) continue
                if (usingFieldData.field.typeName == String::class.java.name) {
                    return@getField usingFieldData.field.getFieldInstance(classLoader)
                }
            }
            null
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetWaContactMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadGetWaContactMethod",
                "ContactManager/getContactFromCacheOrDbByJid",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "ContactManager/getContactFromCacheOrDbByJid",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSharedPreferencesClasses(classLoader: ClassLoader): Array<Class<*>>? {
        return UnobfuscatorCache.getInstance().getClasses(classLoader) {
            val classesData =
                bridge.findClass {
                    matcher {
                        addInterface(SharedPreferences::class.java.name)
                    }
                }
            if (classesData.isEmpty()) return@getClasses null
            classesData
                .stream()
                .map { classData -> convertRealClass(classData, classLoader) }
                .toArray { length -> arrayOfNulls<Class<*>>(length) }
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPinnedFilterMethod(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "pinSelectedJids",
                ) ?: return@getMethod null
            val methodData = bridge.getMethodData(method)
            for (invoke in methodData!!.invokes) {
                if (!invoke.isMethod) continue
                val methodInstance = invoke.getMethodInstance(classLoader)
                if (!Set::class.java.isAssignableFrom(methodInstance.returnType)) continue
                if (methodInstance.parameterCount == 2 &&
                    methodInstance.parameterTypes[0] == Iterable::class.java &&
                    methodInstance.parameterTypes[1] == Set::class.java
                ) {
                    return@getMethod methodInstance
                }
            }
            throw NoSuchMethodException("PinnedLinkedHashMethod not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSetPinnedLimitMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "ChatSettingsStore/setPin",
            )
                ?: throw NoSuchMethodException("SetPinnedLimit method not found")
        }

    @Throws(Exception::class)
    @JvmStatic
    fun getAllMapFields(clazz: Class<*>): HashMap<String, Field> {
        val cache = UnobfuscatorCache.getInstance()
        val classLoader = clazz.classLoader
        if (classLoader != null) {
            val cacheKey = "getAllMapFields:" + clazz.name
            return cache.getMapField(classLoader, cacheKey) { buildAllMapFields(clazz) }
        }
        return buildAllMapFields(clazz)
    }

    @Throws(Exception::class)
    @JvmStatic
    private fun buildAllMapFields(clazz: Class<*>): HashMap<String, Field> {
        val methodString =
            try {
                clazz.getDeclaredMethod("toString")
            } catch (_: Exception) {
                return HashMap()
            }
        val methodData = bridge.getMethodData(methodString) ?: return HashMap()
        val usingFields = methodData.usingFields
        val usingStrings = methodData.usingStrings
        val result = HashMap<String, Field>()
        var idxFields = 0
        for (i in usingStrings.indices) {
            if (idxFields == usingFields.size) break
            val raw = usingStrings[i]
            val string = raw.trim()
            if (string.isEmpty()) continue
            val eq = string.lastIndexOf('=')
            if (eq < 0) continue
            var start = 0
            for (j in eq - 1 downTo 0) {
                val c = string[j]
                if (c == '\'' || c == ',' || c == ' ' || c == '(' || c == ')' || c == ':' || c == '{' || c == '}') {
                    start = j + 1
                    break
                }
            }
            if (start >= eq) continue
            val name = string.substring(start, eq)
            val field = usingFields[idxFields].field.getFieldInstance(clazz.classLoader!!)
            result[name] = field
            idxFields++
        }
        return result
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadMediaDataVideoConfigurationClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClassByAnyAnchor(
                "loadMediaDataVideoConfigurationClass",
                classLoader,
                StringMatchType.Contains,
                listOf("MediaDataVideoConfiguration(", "VideoTranscodeConfiguration("),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusStyleMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val method =
                bridge
                    .findMethod {
                        matcher {
                            addUsingNumber(8522)
                            returnType(java.lang.Integer.TYPE)
                        }
                    }.singleOrNull() ?: throw NoSuchMethodException("StatusStyle method not found")
            method.getMethodInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadProcessImageQualityClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClassByAnyAnchor(
                "loadProcessImageQualityClass",
                classLoader,
                StringMatchType.StartsWith,
                listOf("ProcessImageQuality(", "ImageQuality("),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetProfilePhoto(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadGetProfilePhoto",
                "ProfilePhotoManager/sendGetProfilePhoto",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "ProfilePhotoManager/sendGetProfilePhoto",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadDialerProfilePictureLoader(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadDialerProfilePictureLoader",
                "DialerProfilePictureLoader/syncFetchProfilePhoto/onPhotoReceived",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "DialerProfilePictureLoader/syncFetchProfilePhoto/onPhotoReceived",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadBottomBarConfigClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadBottomBarConfigClass",
                "BottomBarConfig(",
                findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "BottomBarConfig("),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnCreatedMenuConversation(loader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(loader) {
            val conversationClass =
                findFirstClassUsingName(loader, StringMatchType.EndsWith, "Conversation")
            ReflectionUtils.findMethodUsingFilter(
                conversationClass,
            ) { m -> m.parameterCount == 1 && m.parameterTypes[0] == Menu::class.java }!!
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFStatusKeyClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        addUsingString("Key(id=")
                        addUsingString("senderJid")
                    }
                }.first()
                .getInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFStatusClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadFStatusClass",
                "FStatus state",
                findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "FStatus state"),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadAntiRevokeFStatusMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val fStatusKeyClass = loadFStatusKeyClass(classLoader)
            val clazz =
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "RevokeStatusManager/failed",
                )
            ReflectionUtils.findMethodUsingFilter(clazz) { method ->
                method.parameterCount > 0 && fStatusKeyClass.isAssignableFrom(method.parameterTypes[0])
            }!!
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetStatusByKey(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadGetStatusByKey",
                "StatusStore/GET_STATUS_BY_KEY",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "StatusStore/GET_STATUS_BY_KEY",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFStatusToFMessage(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadFStatusToFMessage",
                "mapFStatusToFMessageForForwarding",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "mapFStatusToFMessageForForwarding",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadStatusPlaybackReplyContainer(classLoader: ClassLoader): Method {
        return UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val clazz =
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "replyContainer",
                    "bottomSheet",
                    "contentSheet",
                ) ?: return@getMethod null
            val clazzData = bridge.getClassData(clazz)
            val methodData =
                clazzData!!.findMethod {
                    matcher {
                        addUsingString("replyContainer")
                    }
                }
            if (methodData.isEmpty()) error("StatusPlaybackReply method not found")
            methodData[0].getMethodInstance(classLoader)
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadProtocolTreeNodeClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadProtocolTreeNodeClass",
                "ProtocolTreeNode/getAttributeJid",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "ProtocolTreeNode/getAttributeJid",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadKeyValueClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadKeyValueClass",
                "KeyValue{key=",
                findFirstClassUsingStrings(classLoader, StringMatchType.Contains, "KeyValue{key="),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadLockedAuthCheckMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadLockedAuthCheckMethod",
                "privacy_fingerprint_enabled",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "privacy_fingerprint_enabled",
                    "app_lock_auth_needed",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadGetCurrentPageInHomeField(classLoader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val method = bridge.getMethodData(loadAddOptionSearchBarMethod(classLoader))
            for (uField in method!!.usingFields) {
                if (uField.field.declaredClassName == method.declaredClassName && uField.field.typeName == "int") {
                    return@getField uField.field.getFieldInstance(classLoader)
                }
            }
            throw NoSuchFieldException("CurrentPageInHome field not found")
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadFMediaStatusClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            requireClass(
                "loadFMediaStatusClass",
                "FStatusMedia/mediaDataV2",
                findFirstClassUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "FStatusMedia/mediaDataV2",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadWaContactNumberField(classLoader: ClassLoader): Field? {
        return UnobfuscatorCache.getInstance().getField(classLoader) {
            val waContact = loadWaContactClass(classLoader)
            val waContactData =
                bridge.getClassData(waContact)
                    ?: throw NoSuchFieldException("WaContact class data not found")

            val methodData =
                waContactData
                    .findMethod {
                        matcher {
                            usingNumbers(-4, 0)
                            returnType(java.lang.Long.TYPE)
                        }
                    }.firstOrNull() ?: throw NoSuchFieldException("Number Method not found!")

            for (ufield in methodData.usingFields) {
                val field = ufield.field.getFieldInstance(classLoader)
                if (field.declaringClass == waContact && !field.type.isPrimitive) {
                    return@getField field
                }
            }
            null
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun loadSeenReceiptForStatus(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            requireMethod(
                "loadSeenReceiptForStatus",
                "StatusReceiptStore/insertOrUpdateSeenReceiptForStatus",
                findFirstMethodUsingStrings(
                    classLoader,
                    StringMatchType.Contains,
                    "StatusReceiptStore/insertOrUpdateSeenReceiptForStatus",
                ),
            )
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadOnConversationsListChangedMethod(classLoader: ClassLoader): Method? =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStringsFilter(
                classLoader,
                "com.whatsapp.conversationslist",
                StringMatchType.Contains,
                "onConversationsListChanged",
            )
        }

    fun loadMultiSelectionLimitInfoClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        usingStrings("MultiSelectionLimitInfo")
                    }
                }.single()
                .getInstance(classLoader)
        }

    fun loadOndispatchMessage(classLoader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(classLoader) {
            bridge
                .findMethod {
                    matcher {
                        anyOf {
                            match {
                                usingNumbers(419)
                            }
                            match {
                                usingStrings("ConnectionWriter/sendReadReceipts")
                            }
                        }
                        paramCount(1, 5)
                    }
                }.filter { !it.paramTypeNames.isEmpty() && it.paramTypeNames[0].contains("Message") }
                .map { it.getMethodInstance(classLoader) }
                .toTypedArray()
                .ifEmpty { error("onDispatchMessage method not found") }
        }

    fun loadLayoutClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            findFirstClassUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "BubbleRelativeLayout/ConversationRowText",
            )
                ?: error("BubbleRelativeLayout class not found")
        }

    fun loadTextStatusDataClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            val textDataList =
                bridge.findClass {
                    matcher {
                        usingStrings("TextData;")
                    }
                }
            if (textDataList.isEmpty()) {
                findFirstClassUsingName(classLoader, StringMatchType.EndsWith, "TextData")
            } else {
                textDataList[0].getInstance(classLoader)
            }
        }

    fun loadTextStatusComposerOnCreate(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val clazz =
                findFirstClassUsingName(
                    classLoader,
                    StringMatchType.EndsWith,
                    "TextStatusComposerFragment",
                )
            ReflectionUtils.findMethodUsingFilter(clazz) { method ->
                method.parameterCount == 2 &&
                    method.parameterTypes[0] === Bundle::class.java &&
                    method.parameterTypes[1] === View::class.java
            }
        }

    fun loadTextStatusData(classLoader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(classLoader) {
            val textData = loadTextStatusDataClass(classLoader)
            bridge
                .findMethod {
                    matcher {
                        addParamType(textData)
                    }
                }.filter { it.isMethod }
                .map { it.getMethodInstance(classLoader) }
                .toTypedArray()
        }

    fun loadTextStatusDataFStatus(classLoader: ClassLoader): Constructor<*> =
        UnobfuscatorCache.getInstance().getConstructor(classLoader) {
            val textData = loadTextStatusDataClass(classLoader)
            bridge
                .findMethod {
                    matcher {
                        paramTypes(textData.name, null, null, null, null, null, null)
                    }
                }.single()
                .getConstructorInstance(classLoader)
        }

    fun loadStickerColoredOutline(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            bridge
                .findMethod {
                    matcher {
                        paramTypes(
                            Bitmap::class.java,
                            ColorFilter::class.java,
                            Float::class.javaPrimitiveType,
                        )
                        returnType(Bitmap::class.java)
                    }
                }.single()
                .getMethodInstance(classLoader)
        }

    fun loadDrawSpanMethods(classLoader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(classLoader) {
            bridge
                .findClass {
                    matcher {
                        anyOf {
                            match {
                                superClass = "android.text.style.ImageSpan"
                            }
                            match {
                                superClass = "android.text.style.ReplacementSpan"
                            }
                        }
                    }
                }.findMethod {
                    matcher {
                        paramCount(9)
                        name = "draw"
                    }
                }.map { it.getMethodInstance(classLoader) }
                .toTypedArray()
        }

    fun loadGetSizeSpanMethods(classLoader: ClassLoader): Array<Method> =
        UnobfuscatorCache.getInstance().getMethods(classLoader) {
            bridge
                .findClass {
                    matcher {
                        anyOf {
                            match {
                                superClass = "android.text.style.ImageSpan"
                            }
                            match {
                                superClass = "android.text.style.ReplacementSpan"
                            }
                        }
                    }
                }.findMethod {
                    matcher {
                        paramCount(5)
                        name = "getSize"
                    }
                }.map { it.getMethodInstance(classLoader) }
                .toTypedArray()
        }

    fun loadProfilePhotoProtocolHelperClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        usingStrings("ProfilePhotoManager/sendGetSubProfilePhoto")
                    }
                }.single()
                .getInstance(classLoader)
        }

    @Throws(Exception::class)
    @JvmStatic
    fun loadPopupWindowMessageClass(classLoader: ClassLoader): Class<*> =
        UnobfuscatorCache.getInstance().getClass(classLoader) {
            bridge
                .findClass {
                    matcher {
                        usingStrings("MessageSelectionDropDownRecyclerView")
                        superClass = PopupWindow::class.java.name
                    }
                }.single()
                .getInstance(classLoader)
        }

    fun loadSwipeUpInGroupMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            val id = UnobfuscatorCache.getInstance().getOfuscateIDString("Keep holding to talk")
            bridge
                .findMethod {
                    matcher {
                        usingNumbers(id)
                    }
                }.single()
                .getMethodInstance(classLoader)
        }

    fun loadStatusPlaybackCurrentIndexField(loader: ClassLoader): Field {
        return UnobfuscatorCache.getInstance().getField(loader) {
            val methods =
                bridge.findMethod {
                    matcher {
                        usingStrings("playbackFragment/setPageActive no-messages ")
                    }
                }
            if (methods.isNotEmpty()) {
                val methodData = methods[0]
                val statusPlaybackClass = methodData.declaredClassName
                for (usingField in methodData.usingFields) {
                    val field = usingField.field
                    if (field.className == statusPlaybackClass && field.type.name == "int") {
                        return@getField field.getFieldInstance(loader)
                    }
                }
            }
            val statusPlaybackClass =
                findFirstClassUsingName(
                    loader,
                    StringMatchType.EndsWith,
                    "StatusPlaybackContactFragment",
                )
            val menuStatusMethod = loadMenuStatusMethod(loader)
            val menuMethodData = bridge.getMethodData(menuStatusMethod)
            if (menuMethodData != null) {
                for (usingField in menuMethodData.usingFields) {
                    val field = usingField.field
                    if (field.className == statusPlaybackClass.name && field.type.name == "int") {
                        return@getField field.getFieldInstance(loader)
                    }
                }
            }
            throw NoSuchFieldException("StatusPlayback current index field not found")
        }
    }

    fun loadStartOutgoingCallMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "outgoing-launch/cm-null-contact",
            ) ?: throw NoSuchMethodException("StartOutgoingCall method not found")
        }

    @JvmStatic
    @Throws(Exception::class)
    fun loadReadReceiptMethod(classLoader: ClassLoader): Method =
        UnobfuscatorCache.getInstance().getMethod(classLoader) {
            findFirstMethodUsingStrings(
                classLoader,
                StringMatchType.Contains,
                "ReadReceipts/sendReceiptForIncomingMessage",
            )
                ?: error("ReadReceiptMethod method not found")
        }
}
