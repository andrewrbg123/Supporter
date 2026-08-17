package com.peoplesserver.supportermod.platform.hytale;

import com.hypixel.hytale.logger.HytaleLogger;
import com.peoplesserver.supportermod.platform.PluginLog;
import java.util.logging.Level;

/**
 * {@link PluginLog} backed by the server's own logger.
 *
 * <p>{@code HytaleLogger} is a Flogger {@code AbstractLogger}, so the call shape is
 * {@code at(Level).log(message)} rather than {@code log(level, message)}. Verified against
 * the server jar:
 *
 * <pre>
 * public HytaleLogger.Api at(java.util.logging.Level)
 * public interface HytaleLogger.Api extends com.google.common.flogger.LoggingApi&lt;Api&gt;
 * </pre>
 *
 * <p>The {@code [SupporterMod]} prefix is deliberate. Every other plugin on this server
 * prefixes its lines, and the server log is read by grepping for a plugin name when something
 * breaks at boot.
 */
public final class HytaleLog implements PluginLog {

    private static final String PREFIX = "[SupporterMod] ";

    private final HytaleLogger logger;

    public HytaleLog(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public void info(String message) {
        logger.at(Level.INFO).log(PREFIX + message);
    }

    @Override
    public void warn(String message) {
        logger.at(Level.WARNING).log(PREFIX + message);
    }

    @Override
    public void error(String message, Throwable cause) {
        // withCause before log, or Flogger drops the stack trace. A SupporterMod failure is
        // a payment failure, so the trace is the whole point of the line.
        logger.at(Level.SEVERE).withCause(cause).log(PREFIX + message);
    }
}
