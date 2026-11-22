plugins {
    id("rikkihub.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.jtokkit)
    implementation(libs.quickjs)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
