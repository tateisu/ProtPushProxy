import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.21"
    kotlin("plugin.serialization") version "1.8.0"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "jp.juggler.subwaytooter.appServerV2"
version = "0.0.1"

repositories {
    mavenCentral()
}


dependencies {
    testImplementation(kotlin("test"))

    val exposedVersion = "0.41.1"
    val h2Version = "2.1.214"
    val ktorVersion = "2.2.2"

    implementation("com.h2database:h2:$h2Version")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-double-receive:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.4.5")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("commons-codec:commons-codec:1.15")
    implementation("com.zaxxer:HikariCP:5.0.1")
    implementation("com.google.firebase:firebase-admin:8.2.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}
tasks.jar {
    manifest {
        // 警告よけ
        // > WARNING: sun.reflect.Reflection.getCallerClass is not supported. This will impact performance.
        attributes["Multi-Release"] = "true"
    }
}
