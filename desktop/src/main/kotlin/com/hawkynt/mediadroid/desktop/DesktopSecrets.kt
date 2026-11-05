package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.sources.Secrets
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Where the desktop keeps the network sources' passwords, by source id: never in the store beside the sources. The
 * system's own keeper where there is one — the Secret Service on Linux, DPAPI on Windows — else a file only the user
 * can read.
 */
interface DesktopSecrets : Secrets {

    /** Keeps [secret] for [sourceId]; null forgets it. */
    fun store(sourceId: String, secret: String?)

    companion object {
        /** This system's keeper, its files (if any) in [folder]. */
        fun forThisSystem(folder: File): DesktopSecrets {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Windows") -> DpapiSecrets(File(folder, "secrets.properties"))
                SecretServiceSecrets.available() -> SecretServiceSecrets()
                else -> FileSecrets(File(folder, "secrets.properties"))
            }
        }

        /** Runs [command] with [input] on its standard input; its standard output, or null when it failed. */
        internal fun run(input: String?, vararg command: String): String? = runCatching {
            val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.use { out -> input?.let { out.write(it.toByteArray()) } }
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            output.takeIf { process.exitValue() == 0 }
        }.getOrNull()
    }
}

/** A properties file only its owner may read or write (where the file system can say so). */
internal open class SecretFile(private val file: File) {
    private val properties = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    fun get(key: String): String? = properties.getProperty(key)

    fun set(key: String, value: String?) {
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
            runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        file.outputStream().use { properties.store(it, "MediaDroid") }
    }
}

/** Plain, in a file only the user can read: where the system offers no keeper (no Secret Service running). */
internal class FileSecrets(file: File) : DesktopSecrets {
    private val secrets = SecretFile(file)
    override fun secretFor(sourceId: String): String? = secrets.get(sourceId)
    override fun store(sourceId: String, secret: String?) = secrets.set(sourceId, secret)
}

/** The freedesktop Secret Service (GNOME Keyring, KWallet, KeePassXC…), through libsecret's `secret-tool`. */
internal class SecretServiceSecrets : DesktopSecrets {

    override fun secretFor(sourceId: String): String? =
        DesktopSecrets.run(null, "secret-tool", "lookup", *attributes(sourceId))?.takeIf { it.isNotEmpty() }

    override fun store(sourceId: String, secret: String?) {
        if (secret == null) {
            DesktopSecrets.run(null, "secret-tool", "clear", *attributes(sourceId))
        } else {
            DesktopSecrets.run(secret, "secret-tool", "store", "--label=MediaDroid source", *attributes(sourceId))
        }
    }

    private fun attributes(sourceId: String) = arrayOf("application", APPLICATION, "source", sourceId)

    companion object {
        private const val APPLICATION = "mediadroid"

        /** Whether a Secret Service answers: a lookup that runs, whether or not it finds anything. */
        fun available(): Boolean = runCatching {
            val process = ProcessBuilder("secret-tool", "lookup", "application", APPLICATION, "probe", "probe")
                .redirectErrorStream(true).start()
            val said = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS) && "not activatable" !in said && "Cannot autolaunch" !in said &&
                (process.exitValue() == 0 || said.isEmpty())
        }.getOrDefault(false)
    }
}

/**
 * Windows' DPAPI, for the current user, through PowerShell's SecureString (ConvertFrom-SecureString encrypts with
 * it); what it makes is kept in a file. The password goes over standard input, never on a command line.
 */
internal class DpapiSecrets(file: File) : DesktopSecrets {
    private val sealed = SecretFile(file)

    override fun secretFor(sourceId: String): String? {
        val text = sealed.get(sourceId) ?: return null
        return DesktopSecrets.run(text, *powershell(UNPROTECT))?.trimEnd('\r', '\n')
    }

    override fun store(sourceId: String, secret: String?) {
        val text = secret?.let { DesktopSecrets.run(it, *powershell(PROTECT))?.trim() }
        sealed.set(sourceId, text)
    }

    private fun powershell(script: String) =
        arrayOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)

    private companion object {
        const val PROTECT = "\$s = [Console]::In.ReadToEnd(); " +
            "ConvertTo-SecureString -String \$s -AsPlainText -Force | ConvertFrom-SecureString"
        const val UNPROTECT = "\$s = ConvertTo-SecureString -String ([Console]::In.ReadToEnd().Trim()); " +
            "[Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringBSTR(" +
            "[Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$s)))"
    }
}
