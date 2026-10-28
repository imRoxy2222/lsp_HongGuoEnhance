plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

// 获取当前分支 commit 次数作为版本号来源 (兼容 Configuration Cache)
// 输出非整数或执行异常时返回 null, 由 getOrElse 回退为 1
abstract class GitCommitCountSource : ValueSource<Int, GitCommitCountSource.Params> {
    interface Params : ValueSourceParameters {
        val projectDir: Property<String>
    }

    override fun obtain(): Int? {
        return try {
            val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .directory(File(parameters.projectDir.get()))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }
}

val commitCount = providers.of(GitCommitCountSource::class) {
    parameters.projectDir.set(rootDir.absolutePath)
}.getOrElse(1)

// 构建期生成 META-INF/xposed/module.prop (含 versionCode, LSPosed 仓库/管理器据此识别更新版本)
val modulePropDir = layout.buildDirectory.dir("generated/moduleProp")

abstract class GenerateModulePropTask : DefaultTask() {
    @get:Input
    val commitCountValue: Property<Int> = project.objects.property(Int::class.java)

    @get:OutputDirectory
    val outputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun generate() {
        val file = outputDir.file("META-INF/xposed/module.prop").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            id=io.github.imroxy2222.lsp_hongguoenhance
            name=我不赤石
            version=${commitCountValue.get()}
            versionCode=${commitCountValue.get()}
            author=imroxy2222
            description=AI总结红果短剧类型Tag, 防止赤石
            minApiVersion=102
            targetApiVersion=102
            staticScope=true
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
    }
}

val generateModuleProp = tasks.register("generateModuleProp", GenerateModulePropTask::class) {
    commitCountValue.set(commitCount)
    outputDir.set(modulePropDir)
}

android {
    namespace = "io.github.imroxy2222.lsp_hongguoenhance"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.imroxy2222.lsp_hongguoenhance"
        minSdk = 29
        targetSdk = 37
        versionCode = commitCount
        versionName = commitCount.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // 注意: 不要设置 packageScope。该 DSL 会把作用域外的类重打包到根包
                // (如 kotlin 类被改名为根包 ig)，且父类访问级别未随之放宽,
                // 运行时在 androidx.startup 初始化时抛 IllegalAccessError 导致 app 无法启动。
                // 默认全量 R8 混淆(类名保留原包前缀)是经过验证的稳定路径。
            }
            // CI 签名: 环境变量齐全时对 release APK 签名, 否则产出未签名 APK (CI 回退用 debug 包)
            val storeFilePath = System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                signingConfig = signingConfigs.create("ci") {
                    storeFile = rootProject.file(storeFilePath)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// 通过 Variant API 把生成的 module.prop 注册为打包资源 (自动携带任务依赖)
androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            generateModuleProp,
            GenerateModulePropTask::outputDir
        )
    }
}
