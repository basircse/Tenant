package com.revesoft.tms.release;

import com.revesoft.tms.common.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the apps whether a newer APK exists (public: it is asked before sign-in).
 *
 * <p>Configured per app with {@code tms.apps.<admin|tenant>.*} (env {@code ADMIN_APP_LATEST}, ...).
 * An installed version below {@code minVersion} must update before it can be used.
 */
@RestController
public class AppReleaseController {

    private final Environment env;

    public AppReleaseController(Environment env) {
        this.env = env;
    }

    public record Release(String app, String latestVersion, String minVersion, String downloadUrl, String notes) {
    }

    @GetMapping("/api/app/releases/{app}")
    public Release release(@PathVariable String app) {
        if (!app.equals("admin") && !app.equals("tenant")) {
            throw BusinessException.notFound("App");
        }
        String prefix = "tms.apps." + app + ".";
        return new Release(app, blankToNull(env.getProperty(prefix + "latest-version")),
                blankToNull(env.getProperty(prefix + "min-version")),
                blankToNull(env.getProperty(prefix + "download-url")), blankToNull(env.getProperty(prefix + "notes")));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
