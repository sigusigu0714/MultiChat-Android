package org.multichat.android
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.Test
import org.junit.Assert.*

class TransportTest {
    @Test fun credentialsAreNotForwardedToRedirects() = runBlocking {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTLS=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTLS=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val first=MockWebServer();val second=MockWebServer()
        first.useHttps(serverTLS.sslSocketFactory(),false);second.useHttps(serverTLS.sslSocketFactory(),false)
        first.start();second.start()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location",second.url("/capture")))
            val api=NetworkApi(NetworkApi().client.newBuilder().sslSocketFactory(clientTLS.sslSocketFactory(),clientTLS.trustManager).build())
            try { api.request(first.url("/api").toString(),headers=mapOf("X-Account-Send-Key" to "test-only-secret"));fail("Redirect should be rejected") } catch(e:ApiFailure) { assertEquals(302,e.code) }
            assertEquals(1,first.requestCount);assertEquals(0,second.requestCount)
            assertEquals("test-only-secret",first.takeRequest().getHeader("X-Account-Send-Key"))
        } finally { first.shutdown();second.shutdown() }
    }
    @Test fun plaintextConnectionIsRejectedBeforeSending() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            try {NetworkApi().request(server.url("/").toString());fail("HTTP must fail")} catch(_:IllegalArgumentException) { }
            assertEquals(0,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun responseBodiesDoNotAppearInErrors() { assertFalse(ApiFailure(403).message.orEmpty().contains("token"));assertTrue(ApiFailure(403).message.orEmpty().contains("403")) }
}
