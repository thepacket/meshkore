plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}
