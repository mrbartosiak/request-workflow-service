public final class B43SeededCriticalMethod {
    private static int flags(int input) {
        int enabled = 0;
        if ((input & 1) != 0) enabled++;
        if ((input & 2) != 0) enabled++;
        if ((input & 4) != 0) enabled++;
        if ((input & 8) != 0) enabled++;
        if ((input & 16) != 0) enabled++;
        if ((input & 32) != 0) enabled++;
        if ((input & 64) != 0) enabled++;
        if ((input & 128) != 0) enabled++;
        if ((input & 256) != 0) enabled++;
        if ((input & 512) != 0) enabled++;
        if ((input & 1024) != 0) enabled++;
        return enabled;
    }

    public static void main(String[] args) {
        int enabled = flags(0);
        if (enabled != 0) {
            throw new AssertionError("Expected all eleven predicates to be false");
        }
        System.out.println("input=0 enabled=0");
    }
}
