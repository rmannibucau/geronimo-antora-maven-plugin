package org.apache.geronimo.tools.maven.plugin.configuration;

import java.util.Collection;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class Sources {
    private Collection<Source> sources;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String url;
        private String startsPath;
        private Collection<String> tags;
        private Collection<String> branches;
    }
}
