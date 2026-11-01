import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("rikkihub.android.library.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.ai"

    sourceSets {
        named("main") {
            kotlin.srcDir(rootDir.resolve("cc-disguise/src/main/java"))
            kotlin.srcDir(rootDir.resolve("claudelife/src/main/java"))
        }
        named("test") {
            kotlin.srcDir(rootDir.resolve("cc-disguise/src/test/java"))
            kotlin.srcDir(rootDir.resolve("claudelife/src/test/java"))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":common"))

    // Compose
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)

    // okhttp
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.okhttp.logging)

    // kotlinx
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)

    // apache commons text (StringEscapeUtils in GoogleProvider)
    implementation(libs.commons.text)

    // tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
