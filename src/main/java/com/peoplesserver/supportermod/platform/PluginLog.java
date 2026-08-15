package com.peoplesserver.supportermod.platform;

/** Port for plugin logging, so the core does not bind to the server's logger. */
public interface PluginLog {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable cause);

    /** Logs to stdout. Used by tests and by early startup before the server logger exists. */
    static PluginLog console() {
        return new PluginLog() {
            @Override
            public void info(String message) {
                System.out.println("[SupporterMod] " + message);
            }

            @Override
            public void warn(String message) {
                System.out.println("[SupporterMod] WARN " + message);
            }

            @Override
            public void error(String message, Throwable cause) {
                System.err.println("[SupporterMod] ERROR " + message);
                if (cause != null) {
                    cause.printStackTrace();
                }
            }
        };
    }
}
