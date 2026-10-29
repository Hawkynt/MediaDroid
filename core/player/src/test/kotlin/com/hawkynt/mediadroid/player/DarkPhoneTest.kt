package com.hawkynt.mediadroid.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkPhoneTest {

    @Test
    fun `leaving the player after the phone was darkened locks it, once`() {
        val phone = DarkPhone()
        phone.darken()
        assertTrue(phone.dark)
        assertTrue(phone.leave())
        assertFalse(phone.dark)
        assertFalse(phone.leave())
    }

    @Test
    fun `a touch lights the phone up, but leaving still locks it`() {
        val phone = DarkPhone()
        phone.darken()
        phone.touch()
        assertFalse(phone.dark)
        assertTrue(phone.leave())
    }

    @Test
    fun `a phone never darkened is left as it is`() {
        val phone = DarkPhone()
        phone.touch()
        assertFalse(phone.leave())
    }

    @Test
    fun `losing the second screen lights the phone up and keeps the lock armed`() {
        val phone = DarkPhone()
        phone.darken()
        phone.secondScreenGone()
        assertFalse(phone.dark)
        assertTrue(phone.leave())
    }
}
