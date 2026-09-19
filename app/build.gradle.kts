plugins {
    application
    `java-test-fixtures`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["testFixtures"].output
        runtimeClasspath += output + compileClasspath
    }
    create("sample") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += output + compileClasspath
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}
configurations.named("sampleImplementation") {
    extendsFrom(configurations.implementation.get())
}
configurations.named("sampleRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(libs.guava)
    implementation(libs.lettuce)
    implementation(libs.jackson.databind)

    add("integrationTestImplementation", testFixtures(project))
}

application {
    mainClass = "org.example.App"
}

tasks.register<JavaExec>("runPipelineSample") {
    group = "application"
    description = "Simulate pipelinesv1 (Jenkins) and pipelinesv2 (K8s) runs."
    val sample = sourceSets["sample"]
    classpath = sample.runtimeClasspath
    mainClass.set("org.example.reservation.sample.PipelineSimulation")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Reservation store tests against Valkey (Apple Container)."
    group = "verification"
    val integrationTest = sourceSets["integrationTest"]
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
}
