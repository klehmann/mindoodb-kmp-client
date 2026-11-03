plugins {
    kotlin("multiplatform") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "com.mindoo.db"
version = "0.0.1"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"
val coroutinesVersion = "1.9.0"
val serializationVersion = "1.7.3"

kotlin {
    // Any JDK from 21 on builds this; the bytecode and API level stay at Java 21.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmMain {
            dependencies {
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            }
        }
    }
}
