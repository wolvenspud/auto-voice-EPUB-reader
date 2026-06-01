plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
}
