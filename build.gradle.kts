import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.izzel.taboolib.gradle.*

plugins {
    java
    id("io.izzel.taboolib") version "2.0.39"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

group = "cn.stingcraft"
version = "1.0.0"

repositories {
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
    maven("https://repo.tabooproject.org/repository/releases/")
}

taboolib {
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitUI)
        install(MinecraftChat)
        install(Database)
        install(DatabasePlayer)
        install("bukkit-nms-stable")
    }
    description {
        name = "BuildFlight"
        contributors {
            name("BuildFlight")
        }
        dependencies {
            name("Dominion").optional(true)
        }
    }
    version {
        taboolib = "6.3.0-0e3a911"
    }
}

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly(kotlin("stdlib"))
    // 仅编译期引用，不打包；Dominion 未安装时由 DominionHook 安全跳过。
    compileOnly("cn.lunadeer:DominionAPI:4.9.6")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}
