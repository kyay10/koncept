@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = "0.1.1"

plugins {
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
  id("convention.publication")

  kotlin("kapt")
}

sourceSets {
  main {
    resources.setSrcDirs(listOf("resources"))
  }
  test {
    java.setSrcDirs(listOf("src/test", "src/test-gen"))
    resources.setSrcDirs(listOf("src/testData"))
  }
}


dependencies {
  testImplementation("org.jetbrains.kotlin:kotlin-compiler:${Dependencies.kotlinCompiler}")
  implementation("org.ow2.asm:asm:9.3")
  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${Dependencies.kotlinCompiler}")
  compileOnly("org.jetbrains.kotlin:kotlin-annotation-processing-embeddable:${Dependencies.kotlinCompiler}")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  kapt("com.google.auto.service:auto-service:1.0.1")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0.1")

  // Needed for running tests since the tests inherit out classpath
  testImplementation(project(":prelude"))

  testImplementation(kotlin("test-junit5"))
  testImplementation(platform("org.junit:junit-bom:5.8.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.platform:junit-platform-commons")
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.junit.platform:junit-platform-runner")
  testImplementation("org.junit.platform:junit-platform-suite-api")
  //testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${Dependencies.kotlinCompiler}")
  testImplementation("org.jetbrains.kotlin:kotlin-reflect:${Dependencies.kotlinCompiler}")
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework:${Dependencies.kotlinCompiler}")
  //testImplementation("com.github.tschuchortdev:kotlin-compile-testing:1.4.9")
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-test")
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-script-runtime")
  testRuntimeOnly("org.jetbrains.kotlin:kotlin-annotations-jvm")
}
tasks.test {
  dependsOn(project(":prelude").tasks.getByName("jvmJar"))
  useJUnitPlatform()
  doFirst {
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")
  }

  testLogging {
    events("passed", "skipped", "failed")
  }
}
buildConfig {
  val packageSanitized = group.toString().replace("-", "")
  packageName(packageSanitized)
  buildConfigField(
    "String",
    "CONCEPT_FQNAME",
    "\"$packageSanitized.Concept\""
  )
  buildConfigField(
    "String",
    "NOTHING_FUN_FQNAME",
    "\"$packageSanitized.nothing\""
  )
  buildConfigField(
    "String",
    "KOTLIN_PLUGIN_ID",
    "\"${rootProject.extra["kotlin_plugin_id"].toString().replace("-", "")}\""
  )
  buildConfigField(
    "String",
    "PRELUDE_JVM_JAR_PATH",
    "\"${rootProject.projectDir.absolutePath}/prelude/build/libs/\""
  )
}
tasks.withType<KotlinCompile> {
  incremental = false
  kotlinOptions.jvmTarget = "1.8"
  kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
  kotlinOptions.freeCompilerArgs += "-opt-in=org.jetbrains.kotlin.utils.addToStdlib.UnsafeCastFunction"
  kotlinOptions.freeCompilerArgs += "-Xcontext-receivers"
}
val generateTests by tasks.creating(JavaExec::class) {
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("io.github.kyay10.koncept.GenerateTestsKt")
}
val compileTestKotlin by tasks.getting {
  doLast {
    generateTests.exec()
  }
}
kapt {
  generateStubs = false
}
java {
  withSourcesJar()
  withJavadocJar()
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}
publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      version = rootProject.version.toString()
    }
  }
}
fun Test.setLibraryProperty(propName: String, jarName: String) {
  val path = project.configurations
    .testRuntimeClasspath.get()
    .files
    .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
    ?.absolutePath
    ?: return
  systemProperty(propName, path)
}
