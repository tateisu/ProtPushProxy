import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "jp.juggler.fcmv1sender"
version = "0.1"

repositories {
    google()
    mavenCentral()
}

dependencies {
    testApi(kotlin("test"))
    api( "org.jetbrains.kotlin:kotlin-stdlib")
    api( "org.jetbrains.kotlinx:kotlinx-cli:0.3.4")
    api( "com.google.firebase:firebase-admin:8.2.0")
    api( "org.slf4j:slf4j-simple:1.7.36")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveBaseName.set("fcmV1Sender")
        mergeServiceFiles()
        manifest {
            attributes(mapOf("Main-Class" to  "main.MainKt"))
        }
    }
}

tasks {
    build {
        dependsOn(shadowJar)
    }
}
