package app.dsh.mobile;

/**
 * Serializes approval submissions across activity instances in this process.
 *
 * A lock is taken before a submission is sent and is never released. A response can be lost
 * after the computer already answered, so once this phone has submitted an approval it cannot
 * prove the outcome; allowing a retry would let a stale list send the opposite decision.
 * Locked approvals stay read-only and the user confirms the result on the computer.
 */
public final class ApprovalLocks {
    public interface Storage {
        boolean contains(String key);
        boolean put(String key);
    }

    private final Storage storage;

    public ApprovalLocks(Storage storage) {
        this.storage = storage;
    }

    public boolean locked(String key) {
        synchronized (ApprovalLocks.class) {
            return storage.contains(key);
        }
    }

    /** Locks the key unless it was already locked. Returns false when no lock could be stored. */
    public boolean begin(String key) {
        synchronized (ApprovalLocks.class) {
            return !storage.contains(key) && storage.put(key);
        }
    }
}
