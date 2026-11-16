plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.ksp)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.core.ui"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK
        missingDimensionStrategy("variant", "proprietary", "libre")
    }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("beta") { initWith(getByName("release")) }
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(projects.core)
    implementation(projects.data)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation(libs.blurhash)
    implementation(libs.jellyfin.core)
    implementation(libs.timber)
}
