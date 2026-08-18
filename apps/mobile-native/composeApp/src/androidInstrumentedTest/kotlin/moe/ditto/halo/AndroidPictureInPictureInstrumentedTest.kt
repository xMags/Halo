package moe.ditto.halo

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ditto.halo.player.AndroidPlayerSystemPort
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's real Activity PiP handoff without needing a media fixture. */
@RunWith(AndroidJUnit4::class)
class AndroidPictureInPictureInstrumentedTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun systemPortEntersNativePictureInPictureWhenTheDeviceSupportsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
        )

        lateinit var activity: MainActivity
        var accepted = false
        activityRule.scenario.onActivity { launched ->
            activity = launched
            accepted = AndroidPlayerSystemPort(launched).enterPictureInPicture()
        }
        assertTrue("Android rejected the native PiP handoff", accepted)

        try {
            val deadline = SystemClock.uptimeMillis() + EnterTimeoutMillis
            var active = false
            while (!active && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    active = activity.isInPictureInPictureMode
                }
                if (!active) SystemClock.sleep(PollMillis)
            }
            assertTrue("Activity never entered native PiP", active)
        } finally {
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }

    private companion object {
        const val EnterTimeoutMillis = 5_000L
        const val PollMillis = 50L
    }
}
