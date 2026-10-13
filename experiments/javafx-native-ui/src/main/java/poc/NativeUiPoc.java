package poc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/** Plain JavaFX controls: no HTML, WebView, JavaScript or browser engine. */
public final class NativeUiPoc extends Application {
    static volatile int exitCode = 1;
    private final Timer watchdog = new Timer("ui-poc-watchdog", true);
    private int actions;
    private boolean closeRequested;

    @Override public void start(Stage stage) {
        System.out.println("[ui-poc] environment java=" + System.getProperty("java.version")
                + " javafx=" + System.getProperty("javafx.runtime.version")
                + " image=" + System.getProperty("org.graalvm.nativeimage.imagecode", "jvm"));
        watchdog.schedule(new TimerTask() {
            @Override public void run() {
                System.err.println("[ui-poc] FAIL timeout");
                System.exit(2);
            }
        }, 30_000);
        Label heading = new Label("JavaFX native controls");
        heading.setStyle("-fx-font-size: 32px; -fx-text-fill: #e6edf3;");
        Label description = new Label("Java controls and events · GraalVM Native Image");
        description.setStyle("-fx-text-fill: #8eb7d1; -fx-font-size: 16px;");
        TextField input = new TextField("Initial value");
        input.setMaxWidth(440);
        Label result = new Label("Action count: 0");
        result.setStyle("-fx-text-fill: #90e4b7; -fx-font-size: 18px;");
        Button button = new Button("Run Java action");
        button.setStyle("-fx-font-size: 16px; -fx-padding: 10 18 10 18;");
        button.setOnAction(event -> {
            if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Wrong UI thread");
            actions++;
            result.setText("Action count: " + actions + " · " + input.getText());
            System.out.println("[ui-poc] PASS button-event on-fx-thread=true count=" + actions);
        });
        VBox root = new VBox(22, heading, description, input, button, result);
        root.setPadding(new Insets(40));
        root.setStyle("-fx-background-color: #12202f;");
        stage.setScene(new Scene(root, 780, 420));
        stage.setTitle("JavaFX native controls PoC");
        stage.setOnCloseRequest(event -> {
            closeRequested = true;
            System.out.println("[ui-poc] close-request");
            Platform.exit();
        });
        stage.show();
        System.out.println("[ui-poc] PASS stage-showing=" + stage.isShowing());
        PauseTransition interact = new PauseTransition(Duration.millis(400));
        interact.setOnFinished(event -> {
            try {
                input.setText("Native UI verified");
                button.fire();
                if (actions != 1 || !"Action count: 1 · Native UI verified".equals(result.getText())) {
                    throw new IllegalStateException("Button did not update the JavaFX UI");
                }
                System.out.println("[ui-poc] PASS control-state=" + result.getText());
                PauseTransition finish = new PauseTransition(Duration.seconds(5));
                finish.setOnFinished(done -> {
                    try {
                        snapshot(stage.getScene().snapshot(null));
                        exitCode = 0;
                        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                        stage.close();
                    } catch (Throwable failure) { fail(failure); }
                });
                finish.play();
            } catch (Throwable failure) { fail(failure); }
        });
        interact.play();
    }

    private static void snapshot(WritableImage image) throws IOException {
        String output = System.getProperty("poc.snapshot");
        if (output == null) return;
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        try (var stream = new BufferedOutputStream(Files.newOutputStream(Path.of(output)))) {
            stream.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            var pixels = image.getPixelReader();
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int argb = pixels.getArgb(x, y);
                stream.write((argb >> 16) & 255);
                stream.write((argb >> 8) & 255);
                stream.write(argb & 255);
            }
        }
        System.out.println("[ui-poc] PASS snapshot=" + width + "x" + height);
    }

    private static void fail(Throwable failure) {
        exitCode = 1;
        failure.printStackTrace();
        Platform.exit();
    }

    @Override public void stop() {
        watchdog.cancel();
        if (!closeRequested) exitCode = 1;
        System.out.println("[ui-poc] stop closeRequested=" + closeRequested + " exit=" + exitCode);
    }
}
