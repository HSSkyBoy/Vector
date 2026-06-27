package org.matrix.vector.daemon.monitor

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.matrix.vector.daemon.ipc.ModuleService
import org.matrix.vector.daemon.system.PackageService
import org.matrix.vector.daemon.system.SystemService
import org.matrix.vector.daemon.system.UserService
import org.matrix.vector.daemon.utils.ConfigFileManager
import java.io.IOException
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private const val TAG = "VectorPackageMonitor"

internal class PackageMonitorService {
    companion object {
        private const val MSG_UPDATE_ALL = 0
        private const val MSG_UPDATE_PACKAGE = 1
        private const val MSG_REMOVE_PACKAGE = 2

        @Volatile
        private var instance: PackageMonitorService? = null

        fun getInstance(): PackageMonitorService {
            return instance ?: synchronized(this) {
                instance ?: PackageMonitorService().also { instance = it }
            }
        }
    }

    private val modules = ConcurrentHashMap<String, ModuleInfo>()
    private val packageApks = ConcurrentHashMap<String, MutableSet<String>>()
    private val handler: Handler

    init {
        val handlerThread = HandlerThread("VectorPackageMonitorService")
        handlerThread.start()
        handler = Handler(handlerThread.looper) { msg ->
            when (msg.what) {
                MSG_UPDATE_ALL -> updateAllPackages()
                MSG_UPDATE_PACKAGE -> {
                    val request = msg.obj as PackageRequest
                    updatePackage(request.packageName, request.userId)
                }
                MSG_REMOVE_PACKAGE -> {
                    val request = msg.obj as PackageRequest
                    removePackage(request.packageName, request.userId, request.allUsers)
                }
                else -> return@Handler false
            }
            true
        }
    }

    fun updateAllPackagesAsync() {
        handler.obtainMessage(MSG_UPDATE_ALL).sendToTarget()
    }

    fun updatePackageAsync(packageName: String?, userId: Int) {
        if (packageName == null) return
        handler.obtainMessage(MSG_UPDATE_PACKAGE, PackageRequest(packageName, userId, false)).sendToTarget()
    }

    fun removePackageAsync(packageName: String?, userId: Int, allUsers: Boolean) {
        if (packageName == null) return
        handler.obtainMessage(MSG_REMOVE_PACKAGE, PackageRequest(packageName, userId, allUsers)).sendToTarget()
    }

    fun getModuleInfo(packageName: String?): ModuleInfo? {
        if (packageName == null || packageName == "lspd") return null
        synchronized(this) {
            val moduleInfo = modules[packageName]
            if (isValidModuleInfo(moduleInfo)) {
                return moduleInfo
            }
            return try {
                updatePackageLocked(packageName, -1).moduleInfo
            } catch (e: Exception) {
                Log.w(TAG, "Get package info of $packageName", e)
                null
            }
        }
    }

    fun updatePackage(packageName: String?, userId: Int): PackageState {
        if (packageName == null || packageName == "lspd") return PackageState.empty(packageName)
        synchronized(this) {
            return try {
                updatePackageLocked(packageName, userId)
            } catch (e: Exception) {
                Log.w(TAG, "Get package info of $packageName", e)
                PackageState.empty(packageName)
            }
        }
    }

    fun removePackage(packageName: String?, userId: Int, allUsers: Boolean): PackageState {
        if (packageName == null || packageName == "lspd") return PackageState.empty(packageName)
        synchronized(this) {
            val oldModule = modules[packageName]
            if (allUsers) {
                modules.remove(packageName)
                packageApks.remove(packageName)
                return PackageState(packageName, oldModule, oldModule != null)
            }
            return try {
                val state = updatePackageLocked(packageName, -1)
                if (state.xposedModule) state
                else PackageState(packageName, oldModule, oldModule != null)
            } catch (e: Exception) {
                Log.w(TAG, "Get package info of $packageName", e)
                PackageState(packageName, oldModule, oldModule != null)
            }
        }
    }

    private fun updateAllPackages() {
        synchronized(this) {
            if (!PackageService.isAlive() || !UserService.isAlive()) return
            val packageNames = ConcurrentHashMap.newKeySet<String>()
            try {
                for (user in UserService.getUsers()) {
                    for (packageInfo in PackageService.getInstalledPackages(
                            PackageService.MATCH_ALL_FLAGS or PackageManager.GET_META_DATA,
                            user.id)) {
                        if (packageInfo == null || packageInfo.applicationInfo == null) continue
                        if (!PackageService.isPackageAvailable(packageInfo.packageName, user.id, true)) continue
                        packageNames.add(packageInfo.packageName)
                        updatePackageLocked(packageInfo, user.id)
                    }
                }
                modules.keys.retainAll(packageNames)
                packageApks.keys.retainAll(packageNames)
            } catch (e: Exception) {
                Log.w(TAG, "update package monitor cache", e)
            }
        }
    }

    private fun updatePackageLocked(packageName: String, userId: Int): PackageState {
        val packageInfo = findPackageInfo(packageName, userId)
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            modules.remove(packageName)
            packageApks.remove(packageName)
            return PackageState.empty(packageName)
        }
        return updatePackageLocked(packageInfo, userId)
    }

    private fun updatePackageLocked(packageInfo: PackageInfo?, userId: Int): PackageState {
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            return PackageState.empty(null)
        }
        val packageName = packageInfo.packageName
        val moduleInfo = parseModule(packageInfo)
        val xposedModule = isXposedModule(packageInfo, moduleInfo)
        if (moduleInfo != null) {
            modules[packageName] = moduleInfo
        } else {
            modules.remove(packageName)
        }
        return PackageState(packageName, moduleInfo, xposedModule)
    }

    private fun findPackageInfo(packageName: String, userId: Int): PackageInfo? {
        if (userId >= 0) {
            findPackageInfoForUser(packageName, userId)?.let { return it }
        }
        if (!UserService.isAlive()) return null
        for (user in UserService.getUsers()) {
            findPackageInfoForUser(packageName, user.id)?.let { return it }
        }
        return null
    }

    private fun findPackageInfoForUser(packageName: String, userId: Int): PackageInfo? {
        if (!PackageService.isPackageAvailable(packageName, userId, true)) return null
        val packageInfo = PackageService.getPackageInfo(
            packageName,
            PackageService.MATCH_ALL_FLAGS or PackageManager.GET_META_DATA,
            userId)
        if (packageInfo?.applicationInfo == null) return null
        return packageInfo
    }

    private fun parseModule(packageInfo: PackageInfo): ModuleInfo? {
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val apks = collectApks(applicationInfo)
        packageApks[packageInfo.packageName] = HashSet(apks)

        for (apk in apks) {
            if (apk == null) {
                Log.w(TAG, "${packageInfo.packageName} has null apk path???")
                continue
            }
            parseModuleApk(apk, packageInfo, applicationInfo)?.let { return it }
        }
        return null
    }

    private fun parseModuleApk(apk: String, packageInfo: PackageInfo, applicationInfo: ApplicationInfo): ModuleInfo? {
        try {
            ZipFile(SystemService.toGlobalNamespace(apk)).use { zip ->
                if (ConfigFileManager.readModernModuleProperties(zip) != null) {
                    return ModuleInfo(
                        packageInfo.packageName, apk, applicationInfo.uid,
                        applicationInfo, packageInfo, false, collectInstalledUsers(packageInfo.packageName))
                }
                if (ConfigFileManager.requiresModernModuleLoading(zip)) {
                    return null
                }
                if (zip.getEntry("assets/xposed_init") != null) {
                    return ModuleInfo(
                        packageInfo.packageName, apk, applicationInfo.uid,
                        applicationInfo, packageInfo, true, collectInstalledUsers(packageInfo.packageName))
                }
            }
        } catch (_: IOException) {
        }
        return null
    }

    private fun isXposedModule(packageInfo: PackageInfo, moduleInfo: ModuleInfo?): Boolean {
        if (moduleInfo != null) return true
        val applicationInfo = packageInfo.applicationInfo ?: return false
        if (applicationInfo.metaData != null && applicationInfo.metaData.containsKey("xposedminversion")) {
            return true
        }
        for (apk in collectApks(applicationInfo)) {
            if (apk == null) continue
            try {
                ZipFile(SystemService.toGlobalNamespace(apk)).use { zip ->
                    if (zip.getEntry("META-INF/xposed/java_init.list") != null) {
                        return true
                    }
                }
            } catch (_: IOException) {
            }
        }
        return false
    }

    private fun collectApks(applicationInfo: ApplicationInfo): List<String> {
        val apks = ArrayList<String>()
        if (applicationInfo.sourceDir != null) {
            apks.add(applicationInfo.sourceDir)
        }
        if (applicationInfo.splitSourceDirs != null) {
            Collections.addAll(apks, *applicationInfo.splitSourceDirs)
        }
        return apks
    }

    private fun collectInstalledUsers(packageName: String): Set<Int> {
        val users = HashSet<Int>()
        if (!UserService.isAlive()) return users
        for (user in UserService.getUsers()) {
            if (PackageService.isPackageAvailable(packageName, user.id, true)) {
                users.add(user.id)
            }
        }
        return users
    }

    private fun isValidModuleInfo(moduleInfo: ModuleInfo?): Boolean {
        if (moduleInfo == null) return false
        if (moduleInfo.apkPath == null || !SystemService.existsInGlobalNamespace(moduleInfo.apkPath)) return false
        return true
    }

    data class ModuleInfo(
        val packageName: String,
        val apkPath: String?,
        val appId: Int,
        val applicationInfo: ApplicationInfo,
        val packageInfo: PackageInfo,
        val legacy: Boolean,
        val installedUsers: Set<Int>
    )

    data class PackageState(
        val packageName: String?,
        val moduleInfo: ModuleInfo?,
        val xposedModule: Boolean
    ) {
        companion object {
            fun empty(packageName: String?) = PackageState(packageName, null, false)
        }
    }

    private data class PackageRequest(
        val packageName: String,
        val userId: Int,
        val allUsers: Boolean
    )
}
