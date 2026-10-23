package io.github.hugo_cacciatori.basedinformant.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun nameIsTheProductName() {
        assertEquals("Based Informant", AppInfo.NAME)
    }
}
