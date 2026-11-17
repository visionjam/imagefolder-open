package com.imagefolder.open

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `values/` is English and `values-zh/` is Simplified Chinese, so any other
 * system language must fall back to English. Before v1.3.0 the default was
 * Chinese, which is what a German or Japanese device used to get.
 */
@RunWith(AndroidJUnit4::class)
class LocaleFallbackTest {

    private fun contextFor(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun unsupportedLocaleFallsBackToEnglish() {
        val german = contextFor("de-DE")
        assertEquals("Image Folder", german.getString(R.string.app_name))
        assertEquals("Pack", german.getString(R.string.tab_pack))
        assertEquals("ImageFolder", german.getString(R.string.save_folder_name))
    }

    @Test
    fun chineseStillUsesChinese() {
        val chinese = contextFor("zh-CN")
        assertEquals("图片文件夹", chinese.getString(R.string.app_name))
        assertEquals("打包", chinese.getString(R.string.tab_pack))
        assertEquals("图片文件夹", chinese.getString(R.string.save_folder_name))
    }
}
