// 플러그인도 Maven Central 의 Google 미러를 먼저 본다(Render 빌드 IP 가 repo.maven.apache.org 에서 429 를 받은 일, 2026-11-27).
// 미러에 없으면 Gradle 플러그인 포털·Maven Central 로 넘어간다.
pluginManagement {
	repositories {
		maven("https://maven-central.storage-download.googleapis.com/maven2/")
		gradlePluginPortal()
		mavenCentral()
	}
}

rootProject.name = "backend"
