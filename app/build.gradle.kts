import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.matcheckmobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.matcheckmobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.21"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"https://matcheck.fvds.ru/\"")
    }

    // Подписной ключ для release-сборок. Используем системный debug.keystore
    // как стабильный ключ — это сознательное решение для текущего масштаба
    // (десяток инспекторов, раздача через GH Releases, не Play Store). Файл
    // должен лежать в ~/.android/debug.keystore — он создаётся Android Studio
    // автоматически при первой debug-сборке. Бэкап файла обязателен: при
    // потере не получится обновлять уже установленные APK
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // На устройстве debug-сборка живёт отдельным приложением
            // (com.example.matcheckmobile.dev) и не конфликтует с release.
            // На своём планшете разработчик может иметь обе одновременно —
            // dev для итераций через Run app, release-копию для проверки
            // «глазами инспектора» перед выкаткой.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // На лаунчере подпись «Матбаланс.dev», чтобы визуально отличать
            // от production-«Матбаланс» (по applicationId Android уже их
            // разделяет, подпись делает то же самое наглядным для пользователя).
            resValue("string", "app_name", "Матбаланс.dev")

            buildConfigField("String", "API_BASE_URL", "\"https://matcheck.fvds.ru/\"")
            // In-app updater отключён в debug — Android Studio Run app
            // сам ставит свежий бинарь, лишние проверки и баннеры
            // «доступно обновление» только мешают.
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "false")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Подпись на лаунчере у инспекторов — «Матбаланс».
            resValue("string", "app_name", "Матбаланс")

            buildConfigField("String", "API_BASE_URL", "\"https://matcheck.fvds.ru/\"")
            // GH Releases раздаётся через публичный side-репо
            // hoperlex/matcheck.mobile-releases: /releases/latest/download
            // редиректит на ассеты последнего тега, поэтому URL манифеста
            // фиксированный и менять его при выкатке новой версии не нужно.
            buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                "\"https://github.com/hoperlex/matcheck.mobile-releases/releases/latest/download/manifest.json\""
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // resValue в build types (см. блок buildTypes выше) задаёт app_name
        // отдельно для debug-сборки («su10.dev») и release («su10»).
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.sse)

    implementation(libs.mlkit.document.scanner)
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ──────────────────────────── publishGithubRelease ─────────────────────────────
// Релиз = одна команда: ./gradlew publishGithubRelease.
//
// Под капотом:
//   1. assembleRelease — собирает signed app-release.apk (signingConfigs.release
//      использует ~/.android/debug.keystore, см. блок android выше).
//   2. Считает SHA-256 итогового APK.
//   3. Пишет рядом manifest.json со ссылкой на этот APK в публичном
//      side-репо hoperlex/matcheck.mobile-releases.
//   4. Дёргает `gh release create v<versionName> ...` — GitHub CLI должен
//      быть аутентифицирован заранее одноразовым `gh auth login`.
//
// versionCode и versionName поднимаются разработчиком вручную в блоке
// android.defaultConfig перед запуском таски — иначе клиенты на старой
// версии не увидят обновление (manifest.versionCode не превысит
// BuildConfig.VERSION_CODE).
val publishGithubRelease by tasks.registering {
    group = "matcheck"
    description = "Собрать signed release-APK и опубликовать как GitHub release " +
        "в hoperlex/matcheck.mobile-releases"

    dependsOn("assembleRelease")

    doLast {
        val apkFile = layout.buildDirectory
            .file("outputs/apk/release/app-release.apk")
            .get()
            .asFile
        if (!apkFile.exists()) {
            throw GradleException("APK не найден: ${apkFile.absolutePath}. " +
                "Запусти ./gradlew assembleRelease отдельно и проверь подпись.")
        }

        val versionName = android.defaultConfig.versionName
            ?: throw GradleException("versionName не задан в defaultConfig")
        val versionCode = android.defaultConfig.versionCode
            ?: throw GradleException("versionCode не задан в defaultConfig")

        // SHA-256 — клиент сверяет после скачивания APK; защита от
        // повреждения в пути и от подмены ассета в репо.
        val sha256 = MessageDigest.getInstance("SHA-256").let { digest ->
            apkFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    digest.update(buf, 0, r)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        val tag = "v$versionName"
        val apkUrl = "https://github.com/hoperlex/matcheck.mobile-releases/" +
            "releases/download/$tag/app-release.apk"

        // Минимальный manifest. changelog нарочно пустой по умолчанию —
        // если хочется аннотировать релиз, заполни вручную через GitHub UI
        // в release notes; клиент тоже сможет видеть позже, когда добавим
        // парсинг release-notes-API.
        val manifestJson = """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "apkUrl": "$apkUrl",
              "apkSizeBytes": ${apkFile.length()},
              "apkSha256": "$sha256",
              "changelog": "",
              "minSupportedVersionCode": 1,
              "releasedAt": "${OffsetDateTime.now(ZoneOffset.UTC)}"
            }
        """.trimIndent()

        val manifestFile = layout.buildDirectory
            .file("outputs/apk/release/manifest.json")
            .get()
            .asFile
        manifestFile.writeText(manifestJson)

        println("APK: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
        println("SHA-256: $sha256")
        println("Manifest: ${manifestFile.absolutePath}")
        println("Tag: $tag")
        println()

        val repo = "hoperlex/matcheck.mobile-releases"

        // gh-обёртка: ProcessBuilder + redirectErrorStream собирает stdout и
        // stderr `gh` в один поток. inheritIO под Gradle daemon съедает stderr,
        // поэтому при падении в Build Output не видно реальной причины (HTTP 422,
        // tag exists, repository empty и пр.). Возвращаем (exitCode, output),
        // чтобы и залогировать, и при ошибке вшить вывод в текст исключения.
        fun runGh(vararg args: String): Pair<Int, String> {
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            return p.waitFor() to out
        }

        // Идемпотентность: повторный запуск той же версии (или до-заливка после
        // частично упавшего релиза) не должен падать на 422 «ReleaseAsset.name
        // already exists». Если релиз с этим тегом уже есть — перезаписываем
        // ассеты через `gh release upload --clobber`, иначе создаём заново.
        val releaseExists = runGh("gh", "release", "view", tag, "--repo", repo).first == 0

        val action = if (releaseExists) "upload" else "create"
        val (exitCode, output) = if (releaseExists) {
            println("Релиз $tag уже существует — перезаливаю ассеты (--clobber)...")
            runGh(
                "gh", "release", "upload", tag,
                apkFile.absolutePath,
                manifestFile.absolutePath,
                "--repo", repo,
                "--clobber",
            )
        } else {
            println("Создаю GitHub release $tag...")
            runGh(
                "gh", "release", "create", tag,
                apkFile.absolutePath,
                manifestFile.absolutePath,
                "--repo", repo,
                "--title", "su10 $versionName",
                "--notes", "Release $versionName (versionCode=$versionCode)",
            )
        }
        if (output.isNotBlank()) println(output)
        if (exitCode != 0) {
            throw GradleException(
                "gh release $action exit=$exitCode\n$output\n" +
                    "Проверь авторизацию (gh auth status) и наличие репо $repo.",
            )
        }

        println("Релиз $tag опубликован.")
        println("  https://github.com/hoperlex/matcheck.mobile-releases/releases/tag/$tag")
    }
}
