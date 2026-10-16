package dev.virtmanager.android.screenshots

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no AndroidKeyStore. This in-memory stand-in lets [dev.virtmanager.android.data.SecretCipher]
 * run unchanged in JVM tests.
 */
object FakeAndroidKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    fun install() {
        if (Security.getProvider("AndroidKeyStore") != null) return
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "fake") {
            init {
                put("KeyStore.AndroidKeyStore", StoreSpi::class.java.name)
                put("KeyGenerator.AES", GenSpi::class.java.name)
            }
        })
    }

    class StoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetEntry(alias: String, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            keys[alias]?.let { KeyStore.SecretKeyEntry(it) }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date()
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineIsCertificateEntry(alias: String?) = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class GenSpi : KeyGeneratorSpi() {
        private var alias = "default"
        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            // KeyGenParameterSpec is an Android class; read its alias reflectively.
            alias = runCatching { params!!.javaClass.getMethod("getKeystoreAlias").invoke(params) as String }.getOrDefault(alias)
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
            keys[alias] = key
            return key
        }
    }
}
