package io.github.gaming32.modloadingscreen;

import java.io.IOException;
import java.lang.instrument.Instrumentation;

public final class ModLoadingScreenAgent {
    private ModLoadingScreenAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) throws IOException {
        EarlyLoadingAgent.premain(args, instrumentation);
    }
}