package io.github.gaming32.modloadingscreen;

import io.github.gaming32.modloadingscreen.update.StartupUpdateCoordinator;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

public class EarlyLoadingAgent {
    public static void premain(String args, Instrumentation instrumentation) throws IOException {
        System.out.println("[ModLoadingScreen] I just want to say... I'm loading *really* **extremely** early.");
        System.setProperty("mod-loading-screen.loaded", "true");
        final AgentOptions agentOptions = AgentOptions.parse(args);

        if (agentOptions.isDirectUpdate()) {
            final int exitCode = StartupUpdateCoordinator.runStandaloneDirect(agentOptions) ? 0 : 1;
            System.exit(exitCode);
        }

        try {
            try (InputStream is = EarlyLoadingAgent.class.getClassLoader().getResourceAsStream(MlsConstants.FLATLAF_PATH)) {
                if (is != null) {
                    Path tempJar = Files.createTempFile("flatlaf", ".jar");
                    Files.copy(is, tempJar, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[ModLoadingScreen] Extracted flatlaf.jar");
                    instrumentation.appendToSystemClassLoaderSearch(new JarFile(tempJar.toFile()));
                }
            }
        } catch (Exception e) {
            // Flatlaf may already be available or extraction failed, continue
        }

        ActualLoadingScreen.startLoadingScreen(false);
        StartupUpdateCoordinator.runStandalone(agentOptions);
        instrumentation.addTransformer(
            (loader, className, classBeingRedefined, protectionDomain, classfileBuffer) ->
                MlsTransformers.instrumentClass(className, classfileBuffer),
            false
        );
    }
}
