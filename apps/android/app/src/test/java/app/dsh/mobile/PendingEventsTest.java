package app.dsh.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class PendingEventsTest {
    @Test
    public void anApprovalBecomesAnAlertWithItsItemId() {
        PendingEvents.Alert alert = PendingEvents.INSTANCE.alert(
            new GatewayEvent("approval", "abc", "", "写入文件", "write"));
        assertNotNull(alert);
        assertEquals("abc", alert.getItemId());
        assertEquals("写入文件", alert.getText());
    }

    @Test
    public void aQuestionAlsoNeedsTheUser() {
        PendingEvents.Alert alert = PendingEvents.INSTANCE.alert(new GatewayEvent("question", "q1", "", "", "ask"));
        assertNotNull(alert);
        assertEquals("q1", alert.getItemId());
        assertEquals("新的问题", alert.getTitle());
    }

    @Test
    public void anIdleStatusIsAnAlertWithoutAnItem() {
        PendingEvents.Alert alert = PendingEvents.INSTANCE.alert(new GatewayEvent("status", "", "idle", "", ""));
        assertNotNull(alert);
        assertEquals("", alert.getItemId());
    }

    @Test
    public void aSettlementCarriesTheItemAndState() {
        PendingEvents.Settlement settled = PendingEvents.INSTANCE.settlement(
            new GatewayEvent("pending-settled", "abc", "phone_timeout", "", ""));
        assertNotNull(settled);
        assertEquals("abc", settled.getItemId());
        assertEquals(PendingEvents.STATE_PHONE_TIMEOUT, settled.getState());
    }

    @Test
    public void unrelatedOrIncompleteEventsAreIgnored() {
        assertNull(PendingEvents.INSTANCE.alert(new GatewayEvent("pending-settled", "abc", "", "", "")));
        assertNull(PendingEvents.INSTANCE.alert(new GatewayEvent("approval", "", "", "", "")));
        assertNull(PendingEvents.INSTANCE.settlement(new GatewayEvent("approval", "abc", "", "", "")));
        assertNull(PendingEvents.INSTANCE.settlement(new GatewayEvent("pending-settled", "", "", "", "")));
        assertNull(PendingEvents.INSTANCE.alert(new GatewayEvent("status", "", "busy", "", "")));
    }
}
