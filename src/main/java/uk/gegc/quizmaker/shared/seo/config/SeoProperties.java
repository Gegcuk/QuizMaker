package uk.gegc.quizmaker.shared.seo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "seo")
public class SeoProperties {

    private List<SitemapEntry> staticEntries = new ArrayList<>();

    private List<String> sitemapPaths = new ArrayList<>();

    private Robots robots = new Robots();

    @Data
    public static class Robots {
        private String userAgent;
        private List<String> allow = new ArrayList<>();
        private List<String> disallow = new ArrayList<>();
    }

    @Data
    public static class SitemapEntry {
        private String path;
        private String changefreq;
        private Double priority;

        public SitemapEntry() {
        }

        public SitemapEntry(String path, String changefreq, Double priority) {
            this.path = path;
            this.changefreq = changefreq;
            this.priority = priority;
        }
    }
}
