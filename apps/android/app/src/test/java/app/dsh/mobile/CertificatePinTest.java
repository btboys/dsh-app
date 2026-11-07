package app.dsh.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.BeforeClass;
import org.junit.Test;

public final class CertificatePinTest {
    /** base64 SHA-256 of resources/pins/certA.crt and certB.crt, from openssl. */
    private static final String PIN_A = "GkaZ+zybYY3AphjbtfgHR4sy8sfNefEMXC+b5QEvl0I=";
    private static final String PIN_B = "zfsGSYlq6hKiIhTCX1CuZt4vNIJk0rkpm0OqryVOEeY=";
    private static X509Certificate a;
    private static X509Certificate b;

    @BeforeClass
    public static void loadCertificates() throws Exception {
        a = load("certA.crt");
        b = load("certB.crt");
    }

    private static X509Certificate load(String name) throws Exception {
        try (InputStream stream = CertificatePinTest.class.getResourceAsStream("/pins/" + name)) {
            assertNotNull(name, stream);
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
    }

    @Test
    public void thePinIsTheBase64Sha256OfTheCertificateDer() {
        assertEquals(PIN_A, CertificatePin.INSTANCE.of(a));
        assertEquals(PIN_B, CertificatePin.INSTANCE.of(b));
    }

    @Test
    public void aStoredPinOnlyMatchesItsOwnCertificate() {
        assertTrue(CertificatePin.INSTANCE.matches(PIN_A, a));
        assertFalse(CertificatePin.INSTANCE.matches(PIN_A, b));
        assertFalse(CertificatePin.INSTANCE.matches(PIN_B, a));
    }

    @Test
    public void pinsAreComparedByDecodedBytes() {
        assertTrue(CertificatePin.INSTANCE.matches(PIN_A, " " + PIN_A + "\n"));
        assertFalse(CertificatePin.INSTANCE.matches(PIN_A, PIN_B));
        assertFalse(CertificatePin.INSTANCE.matches("not a pin", PIN_A));
        assertFalse(CertificatePin.INSTANCE.matches("", PIN_A));
    }

    @Test
    public void thePinFormatIsCheckedBeforeUse() {
        assertTrue(CertificatePin.INSTANCE.isValidFormat(PIN_A));
        assertTrue(CertificatePin.INSTANCE.isValidFormat(PIN_A.replace("=", "")));
        assertFalse(CertificatePin.INSTANCE.isValidFormat("GkaZ"));
        // 32 base64 characters decode to 24 bytes, not a SHA-256 digest.
        assertFalse(CertificatePin.INSTANCE.isValidFormat("GkaZ+zybYY3AphjbtfgHR4sy8sfNefEMXC"));
    }

    @Test
    public void aPinnedTrustManagerAcceptsOnlyThatLeaf() throws Exception {
        X509TrustManager trust = CertificatePin.INSTANCE.trustManager(PIN_A);
        trust.checkServerTrusted(new X509Certificate[] { a }, "ECDHE_RSA");
        try {
            trust.checkServerTrusted(new X509Certificate[] { b }, "ECDHE_RSA");
            throw new AssertionError("a different certificate must be rejected");
        } catch (CertificateException expected) {
            assertEquals("certificate_mismatch", expected.getMessage());
        }
    }
}
