import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { kotlin("jvm") }
dependencies { testImplementation(kotlin("test")) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
tasks.test { useJUnitPlatform() }
