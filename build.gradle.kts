plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "li.cil.vox2mc"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3") {
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.guava:guava:33.3.1-jre")
}

application {
    mainClass = "li.cil.vox2mc.MainKt"
}
