package org.matrix.vector.impl.core

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashSet
import java.util.zip.ZipFile
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPInjectedModuleService
import org.lsposed.lspd.service.IRemotePreferenceCallback
import org.lsposed.lspd.util.Utils.Log
import org.matrix.vector.impl.VectorContext
import org.matrix.vector.impl.VectorLifecycleManager
import org.matrix.vector.impl.utils.VectorModuleClassLoader
import org.matrix.vector.nativebridge.NativeAPI

/**
 * Responsible for loading modules into the target process. Handles ClassLoader isolation and
 * injects the framework context into the module instances.
 */
object VectorModuleManager {

    private const val TAG = "VectorModuleManager"

    /**
     * Loads a module APK, instantiates its entry classes, and binds them to the Vector framework.
     */
    fun loadModule(module: Module, isSystemServer: Boolean, processName: String): Boolean {
        try {
            Log.d(TAG, "Loading module ${module.packageName}")
            val preLoadedApk =
                module.file
                    ?: run {
                        Log.e(TAG, "Module ${module.packageName} has no preloaded APK payload")
                        return false
                    }

            val nativeLibraryDir = prepareNativeLibraryDir(module)
            val librarySearchPath = buildLibrarySearchPath(module, nativeLibraryDir)

            // Create the isolated ClassLoader for the module
            val initLoader = XposedModule::class.java.classLoader
            val moduleClassLoader =
                VectorModuleClassLoader.loadApk(
                    module.apkPath,
                    preLoadedApk.preLoadedDexes,
                    librarySearchPath,
                    initLoader,
                )

            // Security/Integrity Check: Ensure the module isn't bundling its own API classes
            if (
                moduleClassLoader.loadClass(XposedModule::class.java.name).classLoader !==
                    initLoader
            ) {
                Log.e(TAG, "The Xposed API classes are compiled into ${module.packageName}")
                return false
            }

            // Create the Context that will be injected into the module
            val moduleApplicationInfo =
                module.applicationInfo
                    ?: android.content.pm.ApplicationInfo().apply {
                        packageName = module.packageName
                        sourceDir = module.apkPath
                        publicSourceDir = module.apkPath
                        uid = module.appId
                    }
            if (nativeLibraryDir != null) {
                moduleApplicationInfo.nativeLibraryDir = nativeLibraryDir.absolutePath
            }
            val vectorContext =
                VectorContext(
                    packageName = module.packageName,
                    applicationInfo = moduleApplicationInfo,
                    service = module.service ?: EmptyInjectedModuleService,
                )

            // Native entrypoints must be ready before Java onModuleLoaded() can call into JNI.
            initializeNativeEntrypoints(module, nativeLibraryDir, preLoadedApk.moduleLibraryNames)

            // Instantiate the module entry classes
            for (className in preLoadedApk.moduleClassNames) {
                runCatching {
                        val moduleClass = moduleClassLoader.loadClass(className)
                        Log.v(TAG, "Loading class $moduleClass")

                        if (!XposedModule::class.java.isAssignableFrom(moduleClass)) {
                            Log.e(TAG, "Class $moduleClass does not extend XposedModule, skipping.")
                            return@runCatching
                        }

                        val constructor = moduleClass.getDeclaredConstructor()
                        constructor.isAccessible = true
                        val moduleInstance = constructor.newInstance() as XposedModule

                        // Attach the framework context to the module
                        moduleInstance.attachFramework(vectorContext)

                        // Register the active module to receive future lifecycle events
                        VectorLifecycleManager.activeModules.add(moduleInstance)

                        // Trigger the initial onModuleLoaded callback
                        moduleInstance.onModuleLoaded(
                            object : ModuleLoadedParam {
                                override fun isSystemServer(): Boolean = isSystemServer

                                override fun getProcessName(): String = processName
                            }
                        )
                    }
                    .onFailure { e -> Log.e(TAG, "Failed to instantiate class $className", e) }
            }

            Log.d(TAG, "Loaded module ${module.packageName} successfully.")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal error loading module ${module.packageName}", e)
            return false
        }
    }

    private object EmptyInjectedModuleService : ILSPInjectedModuleService.Stub() {
        override fun getFrameworkProperties(): Long = 0L

        override fun requestRemotePreferences(
            group: String?,
            callback: IRemotePreferenceCallback?,
        ): Bundle {
            return Bundle().apply { putSerializable("map", HashMap<String, Any>()) }
        }

        override fun openRemoteFile(path: String?): ParcelFileDescriptor? = null

        override fun getRemoteFileList(): Array<String> = emptyArray()
    }

    private fun initializeNativeEntrypoints(
        module: Module,
        nativeLibraryDir: File?,
        moduleLibraryNames: List<String>,
    ) {
        for (libraryName in moduleLibraryNames) {
            var initialized = false
            for (fileName in nativeLibraryFileNames(libraryName)) {
                NativeAPI.recordNativeEntrypoint(fileName)
                for (candidate in buildNativeInitCandidates(module, nativeLibraryDir, fileName)) {
                    if (NativeAPI.initializeNativeEntrypoint(fileName, candidate)) {
                        Log.i(TAG, "Prepared native library $fileName from $candidate")
                        initialized = true
                        break
                    }
                }
                if (initialized) {
                    break
                }
            }
        }
    }

    private fun prepareNativeLibraryDir(module: Module): File? {
        return runCatching {
                val apkFile = File(module.apkPath)
                val parent = apkFile.parentFile ?: return null
                val moduleRoot = File(parent, "native/${module.packageName.replace('.', '_')}")
                val targetDir = File(moduleRoot, "${apkFile.lastModified()}-${apkFile.length()}")
                if (
                    targetDir.isDirectory &&
                        targetDir.listFiles { file -> file.name.endsWith(".so") }?.isNotEmpty() == true
                ) {
                    return targetDir
                }

                moduleRoot.deleteRecursively()
                targetDir.mkdirs()

                val abis =
                    if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS
                    else Build.SUPPORTED_32_BIT_ABIS
                ZipFile(apkFile).use { zip ->
                    for (abi in abis) {
                        val prefix = "lib/$abi/"
                        var extractedAny = false
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val name = entry.name
                            if (entry.isDirectory || !name.startsWith(prefix) || !name.endsWith(".so")) {
                                continue
                            }

                            val outFile = File(targetDir, File(name).name)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output -> input.copyTo(output) }
                            }
                            outFile.setReadable(true, false)
                            outFile.setExecutable(true, false)
                            outFile.setWritable(false, false)
                            extractedAny = true
                        }
                        if (extractedAny) {
                            Log.i(
                                TAG,
                                "Prepared native libraries for ${module.packageName} $abi at $targetDir",
                            )
                            return targetDir
                        }
                    }
                }

                targetDir.deleteRecursively()
                null
            }
            .onFailure { Log.e(TAG, "Failed to prepare native dir for ${module.packageName}", it) }
            .getOrNull()
    }

    private fun buildLibrarySearchPath(module: Module, nativeLibraryDir: File?): String {
        return buildString {
            if (nativeLibraryDir != null) {
                append(nativeLibraryDir.absolutePath).append(File.pathSeparator)
            }
            val abis =
                if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            for (abi in abis) {
                append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator)
            }
        }
    }

    private fun buildNativeInitCandidates(
        module: Module,
        nativeLibraryDir: File?,
        fileName: String,
    ): List<String> {
        val candidates = LinkedHashSet<String>()
        if (nativeLibraryDir != null) {
            candidates.add(File(nativeLibraryDir, fileName).absolutePath)
        }
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        for (abi in abis) {
            candidates.add("${module.apkPath}!/lib/$abi/$fileName")
            val normalizedAbi = abi.lowercase()
            if (normalizedAbi != abi) {
                candidates.add("${module.apkPath}!/lib/$normalizedAbi/$fileName")
            }
        }
        return candidates.toList()
    }

    private fun nativeLibraryFileNames(libraryName: String): List<String> {
        val names = LinkedHashSet<String>()
        names.add(libraryName)
        if (!libraryName.startsWith("lib") || !libraryName.endsWith(".so")) {
            names.add(System.mapLibraryName(libraryName))
        }
        return names.toList()
    }
}
