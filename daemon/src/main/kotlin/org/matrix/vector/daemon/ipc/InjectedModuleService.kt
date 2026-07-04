package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.IXposedService
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.service.IRemotePreferenceCallback
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.ModuleDatabase
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.NotificationManager
import org.matrix.vector.daemon.system.PER_USER_RANGE

private const val TAG = "VectorInjectedModuleService"

class InjectedModuleService(private val packageName: String) : ILSPInjectedModuleService.Stub() {

  // Preference instances in hooked processes subscribe here for daemon-side updates.
  private val callbacks = ConcurrentHashMap<String, MutableSet<IRemotePreferenceCallback>>()

  override fun getFrameworkProperties(): Long {
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled) {
      prop = prop or IXposedService.PROP_RT_API_PROTECTION
    }
    return prop
  }

  override fun getScope(): List<String> =
      ConfigCache.getModuleScope(packageName)?.map { it.packageName } ?: emptyList()

  override fun requestScope(packages: List<String>, callback: IXposedScopeCallback?) {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    if (PreferenceStore.isScopeRequestBlocked(packageName)) {
      callback?.onScopeRequestFailed("Scope request blocked by user configuration")
      return
    }
    packages.forEach { pkg ->
      if (callback != null) NotificationManager.requestModuleScope(packageName, userId, pkg, callback)
    }
  }

  override fun removeScope(packages: List<String>) {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    packages.forEach { pkg ->
      runCatching { ModuleDatabase.removeModuleScope(packageName, pkg, userId) }
          .getOrElse { throw RemoteException(it.message) }
    }
  }

  override fun getRunningTargets(): List<HookedProcess> {
    val module = ConfigCache.getModuleByPackage(packageName) ?: return emptyList()
    return ApplicationService.getRunningTargets(module)
  }

  override fun hotReloadModule(targetId: Long, data: Bundle?, callback: IHotReloadCallback?) {
    runCatching {
          val module = ConfigCache.getModuleByPackage(packageName)
              ?: throw HotReloadUnsupportedException("Module $packageName is not enabled")
          if (module.file.moduleClassNames.size != 1) {
            throw HotReloadUnsupportedException("Hot reload requires exactly one Java entry class")
          }
          ApplicationService.hotReloadTarget(targetId, module, data)
          callback?.onHotReloadResult(IXposedService.HOT_RELOAD_SUCCEEDED, null)
        }
        .onFailure { throwable ->
          if (throwable is SecurityException) throw throwable
          val status = when (throwable) {
            is HotReloadInProgressException -> IXposedService.HOT_RELOAD_IN_PROGRESS
            is HotReloadProcessDiedException -> IXposedService.HOT_RELOAD_PROCESS_DIED
            is HotReloadUnsupportedException -> IXposedService.HOT_RELOAD_UNSUPPORTED
            else -> IXposedService.HOT_RELOAD_FAILED
          }
          callback?.onHotReloadResult(status, throwable.message)
        }
  }

  override fun requestRemotePreferences(
      group: String,
      callback: IRemotePreferenceCallback?
  ): Bundle {
    val bundle = Bundle()
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    bundle.putSerializable(
        "map", PreferenceStore.getModulePrefs(packageName, userId, group) as Serializable)

    if (callback != null) {
      val groupCallbacks = callbacks.getOrPut(group) { ConcurrentHashMap.newKeySet() }
      groupCallbacks.add(callback)
      runCatching { callback.asBinder().linkToDeath({ groupCallbacks.remove(callback) }, 0) }
          .onFailure { Log.w(TAG, "requestRemotePreferences linkToDeath failed", it) }
    }
    return bundle
  }

  @Suppress("DEPRECATION")
  override fun updateRemotePreferences(group: String, diff: Bundle) {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    val values = mutableMapOf<String, Any?>()
    if (diff.getBoolean("clear", false)) {
      PreferenceStore.deleteModulePrefs(packageName, userId, group)
    }
    (diff.getSerializable("delete") as? Set<*>)?.forEach { key ->
      if (key is String) values[key] = null
    }
    (diff.getSerializable("put") as? Map<*, *>)?.forEach { (key, value) ->
      if (key is String) values[key] = value
    }
    runCatching {
          PreferenceStore.updateModulePrefs(packageName, userId, group, values)
          onUpdateRemotePreferences(group, diff)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemotePreferences(group: String) {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    runCatching { PreferenceStore.deleteModulePrefs(packageName, userId, group) }
        .getOrElse { throw RemoteException(it.message) }
    onUpdateRemotePreferences(group, Bundle().apply { putBoolean("clear", true) })
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor {
    FileSystem.ensureModuleFilePath(path)
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          ParcelFileDescriptor.open(
              dir.resolve(path).toFile(),
              ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun getRemoteFileList(): Array<String> {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          dir.toFile().list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  override fun deleteRemoteFile(path: String): Boolean {
    FileSystem.ensureModuleFilePath(path)
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          FileSystem.resolveModuleDir(packageName, "files", userId, -1)
              .resolve(path)
              .toFile()
              .delete()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  // Keep hooked-process preference caches in sync with updates from the module app.
  fun onUpdateRemotePreferences(group: String, diff: Bundle) {
    val groupCallbacks = callbacks[group] ?: return
    for (callback in groupCallbacks) {
      runCatching { callback.onUpdate(diff) }.onFailure { groupCallbacks.remove(callback) }
    }
  }
}
