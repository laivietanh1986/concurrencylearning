package com.nanopool.level1;

// Bai 3: Publish object chua khoi tao xong - safe publication.
public class UnsafePublication {

    // Mutable fields, filled by the constructor. Nothing stops a reader from
    // seeing this object before the constructor's writes are visible to it.
    public static class Holder {
        int a;
        int b;
        String label;

        Holder(int a, int b, String label) {
            this.a = a;
            this.b = b;
            this.label = label;
        }
    }

    // Same data, but every field is final -> constructor writes get a "freeze"
    // that is guaranteed visible to any thread that sees the reference at all.
    public static class FinalHolder {
        final int a;
        final int b;
        final String label;

        FinalHolder(int a, int b, String label) {
            this.a = a;
            this.b = b;
            this.label = label;
        }
    }

    private Holder unsafeInstance;
    private volatile Holder volatileInstance;
    private FinalHolder finalFieldInstance;
    private Holder syncInstance;
    private final Object lock = new Object();

    public void publishUnsafe(int a, int b, String label) {
        unsafeInstance = new Holder(a, b, label);
    }

    public Holder readUnsafe() {
        return unsafeInstance;
    }

    public void publishVolatile(int a, int b, String label) {
        volatileInstance = new Holder(a, b, label);
    }

    public Holder readVolatile() {
        return volatileInstance;
    }

    public void publishFinalFields(int a, int b, String label) {
        finalFieldInstance = new FinalHolder(a, b, label);
    }

    public FinalHolder readFinalFields() {
        return finalFieldInstance;
    }

    public void publishSync(int a, int b, String label) {
        synchronized (lock) {
            syncInstance = new Holder(a, b, label);
        }
    }

    public Holder readSync() {
        synchronized (lock) {
            return syncInstance;
        }
    }
}
