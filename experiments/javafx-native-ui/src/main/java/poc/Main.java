package poc;

import javafx.application.Application;

public final class Main {
    public static void main(String[] args) {
        try {
            Runnable launch = () -> Application.launch(NativeUiPoc.class, args);
            if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
                MacNativeBootstrap.run(launch);
            } else {
                launch.run();
            }
            System.out.println("[ui-poc] launch-returned exit=" + NativeUiPoc.exitCode);
            if (NativeUiPoc.exitCode != 0) System.exit(NativeUiPoc.exitCode);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
