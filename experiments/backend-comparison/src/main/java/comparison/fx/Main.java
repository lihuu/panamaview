package comparison.fx;

import comparison.ComparisonApp;

public final class Main {
    public static void main(String[] args) throws Exception {
        JavaFxHost host = new JavaFxHost();
        ComparisonApp.run("javafx-webview", host, host::close);
        System.out.println("[comparison] launcher-returned exit=" + ComparisonApp.exitCode);
        if (ComparisonApp.exitCode != 0) System.exit(ComparisonApp.exitCode);
    }
}
