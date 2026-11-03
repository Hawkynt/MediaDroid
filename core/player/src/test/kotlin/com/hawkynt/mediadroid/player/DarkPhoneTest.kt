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

    @Test
    fun `on a second screen the phone goes dark once the controls hide, without arming the lock`() {
        val phone = DarkPhone()
        phone.secondScreen(true)
        phone.controlsHidden()
        assertTrue(phone.dark)
        assertFalse(phone.leave())
    }

    @Test
    fun `a touch lights it up, and it goes dark again when the controls hide again`() {
        val phone = DarkPhone()
        phone.secondScreen(true)
        phone.controlsHidden()
        phone.touch()
        assertFalse(phone.dark)
        phone.controlsHidden()
        assertTrue(phone.dark)
    }

    @Test
    fun `with the film on the phone itself hidden controls leave it lit`() {
        val phone = DarkPhone()
        phone.controlsHidden()
        assertFalse(phone.dark)
        phone.secondScreen(true)
        phone.secondScreen(false)
        phone.controlsHidden()
        assertFalse(phone.dark)
    }

    @Test
    fun `the film back on the phone lights it up`() {
        val phone = DarkPhone()
        phone.secondScreen(true)
        phone.controlsHidden()
        phone.secondScreen(false)
        assertFalse(phone.dark)
    }
}
