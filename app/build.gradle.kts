import com.android.build.api.dsl.Packaging
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

// ---------------------------------------------------------------------------
// 交付诊断字段：
//   git commit / dirty 标记 / 构建时间与来源。
// 约束：
// - 只注入编译期可确定的值；任何一项取不到一律记 "unavailable"。
// - APK sha256 绝不写进 BuildConfig（包内容与自身哈希会形成循环依赖）。
//   APK 打包+签名完成后由 scripts/apk_build_info.py 计算并另存 sidecar
//   build-info.json。
// - configuration cache 兼容：GitBuildInfoSource 是 ValueSource，Gradle 每次
//   构建都会重新执行 obtain() 校验指纹（9.5.0 实测），commit/dirty 始终新鲜，
//   且不会出现 build-script 直接调用外部进程的告警。
// - BUILD_TIME 只取显式构建输入（-Prikkihub.buildTime=ISO8601 或
//   SOURCE_DATE_EPOCH=unix秒），未提供时固定为 "unavailable"。这样纯测试
//   等不涉及 BuildConfig 产物的构建不会因时间戳无意义地使整个任务图失效。
// ---------------------------------------------------------------------------
abstract class GitBuildInfoSource : ValueSource<String, GitBuildInfoSource.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: Property<String>
    }

    @get:Inject
    abstract val execOps: ExecOperations

    override fun obtain(): String {
        val repo = File(parameters.repoDir.get())
        fun git(vararg args: String): String? {
            val out = ByteArrayOutputStream()
            val result = try {
                execOps.exec {
                    workingDir = repo
                    commandLine(listOf("git") + args)
                    standardOutput = out
                    errorOutput = ByteArrayOutputStream()
                    isIgnoreExitValue = true
                }
            } catch (e: Exception) {
                return null
            }
            if (result.exitValue != 0) return null
            return out.toString(StandardCharsets.UTF_8).trim()
        }
        val commit = git("rev-parse", "HEAD") ?: return "unavailable"
        val dirty = when (val status = git("status", "--porcelain")) {
            null -> return "unavailable"
            "" -> "false"
            else -> "true"
        }
        return "$commit;$dirty"
    }
}

private fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "me.rerere.rikkihub"
    compileSdk = 37

    val coexist = project.findProperty("rikkihub.coexist")?.toString() == "true"

    defaultConfig {
        applicationId = if (coexist) "me.rerere.rikkihub.subagent" else "me.rerere.rikkihub"
        minSdk = 26
        targetSdk = 37
        versionCode = 222
        versionName = "2.32.0"

        // 各变体应用名：共存包 RikkiTomo / 发布包 RikkiRana / debug RikkiUmi
        manifestPlaceholders["appName"] = "Rikkihub"

        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")
        buildConfigField("String", "VERSION_CODE", "\"$versionCode\"")

        // 交付诊断字段（见上方注释）。git 信息经 ValueSource 采集，保证
        // configuration cache 兼容且每次构建指纹新鲜。
        val gitInfo = providers.of(GitBuildInfoSource::class.java) {
            parameters.repoDir.set(rootDir.absolutePath)
        }.get()
        val gitCommit = gitInfo.substringBefore(';').takeIf { it.isNotBlank() } ?: "unavailable"
        val gitDirty = gitInfo.substringAfter(';', "unavailable")

        val buildTimeOverride = providers.gradleProperty("rikkihub.buildTime").orNull?.trim()
        val sourceDateEpoch = providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.trim()
        val epochSeconds = sourceDateEpoch?.toLongOrNull()
        val buildTime: String
        val buildTimeSource: String
        when {
            !buildTimeOverride.isNullOrEmpty() -> {
                buildTime = buildTimeOverride
                buildTimeSource = "rikkihub.buildTime"
            }
            epochSeconds != null -> {
                buildTime = Instant.ofEpochSecond(epochSeconds).toString()
                buildTimeSource = "SOURCE_DATE_EPOCH"
            }
            else -> {
                buildTime = "unavailable"
                buildTimeSource = "unavailable"
            }
        }
        val buildSource = if (providers.environmentVariable("CI").orNull?.isNotBlank() == true) "ci" else "local"

        buildConfigField("String", "GIT_COMMIT", gitCommit.asBuildConfigString())
        buildConfigField("String", "GIT_DIRTY", gitDirty.asBuildConfigString())
        buildConfigField("String", "BUILD_TIME", buildTime.asBuildConfigString())
        buildConfigField("String", "BUILD_TIME_SOURCE", buildTimeSource.asBuildConfigString())
        buildConfigField("String", "BUILD_SOURCE", buildSource.asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // libtermux.so attach bridge: Termux's emulator attaches to workspace PTY masters (no fork here).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            // Only release builds produce per-ABI APKs; debug assemble outputs a single APK.
            //noinspection WrongGradleMethod
            val taskNames = gradle.startParameter.taskNames.map { it.lowercase() }
            val isReleaseBuild = taskNames.any {
                it.contains("release") || it.contains("bundle") || it.contains("buildall")
            }
            isEnable = isReleaseBuild && taskNames.none { it.contains("bundle") }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = rootProject.file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["appName"] = "RikkiRana"
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
            }
            packaging {
                resources {
                    excludes += setOf(
                        "META-INF/*.version",
                        "META-INF/INDEX.LIST",
                        "META-INF/LICENSE*"
                    )
                }
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = if (coexist) "RikkiTomo" else "RikkiUmi"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        named("main") {
            kotlin.srcDir(rootDir.resolve("cc-disguise/src/app/java"))
            kotlin.srcDir(rootDir.resolve("claudelife/src/app/java"))
        }
    }
    androidResources {
        generateLocaleConfig = true
        // Keep only the languages the app itself translates; locales that dependencies (AndroidX, GMS, ...) ship are dropped
        localeFilters += listOf("en", "de", "ja", "ko", "ko-rKR", "ru", "zh", "zh-rTW")
    }
    // AGP lint crashes on non-compiled PSI in web-settings (SettingsWebDto.kt); skip vital release lint
    lint {
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            // Netty modules carry distinct diagnostic version keys in one shared resource.
            merges += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            useLegacyPackaging = true
            // The app's own attach-bridge libtermux.so takes precedence over the Termux AAR copy.
            pickFirsts += "lib/*/libtermux.so"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleRelease", "bundleRelease")
    description = "Build both APK and AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.exifinterface)

    // media3（媒体创作里播放生成的视频）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // 画板（androidx.ink：低延迟落笔、压感与自定义笔刷；自定义笔刷接口 1.0.0 里还没公开，用 alpha）
    implementation(libs.androidx.ink.authoring.compose)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.geometry)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view)
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // jetbrains markdown parser
    implementation(libs.jetbrains.markdown)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)


    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing + CameraX (qrcode scanner)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // lucide icons
    implementation(libs.lucide.icons)
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // FloatingX (floating window)
    // https://github.com/Petterpx/FloatingX
    implementation(libs.floatingx.app)
    implementation(libs.floatingx.compose)

    // jsoup (HTML parsing in rich text rendering)
    implementation(libs.jsoup)

    // JLatexMath
    // https://github.com/rikkahub/jlatexmath-android
    implementation(libs.jlatexmath)
    implementation(libs.jlatexmath.font.greek)
    implementation(libs.jlatexmath.font.cyrillic)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":highlight"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":workspace"))
    implementation(project(":oauth"))
    implementation(project(":mediagen"))
    implementation(kotlin("reflect"))

    // tests
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // Route-level JVM tests for the web boundary (auth gate, origin, files).
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
}
