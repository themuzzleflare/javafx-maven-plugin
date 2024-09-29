package org.openjfx.model;

/**
 * All the classes and dependencies are added to either the classpath or modulepath depending on the option set in the plugin configuration.
 * If not set, the plugin chooses the suitable option for classes and dependencies based on a few parameters, like presence of module descriptor file.
 */
public enum RuntimePathOption {
    /**
     * Puts all the dependencies on the classpath. If a module-info.java is present, it is ignored.
     * A <a href="https://github.com/openjfx/samples/blob/master/CommandLine/Non-modular/CLI/hellofx/src/hellofx/Launcher.java">Launcher class</a> is
     * required to run a JavaFX application from the classpath.
     */
    CLASSPATH,
    /**
     * Puts all the dependencies on the modulepath.
     */
    MODULEPATH
}
