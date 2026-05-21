import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.ncorti.ktfmt.gradle.tasks.KtfmtFormatTask
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository

plugins {
    alias(libs.plugins.agp.lib) apply false
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.ktfmt)
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    }
}

val npatchRoot = rootDir.resolve("..").canonicalFile
val npatchBuildFile = npatchRoot.resolve("build.gradle.kts")
val npatchVersionName =
    runCatching {
            Regex("""val\s+verName\s+by\s+extra\("([^"]+)"\)""")
                .find(npatchBuildFile.readText())
                ?.groupValues
                ?.getOrNull(1)
        }
        .getOrNull()
        ?: "1.0"

val npatchVersionCode =
    runCatching {
            FileRepository(npatchRoot.resolve(".git")).use { repo ->
                val refId = repo.refDatabase.exactRef("refs/remotes/origin/miuix")?.objectId
                if (refId != null) Git(repo).log().add(refId).call().count().toString() else "1"
            }
        }
        .getOrDefault("1")

val versionCodeProvider by extra(providers.provider { npatchVersionCode })
val versionNameProvider by extra(providers.provider { npatchVersionName })

val injectedPackageName by extra("com.android.shell")
val injectedPackageUid by extra(2000)
val defaultManagerPackageName by extra("org.lsposed.manager")

val androidTargetSdkVersion by extra(36)
val androidMinSdkVersion by extra(27)
val androidBuildToolsVersion by extra("36.1.0")
val androidCompileSdkVersion by extra(36)
val androidCompileNdkVersion by extra("29.0.13113456")
val androidSourceCompatibility by extra(JavaVersion.VERSION_21)
val androidTargetCompatibility by extra(JavaVersion.VERSION_21)

subprojects {
    plugins.withType(AndroidBasePlugin::class.java) {
        extensions.configure(CommonExtension::class.java) {
            compileSdk = androidCompileSdkVersion
            ndkVersion = androidCompileNdkVersion
            buildToolsVersion = androidBuildToolsVersion

            buildFeatures { buildConfig = true }
            externalNativeBuild {
                cmake {
                    version = "3.29.8+"
                    buildStagingDirectory = layout.buildDirectory.get().asFile
                }
            }

            defaultConfig {
                minSdk = androidMinSdkVersion
                ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) }

                if (this is ApplicationDefaultConfig) {
                    targetSdk = androidTargetSdkVersion

                    versionCode = versionCodeProvider.get().toInt()
                    versionName = versionNameProvider.get()
                }

                val flags =
                    listOf(
                        "-DVERSION_CODE=${versionCodeProvider.get()}",
                        "-DVERSION_NAME='\"${versionNameProvider.get()}\"'",
                    )

                val args =
                    listOf(
                        "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                        "-DVECTOR_ROOT=${rootDir.absolutePath}",
                        // Enforce 16 KB page size alignment for Android 15+ compatibility
                        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                        "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    )

                externalNativeBuild {
                    cmake {
                        cFlags.addAll(flags)
                        cppFlags.addAll(flags)
                        arguments.addAll(args)
                    }
                }
            }

            buildTypes {
                getByName("release") {
                    externalNativeBuild {
                        cmake {
                            arguments.add(
                                "-DDEBUG_SYMBOLS_PATH=${
                                layout.buildDirectory.dir("symbols").get().asFile.absolutePath
                            }"
                            )
                        }
                    }
                }
            }

            lint {
                abortOnError = true
                checkReleaseBuilds = false
            }

            compileOptions {
                sourceCompatibility = androidSourceCompatibility
                targetCompatibility = androidTargetCompatibility
            }
        }
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}

tasks.register<KtfmtFormatTask>("format") {
    source = project.fileTree(rootDir)
    include(
        "*.gradle.kts",
        "*/build.gradle.kts",
        "hiddenapi/*/build.gradle.kts",
        "services/*-service/build.gradle.kts",
    )
    dependsOn(":daemon:ktfmtFormat")
    dependsOn(":xposed:ktfmtFormat")
    dependsOn(":zygisk:ktfmtFormat")
}

ktfmt { kotlinLangStyle() }
