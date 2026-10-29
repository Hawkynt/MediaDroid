package com.hawkynt.mediadroid.nfo

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Android's own DocumentBuilderFactory knows only the namespace and validation features and refuses
 * every other one with a ParserConfigurationException — FEATURE_SECURE_PROCESSING too. An NFO must
 * parse there all the same.
 */
class AndroidXmlTest {

    /** A factory that behaves like Android's: the JDK's parser, but no features beyond the two. */
    class AndroidLikeFactory : DocumentBuilderFactory() {
        private val jdk = DocumentBuilderFactory.newDefaultInstance()

        override fun newDocumentBuilder(): DocumentBuilder = jdk.apply {
            isNamespaceAware = this@AndroidLikeFactory.isNamespaceAware
        }.newDocumentBuilder()

        override fun setAttribute(name: String?, value: Any?) = throw IllegalArgumentException(name)

        override fun getAttribute(name: String?): Any = throw IllegalArgumentException(name)

        override fun setFeature(name: String?, value: Boolean) {
            when (name) {
                "http://xml.org/sax/features/namespaces" -> isNamespaceAware = value
                "http://xml.org/sax/features/validation" -> isValidating = value
                else -> throw ParserConfigurationException(name)
            }
        }

        override fun getFeature(name: String?): Boolean = throw ParserConfigurationException(name)
    }

    private val property = "javax.xml.parsers.DocumentBuilderFactory"
    private var before: String? = null

    @Before
    fun useAndroidLikeParser() {
        before = System.getProperty(property)
        System.setProperty(property, AndroidLikeFactory::class.java.name)
    }

    @After
    fun restore() {
        before?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    @Test
    fun `an NFO parses where the parser refuses the secure-processing feature`() {
        val movie = parseMovieNfo(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <movie>
              <title>Coco</title>
              <plot>Miguel dreams of music.</plot>
              <actor><name>Anthony Gonzalez</name><role>Miguel</role></actor>
            </movie>
            """.trimIndent(),
        )
        assertEquals("Coco", movie.title)
        assertEquals("Miguel dreams of music.", movie.plot)
    }
}
