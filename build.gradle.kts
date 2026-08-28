plugins {
    kotlin("jvm") version "2.2.20"
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
    implementation("com.github.ajalt.clikt:clikt:5.0.3") {
        exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-jna")
    }
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.guava:guava:33.3.1-jre")
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
