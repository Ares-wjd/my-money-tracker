import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    // 공통 테스트 케이스(testdata/*.json) 읽기용 (테스트에만)
    testImplementation(libs.org.json)
}

// 공통 테스트 케이스 파일이 바뀌면 테스트를 다시 돌린다 (안 적으면 Gradle 이 '바뀐 것 없음'으로 건너뜀).
tasks.test {
    inputs.dir(rootProject.file("testdata")).withPathSensitivity(PathSensitivity.RELATIVE)
}
