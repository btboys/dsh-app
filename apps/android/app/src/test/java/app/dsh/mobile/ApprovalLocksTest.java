package app.dsh.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class ApprovalLocksTest {
    static final class Memory implements ApprovalLocks.Storage {
        final Set<String> values = new HashSet<>();
        boolean writable = true;

        public boolean contains(String id) { return values.contains(id); }

        public boolean put(String id) {
            if (!writable) return false;
            return values.add(id);
        }
    }

    @Test
    public void aSubmissionLocksTheApprovalForLaterActivityInstances() {
        Memory disk = new Memory();
        ApprovalLocks first = new ApprovalLocks(disk);
        assertTrue(first.begin("a"));
        assertTrue(new ApprovalLocks(disk).locked("a"));
        assertFalse(new ApprovalLocks(disk).begin("a"));
    }

    @Test
    public void otherApprovalsStayUsable() {
        Memory disk = new Memory();
        ApprovalLocks locks = new ApprovalLocks(disk);
        assertTrue(locks.begin("a"));
        assertTrue(locks.begin("b"));
    }

    @Test
    public void theLockOutlivesTheActivityThatTookIt() {
        Memory disk = new Memory();
        assertTrue(new ApprovalLocks(disk).begin("a"));
        assertTrue(new ApprovalLocks(disk).locked("a"));
        assertFalse(new ApprovalLocks(disk).begin("a"));
    }

    @Test
    public void anUnstoredLockBlocksTheSubmission() {
        Memory disk = new Memory();
        disk.writable = false;
        assertFalse(new ApprovalLocks(disk).begin("c"));
    }
}
