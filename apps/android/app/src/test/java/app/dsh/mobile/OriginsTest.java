package app.dsh.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OriginsTest {
    @Test
    public void onlyThePairedOriginIsAllowed() {
        assertTrue(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "https://192.168.1.10:3443/index.html"));
        assertTrue(Origins.INSTANCE.isSameOrigin("https://192.168.1.10:3443", "https://192.168.1.10:3443"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "https://192.168.1.10:3443.evil.com/"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "https://192.168.1.10:3444/"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "http://192.168.1.10:3443/"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "https://evil.example/"));
    }

    @Test
    public void defaultPortsAreNormalised() {
        assertTrue(Origins.INSTANCE.isSameOrigin("https://dsh.example", "https://dsh.example:443/session"));
        assertTrue(Origins.INSTANCE.isSameOrigin("http://dsh.example:80", "http://dsh.example/path"));
        assertFalse(Origins.INSTANCE.isSameOrigin("https://dsh.example", "https://dsh.example.evil"));
    }

    @Test
    public void credentialsInTheUrlDoNotChangeTheHost() {
        // The paired address as userinfo still connects to the paired host.
        assertTrue(Origins.INSTANCE.isSameOrigin("https://192.168.1.10:3443", "https://evil.example@192.168.1.10:3443/"));
        // A lookalike in the userinfo does not hide the real host.
        assertFalse(Origins.INSTANCE.isSameOrigin("https://192.168.1.10:3443", "https://192.168.1.10:3443@evil.example/"));
    }

    @Test
    public void malformedUrlsAreRejected() {
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "not a url"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "javascript:alert(1)"));
        assertFalse(Origins.INSTANCE.isAllowedNavigation("https://192.168.1.10:3443", "file:///etc/passwd"));
    }
}
