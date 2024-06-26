plugins {
  id("io.airbyte.gradle.jvm.lib")
  id("io.airbyte.gradle.publish")
  kotlin("jvm")
  kotlin("kapt")
}

dependencies {
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)     // Lombok must be added BEFORE Micronaut
  annotationProcessor(platform(libs.micronaut.platform))
  annotationProcessor(libs.bundles.micronaut.annotation.processor)

  kapt(platform(libs.micronaut.platform))
  kapt(libs.bundles.micronaut.annotation.processor)

  implementation(platform(libs.micronaut.platform))
  implementation(libs.bundles.micronaut)
  implementation(libs.bundles.micronaut.annotation)
  implementation(libs.micronaut.security)

  implementation(project(":airbyte-commons"))
  implementation(project(":airbyte-config:config-models"))

  testAnnotationProcessor(platform(libs.micronaut.platform))
  testAnnotationProcessor(libs.bundles.micronaut.test.annotation.processor)

  testImplementation(libs.bundles.micronaut.test)
  testImplementation(libs.mockito.inline)
}

tasks.named<Test>("test") {
  maxHeapSize = "2g"
}

// The DuplicatesStrategy will be required while this module is mixture of kotlin and java _with_ lombok dependencies.)
// Kapt, by default, runs all annotation(processors and disables annotation(processing by javac, however)
// this default behavior(breaks the lombok java annotation(processor.  To avoid(lombok breaking, kapt(has)
// keepJavacAnnotationProcessors enabled, which causes duplicate META-INF files to be generated.)
// Once lombok has been removed, this can also be removed.)
tasks.withType<Jar>().configureEach {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
