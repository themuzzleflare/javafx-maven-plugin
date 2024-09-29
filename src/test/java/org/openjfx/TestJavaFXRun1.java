package org.openjfx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public class TestJavaFXRun1 extends Application {
    @Override
    public void start(Stage stage) {
        System.out.println("JavaFXRun1");
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
