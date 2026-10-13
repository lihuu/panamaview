package poc;

import javafx.application.Application;

/** Separate launcher so the Java launcher does not special-case an Application main class. */
public final class Main {
    public static void main(String[] args) {
        try {
            Runnable launch = () -> Application.launch(WebViewPoc.class, args);
            if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))
                    && !"direct".equals(System.getProperty("poc.bootstrap"))) {
                MacNativeBootstrap.run(launch);
            } else {
                launch.run();
            }
            System.out.println("[poc] launch-returned exit=" + WebViewPoc.exitCode);
            if (WebViewPoc.exitCode != 0) System.exit(WebViewPoc.exitCode);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
