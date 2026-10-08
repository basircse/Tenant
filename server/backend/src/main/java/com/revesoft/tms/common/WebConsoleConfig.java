package com.revesoft.tms.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the vendor's web console (the {@code web_console} production build) under
 * {@code /console/} when {@code tms.web-console.dir} points at its folder, so a deployment needs
 * only this one process and the console calls the API on the same address. Paths without a file
 * extension are the console's own pages and get its {@code index.html}; a missing asset is a 404.
 */
@Configuration
@ConditionalOnExpression("'${tms.web-console.dir:}' != ''")
public class WebConsoleConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConsoleConfig.class);

    private final String location;

    WebConsoleConfig(@Value("${tms.web-console.dir}") String dir) {
        Path path = Path.of(dir).toAbsolutePath().normalize();
        this.location = path.toUri().toString().replaceAll("/?$", "/");
        if (Files.isReadable(path.resolve("index.html"))) {
            log.info("Serving the web console from {} at /console/", path);
        } else {
            log.warn("Web console folder {} has no index.html; /console/ will return 404", path);
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/console/");
        registry.addRedirectViewController("/console", "/console/");
        // The resource handler ignores an empty path, so the console's home page is forwarded.
        registry.addViewController("/console/").setViewName("forward:/console/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/**")
                .addResourceLocations(location)
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        Resource found = super.getResource(path, location);
                        if (found != null || path.contains(".")) {
                            return found;
                        }
                        return super.getResource("index.html", location);
                    }
                });
    }
}
