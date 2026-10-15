package space.sovereigncast.hopp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Both apps carry one version (device feedback 2026-10-15: iOS still said
 *  0.0.1): Gradle writes it for Xcode, and this fails if that was skipped. */
class VersionTest {
    @Test
    fun iosHasTheAndroidVersion() {
        val gradle = File("../androidApp/build.gradle.kts").readText()
        val name = Regex("""val appVersion\s*=\s*"([^"]+)"""").find(gradle)!!.groupValues[1]
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)!!.groupValues[1]
        val ios = File("../iosApp/Configuration/Version.xcconfig").readText()
        assertEquals(name, Regex("""MARKETING_VERSION\s*=\s*(\S+)""").find(ios)?.groupValues?.get(1), "run ./gradlew :androidApp:syncIosVersion")
        assertEquals(code, Regex("""CURRENT_PROJECT_VERSION\s*=\s*(\S+)""").find(ios)?.groupValues?.get(1), "run ./gradlew :androidApp:syncIosVersion")
    }
}
