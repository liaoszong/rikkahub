package me.rerere.rikkahub

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermuxNativeCompatibilityTest {
    @Test
    fun termuxNativeBridgeLoadsAndResolvesOn16KbRuntime() {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        assumeTrue("This compatibility test requires a 16 KB Android runtime", pageSize == PAGE_SIZE_16_KB)

        val jniClass = Class.forName("com.termux.terminal.JNI", true, javaClass.classLoader)
        val close = jniClass.getDeclaredMethod("close", Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        close.invoke(null, -1)

        assertEquals(PAGE_SIZE_16_KB, pageSize)
    }

    private companion object {
        const val PAGE_SIZE_16_KB = 16_384L
    }
}
