package com.visionstudio.core.mcp

import com.visionstudio.core.common.error.VisionStudioException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Reproduces the reported "Domain specific configurations require that hostname aware
 * checkServerTrusted(X509Certificate[], String, String) is used" failure on the JVM, and shows the
 * fix keeps full certificate and hostname validation.
 *
 * [DomainConfigTrustManager] behaves like Android's `android.security.net.config.RootTrustManager`
 * when the app's network_security_config.xml has a `<domain-config>` (this app has one, for local
 * cleartext): the hostname-less two-argument `checkServerTrusted` throws exactly that message,
 * while the Socket/SSLEngine variants, which carry the peer host, validate normally. The
 * framework's own bytecode was checked: its two-argument method throws that string when
 * `ApplicationConfig.hasPerDomainConfigs()` is true.
 */
class AndroidTlsCompatibilityTest {

    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("VisionStudio Test CA").build()
    private val localhostCert = HeldCertificate.Builder().signedBy(ca).commonName("localhost")
        .addSubjectAlternativeName("localhost").addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()

    private lateinit var server: StreamableMcpServer

    @Before fun setUp() {
        server = StreamableMcpServer()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(localhostCert, ca.certificate).build()
        server.server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun platformLikeTrust(): DomainConfigTrustManager =
        DomainConfigTrustManager(HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build().trustManager)

    private fun okHttpWith(trust: X509TrustManager): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust).build()
    }

    private fun mcpClient(okHttp: OkHttpClient): McpClient = McpClientFactory(
        httpClient = createMcpHttpClient(OkHttp.create { preconfigured = okHttp }),
        ioDispatcher = Dispatchers.IO,
        defaultDispatcher = Dispatchers.Default,
        clientInfo = McpClientFactory.DEFAULT_CLIENT_INFO,
    ).create(McpServerConfig(url = server.url().replace("127.0.0.1", "localhost"), transport = McpTransport.STREAMABLE_HTTP))

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    @Test
    fun `root cause - Ktor CIO calls the hostname-less check, which Android rejects with this exact message`() = test {
        val trust = platformLikeTrust()
        val cio = HttpClient(CIO) { engine { https { trustManager = trust } } }

        val failure = runCatching { cio.get(server.url().replace("127.0.0.1", "localhost")) }.exceptionOrNull()
        cio.close()

        val message = failure?.chain()?.firstNotNullOfOrNull { it.message?.takeIf { m -> m.contains("Domain specific") } }
        assertEquals(DomainConfigTrustManager.ANDROID_MESSAGE, message)
        assertEquals(listOf("checkServerTrusted(chain, authType)"), trust.calls)
    }

    @Test
    fun `fix - the OkHttp engine validates with the hostname, so the MCP handshake succeeds`() = test {
        val trust = platformLikeTrust()
        val client = mcpClient(okHttpWith(trust))

        val result = client.connect()
        assertTrue(client.listTools().any { it.name == "flux_3_image_klein_infer" })
        client.close()

        assertEquals("FLUX 3 Image Klein", result.serverInfo.name)
        assertTrue("the hostname-less check is never used: ${trust.calls}", trust.calls.none { it == "checkServerTrusted(chain, authType)" })
        assertTrue("validated with the peer host attached: ${trust.calls}", trust.calls.all { it.startsWith("checkServerTrusted(chain, authType, socket@localhost") })
    }

    @Test
    fun `an untrusted certificate is still rejected`() = test {
        // Trusts only the JVM's public CAs; the server's CA is not among them.
        val client = mcpClient(OkHttpClient())

        val error = runCatching { client.connect() }.exceptionOrNull()
        client.close()

        assertTrue("got $error", error is VisionStudioException.NetworkException)
        assertTrue(error!!.chain().any { it is SSLHandshakeException })
    }

    @Test
    fun `a certificate for another host is still rejected, even from a trusted CA`() = test {
        server.shutdown()
        val otherHost = HeldCertificate.Builder().signedBy(ca).commonName("other.example").addSubjectAlternativeName("other.example").build()
        server = StreamableMcpServer()
        server.server.useHttps(HandshakeCertificates.Builder().heldCertificate(otherHost, ca.certificate).build().sslSocketFactory(), false)
        server.start()
        val client = mcpClient(okHttpWith(platformLikeTrust()))

        val error = runCatching { client.connect() }.exceptionOrNull()
        client.close()

        assertTrue("got $error", error!!.chain().any { it is SSLPeerUnverifiedException })
    }

}

/**
 * Mirrors `android.security.net.config.RootTrustManager` with per-domain configs present: only
 * the host-aware checks validate; the bare two-argument check always throws. Records every call.
 */
class DomainConfigTrustManager(private val delegate: X509TrustManager) : X509ExtendedTrustManager() {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        calls += "checkServerTrusted(chain, authType)"
        throw CertificateException(ANDROID_MESSAGE)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        calls += "checkServerTrusted(chain, authType, socket@${socket.inetAddress?.hostName})"
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        calls += "checkServerTrusted(chain, authType, engine@${engine.peerHost})"
        delegate.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = delegate.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = delegate.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    companion object {
        const val ANDROID_MESSAGE =
            "Domain specific configurations require that hostname aware checkServerTrusted(X509Certificate[], String, String) is used"
    }
}
