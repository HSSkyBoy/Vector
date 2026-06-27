/*
 * This file is part of Vector.
 *
 * Vector is a continuation and evolution of LSPosed.
 * Copyright (C) 2026 Vector Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.matrix.vector.daemon

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteException
import android.util.Log
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.system.MATCH_ALL_FLAGS
import org.matrix.vector.daemon.system.PER_USER_RANGE
import org.matrix.vector.daemon.system.getInstalledPackagesFromAllUsers
import org.matrix.vector.daemon.system.getPackageInfoCompat
import org.matrix.vector.daemon.system.isPackageAvailable
import org.matrix.vector.daemon.system.packageManager
import org.matrix.vector.daemon.system.userManager
import org.matrix.vector.daemon.utils.getRealUsers
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private const val TAG = "VectorPackageMonitor"

private const val MSG_UPDATE_ALL = 0
private const val MSG_UPDATE_PACKAGE = 1
private const val MSG_REMOVE_PACKAGE = 2

class PackageMonitorService {
    private val modules = ConcurrentHashMap<String, ModuleInfo>()
    private val packageApks = ConcurrentHashMap<String, MutableSet<String>>()
    private val handler: Handler

    companion object {
        @Volatile
        private var instance: PackageMonitorService? = null

        @Synchronized
        fun getInstance(): PackageMonitorService {
            var inst = instance
            if (inst == null) {
                inst = PackageMonitorService()
                instance = inst
            }
            return inst
        }
    }

    init {
        val handlerThread = HandlerThread("VectorPackageMonitor")
        handlerThread.start()
        handler = Handler(handlerThread.looper) { message ->
            when (message.what) {
                MSG_UPDATE_ALL -> {
                    updateAllPackages()
                    true
                }

                MSG_UPDATE_PACKAGE -> {
                    val request = message.obj as PackageRequest
                    updatePackage(request.packageName, request.userId)
                    true
                }

                MSG_REMOVE_PACKAGE -> {
                    val request = message.obj as PackageRequest
                    removePackage(request.packageName, request.userId, request.allUsers)
                    true
                }

                else -> false
            }
        }
    }

    // --- Public API ---

    fun updateAllPackagesAsync() {
        handler.obtainMessage(MSG_UPDATE_ALL).sendToTarget()
    }

    fun updatePackageAsync(packageName: String?, userId: Int) {
        if (packageName.isNullOrEmpty()) return
        handler.obtainMessage(
            MSG_UPDATE_PACKAGE,
            PackageRequest(packageName, userId, false)
        ).sendToTarget()
    }

    fun removePackageAsync(packageName: String?, userId: Int, allUsers: Boolean) {
        if (packageName.isNullOrEmpty()) return
        handler.obtainMessage(
            MSG_REMOVE_PACKAGE,
            PackageRequest(packageName, userId, allUsers)
        ).sendToTarget()
    }

    @Throws(RemoteException::class)
    fun getModuleInfo(packageName: String?, userId: Int): ModuleInfo? {
        if (packageName.isNullOrEmpty() || packageName == "lspd") return null
        synchronized(this) {
            val moduleInfo = modules[packageName]
            if (isValidModuleInfo(moduleInfo, userId)) {
                return moduleInfo
            }
            return updatePackageLocked(packageName, userId).moduleInfo
        }
    }

    fun updatePackage(packageName: String?, userId: Int): PackageState {
        if (packageName.isNullOrEmpty() || packageName == "lspd")
            return PackageState.empty(packageName)
        synchronized(this) {
            return try {
                updatePackageLocked(packageName, userId)
            } catch (e: RemoteException) {
                Log.w(TAG, "get package info of $packageName", e)
                PackageState.empty(packageName)
            }
        }
    }

    fun removePackage(packageName: String?, userId: Int, allUsers: Boolean): PackageState {
        if (packageName.isNullOrEmpty() || packageName == "lspd")
            return PackageState.empty(packageName)
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
            } catch (e: RemoteException) {
                Log.w(TAG, "get package info of $packageName", e)
                PackageState(packageName, oldModule, oldModule != null)
            }
        }
    }

    // --- Internal ---

    private fun updateAllPackages() {
        synchronized(this) {
            val pm = packageManager ?: return
            val packageNames = ConcurrentHashMap.newKeySet<String>()
            try {
                val allPackages = pm.getInstalledPackagesFromAllUsers(
                    MATCH_ALL_FLAGS or PackageManager.GET_META_DATA,
                    filterNoProcess = false
                )
                for (pkg in allPackages) {
                    if (pkg.applicationInfo == null) continue
                    packageNames.add(pkg.packageName)
                    val userId = pkg.applicationInfo!!.uid / PER_USER_RANGE
                    updatePackageLocked(pkg, userId)
                }
                modules.keys.retainAll(packageNames)
                packageApks.keys.retainAll(packageNames)
            } catch (e: RemoteException) {
                Log.w(TAG, "update all packages cache", e)
            }
        }
    }

    @Throws(RemoteException::class)
    private fun updatePackageLocked(packageName: String, userId: Int): PackageState {
        val packageInfo = findPackageInfo(packageName, userId) ?: run {
            modules.remove(packageName)
            packageApks.remove(packageName)
            return PackageState.empty(packageName)
        }
        return updatePackageLocked(packageInfo, userId)
    }

    @Throws(RemoteException::class)
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

    @Throws(RemoteException::class)
    private fun findPackageInfo(packageName: String, userId: Int): PackageInfo? {
        val pm = packageManager ?: return null
        if (userId >= 0) {
            val packageInfo = findPackageInfoForUser(packageName, userId)
            if (packageInfo != null) return packageInfo
        }
        val um = userManager ?: return null
        for (user in um.getRealUsers()) {
            val packageInfo = findPackageInfoForUser(packageName, user.id)
            if (packageInfo != null) return packageInfo
        }
        return null
    }

    @Throws(RemoteException::class)
    private fun findPackageInfoForUser(packageName: String, userId: Int): PackageInfo? {
        val pm = packageManager ?: return null
        if (!pm.isPackageAvailable(packageName, userId, true)) return null
        return pm.getPackageInfoCompat(
            packageName, MATCH_ALL_FLAGS or PackageManager.GET_META_DATA, userId
        )
    }

    private fun parseModule(packageInfo: PackageInfo): ModuleInfo? {
        val applicationInfo = packageInfo.applicationInfo ?: return null
        val apks = collectApks(applicationInfo)
        packageApks[packageInfo.packageName] = apks.toMutableSet()

        for (apk in apks) {
            if (apk == null) {
                Log.w(TAG, "${packageInfo.packageName} has null apk path")
                continue
            }
            val moduleInfo = parseModuleApk(apk, packageInfo, applicationInfo)
            if (moduleInfo != null) return moduleInfo
        }
        return null
    }

    private fun parseModuleApk(
        apk: String,
        packageInfo: PackageInfo,
        applicationInfo: ApplicationInfo
    ): ModuleInfo? {
        try {
            ZipFile(apk).use { zip ->
                // Modern module: java_init.list
                if (zip.getEntry("META-INF/xposed/java_init.list") != null) {
                    return ModuleInfo(
                        packageName = packageInfo.packageName,
                        apkPath = apk,
                        appId = applicationInfo.uid,
                        applicationInfo = applicationInfo,
                        packageInfo = packageInfo,
                        legacy = false,
                        installedUsers = collectInstalledUsers(packageInfo.packageName)
                    )
                }
                // Legacy module: xposed_init in assets
                if (zip.getEntry("assets/xposed_init") != null) {
                    return ModuleInfo(
                        packageName = packageInfo.packageName,
                        apkPath = apk,
                        appId = applicationInfo.uid,
                        applicationInfo = applicationInfo,
                        packageInfo = packageInfo,
                        legacy = true,
                        installedUsers = collectInstalledUsers(packageInfo.packageName)
                    )
                }
            }
        } catch (_: Exception) {
            // ZIP read failure — not a module
        }
        return null
    }

    private fun isXposedModule(packageInfo: PackageInfo, moduleInfo: ModuleInfo?): Boolean {
        if (moduleInfo != null) return true
        val applicationInfo = packageInfo.applicationInfo ?: return false

        // Check manifest metadata
        if (applicationInfo.metaData?.containsKey("xposedminversion") == true) return true

        // Check APK content for java_init.list
        for (apk in collectApks(applicationInfo)) {
            if (apk == null) continue
            try {
                ZipFile(apk).use { zip ->
                    if (zip.getEntry("META-INF/xposed/java_init.list") != null) return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun collectApks(applicationInfo: ApplicationInfo): List<String> {
        val apks = mutableListOf<String>()
        applicationInfo.sourceDir?.let { apks.add(it) }
        applicationInfo.splitSourceDirs?.forEach { apks.add(it) }
        return apks
    }

    @Throws(RemoteException::class)
    private fun collectInstalledUsers(packageName: String): Set<Int> {
        val users = mutableSetOf<Int>()
        val pm = packageManager ?: return users
        val um = userManager ?: return users
        for (user in um.getRealUsers()) {
            if (pm.isPackageAvailable(packageName, user.id, true)) {
                users.add(user.id)
            }
        }
        return users
    }

    private fun isValidModuleInfo(moduleInfo: ModuleInfo?, userId: Int): Boolean {
        if (moduleInfo == null) return false
        if (moduleInfo.apkPath.isEmpty()) return false
        if (!java.io.File(moduleInfo.apkPath).exists()) return false
        if (userId == -1) return true
        return moduleInfo.installedUsers.contains(userId)
    }

    // --- Data classes ---

    data class ModuleInfo(
        val packageName: String,
        val apkPath: String,
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

    private class PackageRequest(
        val packageName: String,
        val userId: Int,
        val allUsers: Boolean
    )
}
