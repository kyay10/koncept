buildscript {
  extra["kotlin_plugin_id"] = "io.github.kyay10.koncept"
}
plugins {
  `maven-publish`
  kotlin("jvm") version Dependencies.kotlin apply false
  id("org.jetbrains.dokka") version "1.6.10" apply false
  id("com.gradle.plugin-publish") version "0.21.0" apply false
  id("com.github.gmazzo.buildconfig") version "3.0.3" apply false
}
val rootGroup = "io.github.kyay10"
group = rootGroup
val rootVersion = "0.1.0"
version = rootVersion
val rootName = name
subprojects {
  repositories {
    mavenCentral()
    mavenLocal()
    maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
  }
  // Naming scheme used by jitpack
  group = "$rootGroup.$rootName"
  version = rootVersion
}
