plugins {
    alias(libs.plugins.kotlinJvm)
    application
    `maven-publish`
}

val semver: String by project

group = "li.cil.vox2mc"
version = semver

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.clikt) {
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna")
    }
    implementation(libs.gson)
    implementation(libs.guava)
}

application {
    mainClass = "li.cil.vox2mc.MainKt"
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name = "vox2mc"
                description = "Turns MagicaVoxel models into Minecraft block models, one quad soup and one sprite each."
                url = "https://github.com/fnuecke/vox2mc"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/fnuecke/vox2mc/blob/main/LICENSE"
                    }
                }
                developers {
                    developer {
                        id = "fnuecke"
                        name = "Florian Nücke"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/fnuecke/vox2mc.git"
                    developerConnection = "scm:git:ssh://git@github.com/fnuecke/vox2mc.git"
                    url = "https://github.com/fnuecke/vox2mc"
                }
            }
        }
    }
    repositories {
        val mavenRepoDir = (project.findProperty("mavenRepoDir") as String?)?.takeIf { it.isNotBlank() }
        if (mavenRepoDir != null) {
            maven {
                name = "StaticMavenRepo"
                url = uri(file(mavenRepoDir))
            }
        }
    }
}
