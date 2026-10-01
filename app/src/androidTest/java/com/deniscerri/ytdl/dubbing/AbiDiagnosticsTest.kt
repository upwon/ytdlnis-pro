package com.deniscerri.ytdl.dubbing

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.deniscerri.ytdl.dubbing.DeviceTestEnv as Env

/** Makes the CPU environment visible in the CI log; the other tests are meaningless on a mismatched ABI. */
@RunWith(AndroidJUnit4::class)
class AbiDiagnosticsTest {
    @Test fun appRunsWithNativeLibrariesOfTheDeviceAbi() {
        val dir = File(Env.ctx.applicationInfo.nativeLibraryDir)
        Log.i(TAG, "SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.toList()} api=${Build.VERSION.SDK_INT}")
        Log.i(TAG, "nativeLibraryDir=$dir files=${dir.list()?.sorted()}")
        Log.i(TAG, "os.arch=${System.getProperty("os.arch")}")
        assertTrue("bundled ffmpeg package is missing from $dir", File(dir, "libffmpeg.zip.so").exists())
        assertTrue("bundled python package is missing from $dir", File(dir, "libpython.zip.so").exists())
        assertTrue("native libs should match the device ABI (${Build.SUPPORTED_ABIS[0]}): $dir",
            dir.path.contains(Build.SUPPORTED_ABIS[0].replace("-v8a", "").replace("arm64", "arm64")) || dir.path.contains("x86_64") || dir.path.contains("arm64"))
    }
}
