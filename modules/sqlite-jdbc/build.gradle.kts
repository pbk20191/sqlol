plugins {
    `java-library`
}

group = "org.example"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // sqlite4j(Apache-2.0) 벤더링 + WorkerDB — 실제 엔진은 :modules:sqlite 의 워커 아키텍처
    implementation(project(":modules:sqlite"))
    // nullability 어노테이션 (Kotlin 리팩토링 준비) — CLASS 리텐션이라 런타임 불필요
    compileOnly("org.jspecify:jspecify:1.0.0")
    // 벤더링 원본의 optional 의존성 (util/LoggerFactory 가 reflection 으로 유무 감지)
    compileOnly("org.slf4j:slf4j-api:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")   // xerial 테스트 코퍼스 사용
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // 코퍼스의 32-동시-DB 테스트 = 런타임 32개 (공유 linear memory 초기 32MB × 32) — 기본 힙으론 OOM
    maxHeapSize = "4g"
}
