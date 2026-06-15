plugins {
    kotlin("jvm")
    `java-library`
}

group = "org.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // 라이브러리 공개 API에 Chicory 타입이 노출되지 않으므로 implementation 으로 충분
    implementation("com.dylibso.chicory:runtime:1.7.5")
    implementation("com.dylibso.chicory:wasi:1.7.5")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generatedJavaDir = layout.projectDirectory.dir("chicory-aot/build/sources")
val generatedClassDir = layout.projectDirectory.dir("chicory-aot/build/classes")
val generatedResourcesDir = layout.projectDirectory.dir("chicory-aot/build/resources")
val generateWasmAot by tasks.registering(Exec::class) {
    group = "code generation"
    description = "Run Chicory Maven plugin to generate Java/classes from wasm"

    workingDir = file("chicory-aot")
    this.inputs.files("chicory-aot/pom.xml", "chicory-aot/sqlite3.wasm", "chicory-aot/sqlite3-jvmvfs.wasm")
    this.outputs.dirs(generatedJavaDir, generatedClassDir,generatedResourcesDir)
    val mvnCmd = if (System.getProperty("os.name").startsWith("Windows")) {
        "mvn.cmd"
    } else {
        "/opt/homebrew/bin/mvn"
    }

    commandLine(
        mvnCmd,
        "-q",
        "generate-sources"
    )
}

// Chicory 플러그인이 생성한 AOT 머신(.class)은 소스가 아니라 바이트코드로만 존재한다.
// 정식 file 의존성으로 등록해야 컴파일 classpath 에 오르고, IntelliJ 도 라이브러리로 인식한다.
dependencies {
    implementation(files(generatedClassDir).builtBy(generateWasmAot))
}

sourceSets {
    named("main") {
        java.srcDirs(generatedJavaDir)
        // jar 에 머신 클래스를 포함시키기 위해 main output 에도 추가
        output.dir(generatedClassDir, "builtBy" to generateWasmAot)
        resources {
            // src/main/resources 는 기본 포함. 생성된 AOT 리소스만 추가.
            srcDir(generatedResourcesDir)
        }
    }
}

kotlin {
    sourceSets {

    }
}

tasks.named("compileKotlin") {

    dependsOn(generateWasmAot)

}
tasks.named("compileJava") {
    dependsOn(generateWasmAot)
}
tasks.named("processResources") {
    dependsOn(generateWasmAot)
}

tasks.named("clean") {
    doLast {
        delete("chicory-aot/build")
    }
}

tasks.test {
    useJUnitPlatform()
}