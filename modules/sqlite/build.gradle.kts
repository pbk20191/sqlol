import run.endive.build.time.compiler.Config
import run.endive.build.time.compiler.Generator
import run.endive.compiler.InterpreterFallback
import java.io.File

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // 이 스크립트가 직접 호출하는 Config/Generator 를 buildscript classpath 에만 올린다
        // (project classpath 에는 안 실림 — 아래 dependencies 블록과 무관, 공개 API 오염 없음).
        classpath("run.endive:build-time-compiler:1.0.1")
    }
}

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
    // 라이브러리 공개 API에 endive 타입이 노출되지 않으므로 implementation 으로 충분
    // (2026-07 Chicory → endive: Bytecode Alliance 이관, com.dylibso.chicory → run.endive)
    implementation("run.endive:runtime:1.0.1")
    implementation("run.endive:wasi:1.0.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val generatedJavaDir = layout.projectDirectory.dir("chicory-aot/build/sources")
val generatedClassDir = layout.projectDirectory.dir("chicory-aot/build/classes")
val generatedResourcesDir = layout.projectDirectory.dir("chicory-aot/build/resources")

/**
 * endive `build-time-compiler` 의 [Config]/[Generator] 를 **인프로세스로 직접 호출** —
 * 외부 `mvn` 서브프로세스(및 `chicory-aot/pom.xml`) 제거. Maven 플러그인(`EndiveCompilerGenMojo`)의
 * 바이트코드를 역어셈블해 확인한 정확한 호출 순서를 그대로 재현한다:
 * builder 체인 → generateResources(인터프리트 함수 집합 반환) → generateMetaWasm → generateSources
 * → generateModuleInterface (moduleInterface 가 설정된 경우).
 */
abstract class GenerateEndiveModules : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val sqlite3Wasm: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.InputFile
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val jvmVfsWasm: org.gradle.api.file.RegularFileProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputClasses: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputSources: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputResources: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        generateOne(sqlite3Wasm.asFile.get(), "com.example.wasm.Sqlite3Module")
        generateOne(jvmVfsWasm.asFile.get(), "com.example.wasm.JvmVfsModule")
    }

    private fun generateOne(wasmFile: File, name: String) {
        val config = Config.builder()
            .withWasmFile(wasmFile.toPath())
            .withName(name)
            .withTargetClassFolder(outputClasses.asFile.get().toPath())
            .withTargetSourceFolder(outputSources.asFile.get().toPath())
            .withTargetWasmFolder(outputResources.asFile.get().toPath())
            .withInterpreterFallback(InterpreterFallback.FAIL)
            .withInterpretedFunctions(emptySet())
            .withModuleInterface(name)
            .build()
        val generator = Generator(config)
        val interpretedFunctions = generator.generateResources()
        generator.generateMetaWasm(interpretedFunctions)
        generator.generateSources()
        generator.generateModuleInterface(name)
    }
}

val generateWasmAot by tasks.registering(GenerateEndiveModules::class) {
    group = "code generation"
    description = "endive build-time-compiler 를 인프로세스로 실행해 wasm → Java/클래스 생성"

    sqlite3Wasm.set(layout.projectDirectory.file("chicory-aot/sqlite3.wasm"))
    jvmVfsWasm.set(layout.projectDirectory.file("chicory-aot/sqlite3-jvmvfs.wasm"))
    outputClasses.set(generatedClassDir)
    outputSources.set(generatedJavaDir)
    outputResources.set(generatedResourcesDir)
}

// endive 컴파일러가 생성한 AOT 머신(.class)은 소스가 아니라 바이트코드로만 존재한다.
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