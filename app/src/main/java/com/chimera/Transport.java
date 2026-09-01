package com.chimera;

import android.content.Context;

public interface Transport {
    String name();
    boolean isHealthy(Context ctx);
    void send(String message, Context ctx);
    void start(Context ctx);
    void stop();
}
