package org.matrix.vector.impl

import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.util.Log

/**
 * Exactly-once gate for the legacy `handleLoadPackage` dispatch.
 *
 * Two independent producers can deliver the same package to `XC_LoadPackage.callAll`: this
 * framework's [org.matrix.vector.impl.hookers.LoadedApkCreateCLHooker] and the rootless host's own
 * bootstrap fallback (NPatch's `LSPLoader.dispatchPackageLoadedIfNeeded`). Neither
 * `XCallback.callAll` nor `XposedInit.loadedPackagesInProcess` de-duplicates, so without a shared
 * claim both paths fire and every legacy module's `handleLoadPackage` runs twice in one process.
 *
 * A doubled dispatch is harmless for a module that guards its own entry point, but it is fatal for
 * one that installs native hooks or does its `JNI_OnLoad` work there: the second pass re-installs
 * inline hooks over instructions that are already hooked, and the process dies with a native
 * tombstone instead of a Java exception.
 *
 * Lives in this module rather than `legacy` because `legacy` already depends on `xposed`, so the
 * dependency cannot point back; both the framework hook and the host bootstrap share one class
 * loader, so the claim is visible to both either way.
 */
object LegacyDispatchGate {

    private const val TAG = "NPatch"

    private val dispatched: MutableSet<String> = ConcurrentHashMap.newKeySet(1)

    /**
     * Atomically claims the legacy dispatch for [packageName].
     *
     * @return `true` for the first caller only, which is the one that must dispatch; `false` when
     *   this package was already delivered in this process.
     */
    @JvmStatic
    fun claim(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) {
            // No key to deduplicate on; let the caller dispatch rather than swallowing it.
            return true
        }
        val first = dispatched.add(packageName)
        if (first) {
            // High-signal marker: seeing this in logcat proves the framework hook dispatched the
            // legacy callback, and its absence proves the host bootstrap fallback had to.
            Log.i(TAG, "legacy handleLoadPackage dispatched by framework hook: $packageName")
        } else {
            Log.i(TAG, "legacy handleLoadPackage already dispatched, skipping duplicate: $packageName")
        }
        return first
    }

    /** Whether [claim] already succeeded for [packageName] in this process. */
    @JvmStatic
    fun isDispatched(packageName: String?): Boolean =
        packageName != null && dispatched.contains(packageName)
}