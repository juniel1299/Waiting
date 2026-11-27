plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.daumjari"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	// Maven Central 의 Google 미러를 먼저: Render 빌드가 repo.maven.apache.org 에서 429(Too Many Requests)를 받아 배포가 실패했다(2026-11-27).
	// 미러에 없거나 미러가 실패하면 Maven Central 로 넘어간다
	maven("https://maven-central.storage-download.googleapis.com/maven2/")
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// prod 프로필에서만 쓰는 JDBC 세션 저장소 (ProdSessionConfig). Boot 자동 설정 없이 라이브러리만 둔다
	implementation("org.springframework.session:spring-session-jdbc")
	implementation("org.flywaydb:flyway-database-postgresql")
	// 사진첩: JDK ImageIO 에 없는 WebP 읽기만 더한다(쓰기는 모두 JPEG). 근거·크기는 docs/plans/platform-plan.md 7단계
	implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 로컬 실행은 기본으로 local 프로필(시드 포함). SPRING_PROFILES_ACTIVE 로 바꿀 수 있다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Docker 빌드의 의존성 층(소스 복사 전)에서 실제 jar 까지 받아 두는 작업. 소스만 바뀐 배포는 이 층을 캐시로 다시 써서
// 저장소에 다시 묻지 않는다(그 뒤 bootJar 는 --offline). 테스트용 구성은 운영 이미지에 필요 없어 받지 않는다.
tasks.register("downloadDependencies") {
	description = "운영 빌드에 필요한 의존성 jar 를 받아 둔다 (Dockerfile)"
	val wanted = configurations.matching { it.isCanBeResolved && !it.name.startsWith("test") }
	doLast {
		wanted.forEach { it.resolve() }
	}
}
