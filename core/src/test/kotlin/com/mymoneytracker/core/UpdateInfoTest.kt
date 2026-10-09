package com.mymoneytracker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {
    @Test
    fun readsVersionFromBodyThenName() {
        assertEquals(12, UpdateInfo.parseVersionCode("개발 버전 (빌드 #9)", "versionCode: 12\n커밋: abc"))
        assertEquals(9, UpdateInfo.parseVersionCode("개발 버전 (빌드 #9)", "내용 없음"))
        assertNull(UpdateInfo.parseVersionCode("이름", null))
    }

    @Test
    fun notifiesOnlyForNewerUnskippedBuild() {
        assertTrue(UpdateInfo.shouldNotify(latest = 10, installed = 8, skipped = null))
        assertFalse(UpdateInfo.shouldNotify(latest = 8, installed = 8, skipped = null))
        assertFalse(UpdateInfo.shouldNotify(latest = 10, installed = 8, skipped = 10))
        assertTrue(UpdateInfo.shouldNotify(latest = 11, installed = 8, skipped = 10))
        assertFalse(UpdateInfo.shouldNotify(latest = null, installed = 8, skipped = null))
    }
}
