package org.matrix.vector.impl.core

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.VectorContext

/**
 * Exposes the libxposed service API to module code loaded in a hooked process.
 *
 * The upstream service provider delivers this binder to the module app. Hooked processes do not
 * host that provider, so [VectorModuleManager] injects this adapter into the module classloader and
 * forwards its operations to the daemon through [ILSPInjectedModuleService].
 */
internal class VectorXposedService(
    private val packageName: String,
    private val context: VectorContext,
    private val injectedService: ILSPInjectedModuleService?,
) : IXposedService.Stub() {

    override fun getApiVersion(): Int = IXposedService.LIB_API

    override fun getFrameworkName(): String = context.frameworkName

    override fun getFrameworkVersion(): String = context.frameworkVersion

    override fun getFrameworkVersionCode(): Long = context.frameworkVersionCode

    override fun getFrameworkProperties(): Long = context.frameworkProperties

    override fun getScope(): List<String> = injectedService?.scope ?: emptyList()

    override fun requestScope(
        packages: MutableList<String>?,
        callback: IXposedScopeCallback?,
    ) {
        if (packages == null || callback == null) return
        injectedService?.requestScope(packages, callback)
            ?: callback.onScopeRequestFailed("Injected module service unavailable")
    }

    override fun removeScope(packages: MutableList<String>?) {
        if (packages != null) injectedService?.removeScope(packages)
    }

    override fun getRunningTargets(): MutableList<HookedProcess> =
        injectedService?.runningTargets?.toMutableList() ?: mutableListOf()

    override fun hotReloadModule(
        targetId: Long,
        data: Bundle?,
        callback: IHotReloadCallback?,
    ) {
        injectedService?.hotReloadModule(targetId, data, callback)
            ?: callback?.onHotReloadResult(
                IXposedService.HOT_RELOAD_UNSUPPORTED,
                "Injected module service unavailable",
            )
    }

    override fun requestRemotePreferences(group: String?): Bundle {
        if (group == null) return Bundle().apply {
            putSerializable("map", java.util.HashMap<String, Any>())
        }
        return try {
            injectedService?.requestRemotePreferences(group, null)
                ?: Bundle().apply { putSerializable("map", java.util.HashMap<String, Any>()) }
        } catch (e: RemoteException) {
            Log.e(
                TAG,
                "Failed to request remote preferences for $packageName / $group",
                e,
            )
            Bundle().apply { putSerializable("map", java.util.HashMap<String, Any>()) }
        }
    }

    override fun updateRemotePreferences(group: String?, diff: Bundle?) {
        if (group == null || diff == null) return
        try {
            (injectedService ?: throw RemoteException("Injected module service unavailable"))
                .updateRemotePreferences(group, diff)
        } catch (e: RemoteException) {
            throw RemoteException(
                "Failed to update remote preferences for $packageName / $group: ${e.message}"
            )
        } catch (e: UnsupportedOperationException) {
            throw RemoteException(
                "Remote preference updates are not supported: ${e.message}"
            )
        }
    }

    override fun deleteRemotePreferences(group: String?) {
        if (group == null) return
        try {
            (injectedService ?: throw RemoteException("Injected module service unavailable"))
                .deleteRemotePreferences(group)
        } catch (e: RemoteException) {
            throw RemoteException(
                "Failed to delete remote preferences for $packageName / $group: ${e.message}"
            )
        }
    }

    override fun listRemoteFiles(): Array<String> {
        return try {
            injectedService?.remoteFileList ?: emptyArray()
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to list remote files for $packageName", e)
            emptyArray()
        }
    }

    override fun openRemoteFile(name: String?): ParcelFileDescriptor {
        if (name.isNullOrEmpty()) throw RemoteException("File name must not be empty")
        try {
            return injectedService?.openRemoteFile(name)
                ?: throw RemoteException("Injected module service unavailable")
        } catch (e: RemoteException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("Cannot open remote file: $name — ${e.message}")
        }
    }

    override fun deleteRemoteFile(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        return try {
            injectedService?.deleteRemoteFile(name) ?: false
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to delete remote file: $name", e)
            false
        }
    }

    companion object {
        private const val TAG = "VectorXposedService"
    }
}
