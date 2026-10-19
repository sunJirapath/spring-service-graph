package legacy.api;

public class LegacyStyle {
    private final Runnable callback = new Runnable() {
        @Override public void run() { System.out.println("legacy"); }
    };
}
