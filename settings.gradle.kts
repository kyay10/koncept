rootProject.name = "koncept"
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    mavenLocal()
  }
}
include(":gradle-plugin")
include(":kotlin-plugin")
include(":kotlin-plugin-native")
include("prelude")
includeBuild("convention-plugins")
include("maven-plugin")
