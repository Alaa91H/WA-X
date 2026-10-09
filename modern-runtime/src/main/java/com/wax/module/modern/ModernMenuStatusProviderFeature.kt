package com.wax.module.modern

import android.content.Context
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy MenuStatusProvider status-playback menu bus.
 *
 * Resolves the same hook targets from repo-derived anchors: the menu method
 * using the `menuitem_conversations_message_contact` resource ID, the menu
 * manager class from the void method using
 * `"MenuPopupHelper cannot be used without an anchor"`, the playback
 * fragment classes by their proven name suffixes, and the status list field
 * typed to [List] on the contact fragment. The current-index field is
 * best-effort, exactly as in the legacy resolver.
 *
 * The bus exposes the raw status list plus the playback fragment so
 * `StatusItemWpp` interpretation stays with each consumer migration
 * (StatusDownload, SeenTick, DeleteStatus). No obfuscated member name is
 * used and no resource ID is hardcoded: the ID is read from the target's
 * own resources.
 *
 * Always-on infrastructure with no user toggle: no in-WhatsApp control.
 */
object ModernMenuStatusProviderFeature {
    const val FEATURE_ID = "menu_status_provider"
    const val MENU_RESOURCE_NAME = "menuitem_conversations_message_contact"
    const val MENU_RESOURCE_TYPE = "id"
    const val ANCHOR_MENU_MANAGER = "MenuPopupHelper cannot be used without an anchor"
    const val ANCHOR_SET_PAGE_ACTIVE = "playbackFragment/setPageActive no-messages "
    const val PLAYBACK_BASE_SUFFIX = "StatusPlaybackBaseFragment"
    const val PLAYBACK_CONTACT_SUFFIX = "StatusPlaybackContactFragment"
    private const val TAG = "WA-X StatusMenu102"

    enum class Outcome {
        INSTALLED,
        RESOURCE_ID_MISSING,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        ERROR,
    }

    /** Provider surface, mirroring the legacy bus with raw payloads. */
    interface Provider {
        fun addMenu(menu: Menu, statusData: StatusData): MenuItem?
        fun onClick(item: MenuItem, statusData: StatusData)
    }

    /** Raw status list plus the playback fragment that owns it. */
    class StatusData internal constructor(
        internal val statusList: List<*>,
        internal val fragment: Any,
    )

    private val providers = CopyOnWriteArraySet<Provider>()

    @Volatile
    private var currentIndexField: Field? = null

    @JvmStatic
    fun register(provider: Provider) {
        providers.add(provider)
    }

    @JvmStatic
    fun unregister(provider: Provider) {
        providers.remove(provider)
    }

    /** Exposed for consumers and diagnostics; may be unset until the hook runs. */
    @JvmStatic
    var latestStatusData: StatusData? = null
        private set

    fun currentIndex(statusData: StatusData): Int {
        val field = currentIndexField ?: return 0
        return try {
            field.getInt(statusData.fragment)
        } catch (unreadable: Throwable) {
            0
        }
    }

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
    ): Outcome {
        val menuResourceId = try {
            target.resources.getIdentifier(
                MENU_RESOURCE_NAME, MENU_RESOURCE_TYPE, target.packageName)
        } catch (failure: Throwable) {
            Log.w(TAG, "Status menu resource lookup failed", failure)
            0
        }
        if (menuResourceId <= 0) return Outcome.RESOURCE_ID_MISSING
        return try {
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                val menuMethods = dex.findMethod { matcher ->
                    matcher.addUsingNumber(menuResourceId)
                }
                if (menuMethods.isEmpty()) return Outcome.RESOLVER_MISSING
                val menuMethod = try {
                    menuMethods[0].getMethodInstance(target.classLoader)
                } catch (resolveFailure: Throwable) {
                    Log.w(TAG, "Status menu method unresolvable", resolveFailure)
                    return Outcome.RESOLVER_MISSING
                }
                val baseFragment = findSuffixClass(dex, target.classLoader, PLAYBACK_BASE_SUFFIX)
                    ?: return Outcome.RESOLVER_MISSING
                val contactFragment = findSuffixClass(dex, target.classLoader, PLAYBACK_CONTACT_SUFFIX)
                    ?: return Outcome.RESOLVER_MISSING
                val statusListField = findFieldOfExtendType(contactFragment, List::class.java)
                    ?: return Outcome.RESOLVER_MISSING
                statusListField.isAccessible = true
                currentIndexField = resolveCurrentIndexField(dex, target.classLoader)
                val menuManagerClass = dex.findMethod { matcher ->
                    matcher.addUsingString(ANCHOR_MENU_MANAGER, StringMatchType.Contains)
                }.firstOrNull { it.returnType == Void.TYPE }?.declaredClassName?.let { name ->
                    runCatching { Class.forName(name, false, target.classLoader) }.getOrNull()
                }
                val menuField = menuManagerClass?.let { findFieldOfExtendType(it, Menu::class.java) }

                hooks.installFeature(FEATURE_ID, listOf(
                    ModernHookRegistry.Registration("status.menu") {
                        val handle = ModernHookBridge(framework).intercept(
                            menuMethod, "wax.modern.status_menu.provider") { chain ->
                            val result = chain.proceed()
                            publish(chain.thisObject, chain.args.firstOrNull(),
                                baseFragment, contactFragment, statusListField, menuManagerClass,
                                menuField)
                            result
                        }
                        ModernHookRegistry.Handle { handle.unhook() }
                    }))
                Outcome.INSTALLED
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Status menu bus unavailable", failure)
            Outcome.ERROR
        }
    }

    private fun publish(
        receiver: Any?,
        firstArg: Any?,
        baseFragment: Class<*>,
        contactFragment: Class<*>,
        statusListField: Field,
        menuManagerClass: Class<*>?,
        menuField: Field?,
    ) {
        if (providers.isEmpty()) return
        val declaringFields = receiver?.javaClass?.declaredFields ?: emptyArray()
        val fieldObjects = declaringFields.mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(receiver)
            }.getOrNull()
        }
        val fragment = if (receiver != null && contactFragment.isInstance(receiver)) {
            receiver
        } else {
            fieldObjects.firstOrNull { baseFragment.isInstance(it) } ?: return
        }
        val menu = firstArg as? Menu
            ?: fieldObjects.firstOrNull { menuManagerClass?.isInstance(it) == true }
                ?.let { manager -> menuField?.let { readMenu(it, manager) } }
            ?: return
        val listStatus = runCatching { statusListField.get(fragment) as? List<*> }.getOrNull() ?: return
        val statusData = StatusData(listStatus, fragment)
        latestStatusData = statusData
        for (provider in providers) {
            try {
                val item = provider.addMenu(menu, statusData) ?: continue
                item.setOnMenuItemClickListener { clicked ->
                    try {
                        provider.onClick(clicked, statusData)
                    } catch (clickFailure: Throwable) {
                        Log.w(TAG, "Status menu provider click failed", clickFailure)
                    }
                    true
                }
            } catch (providerFailure: Throwable) {
                Log.w(TAG, "Status menu provider failed", providerFailure)
            }
        }
    }

    private fun readMenu(field: Field, owner: Any): Menu? = try {
        field.isAccessible = true
        field.get(owner) as? Menu
    } catch (unreadable: Throwable) {
        null
    }

    /** Best-effort current-index field, same two-step evidence as the legacy resolver. */
    private fun resolveCurrentIndexField(
        dex: DexKitBridge,
        classLoader: ClassLoader,
    ): Field? {
        val playbackMethod = try {
            dex.findMethod { matcher ->
                matcher.usingStrings(ANCHOR_SET_PAGE_ACTIVE)
            }.firstOrNull()
        } catch (failure: Throwable) {
            Log.w(TAG, "Current-index anchor unresolvable", failure)
            null
        } ?: return null
        val playbackClassName = playbackMethod.declaredClassName
        return playbackMethod.usingFields.firstOrNull { used ->
            used.field.className == playbackClassName && used.field.type.name == "int"
        }?.field?.let { field ->
            runCatching { field.getFieldInstance(classLoader) }.getOrNull()
        }?.apply { isAccessible = true }
    }

    private fun findSuffixClass(
        dex: DexKitBridge,
        classLoader: ClassLoader,
        suffix: String,
    ): Class<*>? {
        val resolvedName = try {
            dex.findClass { finder ->
                finder.matcher().className(suffix, StringMatchType.EndsWith)
            }.firstOrNull()?.getInstance(classLoader)?.name
        } catch (failure: Throwable) {
            Log.w(TAG, "Playback fragment $suffix unresolvable", failure)
            null
        } ?: return null
        return try {
            Class.forName(resolvedName, false, classLoader)
        } catch (failure: Throwable) {
            Log.w(TAG, "Playback fragment $suffix not loadable", failure)
            null
        }
    }

    private fun findFieldOfExtendType(owner: Class<*>?, type: Class<*>?): Field? {
        if (owner == null || type == null) return null
        return owner.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }
    }
}