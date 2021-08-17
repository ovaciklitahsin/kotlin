import org.jetbrains.kotlin.ideaExt.idea

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

val kotlinNativeCompilerClassPath: Configuration by configurations.creating

dependencies {
    testImplementation(kotlinStdlib())
    testImplementation(intellijCoreDep()) { includeJars("intellij-core") }
    testImplementation(project(":kotlin-compiler-runner"))
    testImplementation(projectTests(":compiler:tests-common"))
    testImplementation(projectTests(":compiler:tests-common-new"))
    testImplementation(projectTests(":compiler:test-infrastructure"))
    testImplementation(projectTests(":generators:test-generator"))
    testApiJUnit5()

    testRuntimeOnly(intellijDep()) { includeJars("trove4j", "intellij-deps-fastutil-8.4.1-4") }

    kotlinNativeCompilerClassPath(projectRuntimeJar(":kotlin-native-compiler-embeddable"))
}

val generationRoot = projectDir.resolve("tests-gen")
val extGenerationRoot = projectDir.resolve("ext-tests-gen")

val kotlinNativeHome = project(":kotlin-native").projectDir.resolve("dist")

sourceSets {
    "main" { none() }
    "test" {
        projectDefault()
        java.srcDirs(generationRoot.name, extGenerationRoot.name)
    }
}

if (kotlinBuildProperties.isInJpsBuildIdeaSync) {
    apply(plugin = "idea")
    idea {
        module.generatedSourceDirs.addAll(listOf(generationRoot, extGenerationRoot))
    }
}

projectTest(jUnit5Enabled = true) {
    dependsOn(":kotlin-native:dist", ":kotlin-native:distPlatformLibs")
    workingDir = rootDir
    systemProperty("kotlin.native.home", kotlinNativeHome.absolutePath)
    systemProperty("kotlin.internal.native.classpath", kotlinNativeCompilerClassPath.files.joinToString(";"))

    useJUnitPlatform()
}

val generateOwnTests by generator("org.jetbrains.kotlin.generators.tests.GenerateNativeBlackboxTestsKt")
val generateExtTests by generator("org.jetbrains.kotlin.generators.tests.GenerateExtNativeBlackboxTestsKt")

val generateTests by tasks.creating<Task> {
    dependsOn(generateOwnTests, generateExtTests)
}
