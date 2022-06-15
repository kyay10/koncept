@file:Suppress("UnstableApiUsage")

import com.gradle.publish.MavenCoordinates
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.utils.addToStdlib.cast

plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
  id("com.gradle.plugin-publish")
  id("convention.publication")
}



dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":kotlin-plugin")
  packageName(project.group.toString().replace("-", ""))
  buildConfigField(
    "String",
    "KOTLIN_PLUGIN_ID",
    "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\""
  )
  buildConfigField("String", "KOTLIN_PLUGIN_GROUP", "\"${project.group}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_NAME", "\"${project.name}\"")
  buildConfigField("String", "KOTLIN_PLUGIN_VERSION", "\"${project.version}\"")
  val preludeProject = project(":prelude")
  buildConfigField("String", "PRELUDE_LIBRARY_GROUP", "\"${preludeProject.group}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_NAME", "\"${preludeProject.name}\"")
  buildConfigField("String", "PRELUDE_LIBRARY_VERSION", "\"${preludeProject.version}\"")
}

java {
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs += "-Xinline-classes"
}
val pluginDescription =
  "Kotlin FIR plugin to support C++ like concepts, DI, Higher-kinded types, and so much more!"
val pluginName = "Koncept"
val pluginDisplayName = "Koncept FIR compiler plugin"

gradlePlugin {
  plugins {
    create(pluginName) {
      id = "io.github.kyay10.koncept"
      displayName = pluginDisplayName
      description = pluginDescription
      implementationClass = "io.github.kyay10.koncept.KonceptGradlePlugin"
    }
  }
}
pluginBundle {
  website = "https://github.com/kyay10/koncept"
  vcsUrl = website
  description = pluginDescription

  version = rootProject.version
  (plugins) {
    pluginName {
      displayName = pluginDisplayName
      // SEO go brrrrrrr...
      tags = listOf(
        "kotlin",
        "concept",
        "dependency-injection",
        "higher kinded types",
        "typeclass",
        "tooling",
        "functional",
        "higher order function",
        "fp",
        "functional programming",
        "category theory"
      )
      version = rootProject.version.cast()
    }
  }
  val mavenCoordinatesConfiguration = { coords: MavenCoordinates ->
    coords.groupId = group.cast()
    coords.artifactId = name
    coords.version = version.cast()
  }

  mavenCoordinates(mavenCoordinatesConfiguration)
}
