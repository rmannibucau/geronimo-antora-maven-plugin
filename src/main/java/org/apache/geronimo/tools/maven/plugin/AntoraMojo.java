package org.apache.geronimo.tools.maven.plugin;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PRE_SITE;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.eirslett.maven.plugins.frontend.lib.CacheDescriptor;
import com.github.eirslett.maven.plugins.frontend.lib.CacheResolver;
import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.InstallationException;
import com.github.eirslett.maven.plugins.frontend.lib.NodeInstaller;
import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;
import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;
import com.github.eirslett.maven.plugins.frontend.lib.YarnInstaller;

import org.apache.geronimo.tools.maven.plugin.configuration.Sources;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.utils.io.FileUtils;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepositoryManager;

import lombok.Data;

@Mojo(name = "run", threadSafe = true, defaultPhase = PRE_SITE)
public class AntoraMojo extends AbstractMojo {
    @Parameter(property = "geronimo-antora.session", defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(property = "geronimo-antora.skip", defaultValue = "false")
    private boolean skip;

    @Parameter(property = "geronimo-antora.workDirectory", defaultValue = "${user.home}/.cache/geronimo-antora/${project.groupId}_${project.artifactId}/work")
    private File workDirectory;

    @Parameter(property = "geronimo-antora.cacheDir", defaultValue = "${user.home}/.cache/geronimo-antora/${project.groupId}_${project.artifactId}/cache")
    private File cacheDir;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repositorySystemSession;

    @Parameter(property = "geronimo-antora.antoraTemplate", defaultValue = "${project.basedir}/src/main/antora/antora-template.yml")
    private File antoraTemplate;

    @Parameter(property = "geronimo-antora.siteTemplate", defaultValue = "${project.basedir}/src/main/antora/site-template.yml")
    private File siteTemplate;

    @Parameter(property = "geronimo-antora.packageTemplate", defaultValue = "${project.basedir}/src/main/antora/package-template.json")
    private File packageTemplate;

    @Parameter(property = "geronimo-antora.supplementalUiFiles", defaultValue = "${project.basedir}/src/main/antora/supplemental-ui")
    private File supplementalUiFiles;

    @Parameter(property = "geronimo-antora.includeDefaultSupplementalUiFiles", defaultValue = "true")
    private boolean includeDefaultSupplementalUiFiles;

    @Parameter(property = "geronimo-antora.siteSource", defaultValue = "${project.basedir}/src/main/antora")
    private File siteSource;

    @Parameter(property = "geronimo-antora.output", defaultValue = "${project.build.directory}/site-documentation")
    protected File output;

    @Parameter(property = "geronimo-antora.yarnScript", defaultValue = "run antora:build")
    private String yarnScript;

    @Parameter(property = "geronimo-antora.url", defaultValue = "http://geronimo.apache.org")
    protected String url;

    @Parameter(property = "geronimo-antora.local", defaultValue = "false")
    private boolean local;

    @Parameter
    private Sources sources;

    @Parameter
    private Map<String, String> asciidoctorAttributes;

    @Parameter
    private Map<String, String> yarnEnvironmentVariables;

    @Parameter(property = "geronimo-antora.googleAnalytics")
    private String googleAnalytics;

    @Parameter(property = "geronimo-antora.nodeDownloadRoot", defaultValue = NodeInstaller.DEFAULT_NODEJS_DOWNLOAD_ROOT)
    private String nodeDownloadRoot;

    @Parameter(property = "geronimo-antora.yarnDownloadRoot", defaultValue = YarnInstaller.DEFAULT_YARN_DOWNLOAD_ROOT)
    private String yarnDownloadRoot;

    @Parameter(property = "geronimo-antora.nodeVersion", defaultValue = "v8.4.0")
    private String nodeVersion;

    @Parameter(property = "geronimo-antora.yarnVersion", defaultValue = "v1.3.2")
    private String yarnVersion;

    @Parameter(property = "geronimo-antora.serverId", defaultValue = "node")
    private String serverId;

    @Parameter(property = "geronimo-antora.npmRegistryURL", defaultValue = "")
    private String npmRegistryURL;

    @Parameter(property = "geronimo-antora.antoraVersion", defaultValue = "1.0.0")
    private String antoraVersion;

    @Parameter(property = "geronimo-antora.color.headerFooter", defaultValue = "#0675c1")
    private String colorHeaderFooter;

    @Parameter(property = "geronimo-antora.color.colorNavLink", defaultValue = "#0093c9")
    private String colorNavLink;

    @Parameter(property = "geronimo-antora.color.colorNav", defaultValue = "#82bd41")
    private String colorNav;

    @Component(role = SettingsDecrypter.class)
    private SettingsDecrypter decrypter;

    @Component(hint = "default")
    private MavenResourcesFiltering mavenResourcesFiltering;

    private ProxyConfig proxyConfig;
    private FrontendPluginFactory factory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().warn("Skipping " + getClass().getSimpleName().replace("Mojo", "") + " execution");
            return;
        }
        init();
        try {
            setupNodeEnv();
        } catch (final InstallationException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        addAntoraProjectProperties();
        try {
            createMissingTemplates();
            filterTemplates();
            installDependencies();
            postInstallDependencies();
            runAntora();
        } catch (final IOException | TaskRunnerException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    protected void installDependencies() throws TaskRunnerException {
        yarn("install");
    }

    protected void runAntora() throws TaskRunnerException, IOException {
        synchronizeSources();
        yarn(yarnScript);
    }

    protected void synchronizeSources() throws IOException {
        final File modules = new File(workDirectory, "modules");
        if (modules.exists()) {
            FileUtils.deleteDirectory(modules);
        }
        if (siteSource.exists()) {
            FileUtils.copyDirectory(siteSource, workDirectory, "**", "*.yml");
        }
    }

    private void yarn(final String install) throws TaskRunnerException {
        factory.getYarnRunner(proxyConfig, npmRegistryURL).execute(install, yarnEnvironmentVariables);
    }

    private void init() {
        if (sources == null) { // we use scm>url and only tags with a start path of ${project.basedir}/src/main/antora
            sources = new Sources();
            final Collection<String> branchesFilter = singletonList("master");
            final String startsPath = project.getBasedir().getName() + "/src/main/antora";
            if (local) {
                sources.setSources(singletonList(new Sources.Source(
                        findRootFolder(), startsPath,
                        emptyList(), branchesFilter)));
            } else {
                final MavenProject rootMavenProject = findRootMavenProject();
                if (rootMavenProject.getScm() != null && rootMavenProject.getScm().getUrl() != null) {
                    final String url = rootMavenProject.getScm().getUrl();
                    sources.setSources(singletonList(new Sources.Source(
                            url + (url.startsWith("http") && !url.endsWith(".git") ? ".git" : ""),
                            startsPath,
                            of(findRootMavenProject().getArtifactId())
                                    .map(aId -> {
                                        final String[] parts = aId.split("\\-");
                                        if ("geronimo".equals(parts[0]) && parts.length > 1) {
                                            return Stream.of(parts[0] + '-' + parts[1], parts[1])
                                                    .map(v -> v + "-*")
                                                    .collect(toSet());
                                        }
                                        return singleton(parts[0] + "-*");
                                    })
                                    .orElseGet(() -> singleton(project.getArtifactId() + "-*")),
                            branchesFilter)));
                }
            }
        }
        proxyConfig = new ProxyConfig(ofNullable(session.getSettings().getProxies())
                .map(p -> p.stream()
                        .filter(Proxy::isActive)
                        .map(pxy -> {
                            final Proxy decrypted = of(pxy)
                                    .map(it -> decrypter.decrypt(new DefaultSettingsDecryptionRequest(it)).getProxy())
                                    .orElse(pxy);
                            return new ProxyConfig.Proxy(
                                    decrypted.getId(), decrypted.getProtocol(), decrypted.getHost(), decrypted.getPort(),
                                    decrypted.getUsername(), decrypted.getPassword(), decrypted.getNonProxyHosts());
                        })
                        .collect(toList()))
                .orElseGet(Collections::emptyList));
        factory = new FrontendPluginFactory(
                workDirectory, workDirectory, new RepositoryCacheResolver(repositorySystemSession));
    }

    private String findRootFolder() {
        final MavenProject project = findRootMavenProject();
        return project.getBasedir().getAbsolutePath().replace("\\", "\\\\");
    }

    private MavenProject findRootMavenProject() {
        MavenProject project = this.project;
        while (project.getParent() != null) { // assume parent is aligned on the filesystem for now
            project = project.getParent();
        }
        return project;
    }

    private void addAntoraProjectProperties() {
        if (!project.getProperties().containsKey("antora.versions.release")) {
            project.getProperties().put("antora.versions.release", project.getVersion().replace("-SNAPSHOT", ""));
        }
        if (!project.getProperties().containsKey("antora.color.headerFooter")) {
            project.getProperties().put("antora.color.headerFooter", colorHeaderFooter);
        }
        if (!project.getProperties().containsKey("antora.color.colorNavLink")) {
            project.getProperties().put("antora.color.colorNavLink", colorNavLink);
        }
        if (!project.getProperties().containsKey("antora.color.colorNav")) {
            project.getProperties().put("antora.color.colorNav", colorNav);
        }
    }

    private void createMissingTemplates() throws IOException, MojoExecutionException {
        if (!antoraTemplate.exists()) {
            antoraTemplate = createVolatileTemplate("antora.yml");
            FileUtils.fileWrite(antoraTemplate, "UTF-8", "name: " + getSiteName() + "\n" +
                    "title: " + getTitleTemplate() + "\n" +
                    "version: '${antora.versions.release}'\n" +
                    "colorNav:\n" +
                    "- modules/ROOT/colorNav.adoc");
            getLog().info("Created antora.yml since " + antoraTemplate + " doesn't exist");
        }
        if (!siteTemplate.exists()) {
            siteTemplate = createVolatileTemplate("site.yml");
            FileUtils.fileWrite(siteTemplate, "UTF-8", "site:\n" +
                    "  title: " + getTitleTemplate() + "\n" +
                    "  start_page: " + getSiteName() + "::index\n" +
                    "  url: " + url + "\n" +
                    ofNullable(googleAnalytics).map(v -> "  keys:\n    google_analytics: " + v + "\n").orElse("") +
                    "content:\n" +
                    "  sources:\n" +
                    sources.getSources().stream()
                            .map(s -> "  - url: " + s.getUrl() + "\n" +
                                    ofNullable(s.getTags()).filter(t -> !t.isEmpty())
                                            .map(t -> "    tags:\n" + t.stream().map(it -> "      - " + it)
                                                    .collect(joining("\n", "", "\n")))
                                            .orElse("") +
                                    ofNullable(s.getBranches()).filter(t -> !t.isEmpty())
                                            .map(t -> "    branches:\n" + t.stream().map(it -> "      - " + it)
                                                    .collect(joining("\n", "", "\n")))
                                            .orElse("") +
                                    ofNullable(s.getStartsPath()).map(path -> "    start_path: " + path + "\n").orElse("")).collect(joining("\n")) +
                    "ui:\n" +
                    "  bundle:\n" +
                    "    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/master/raw/build/ui-bundle.zip?job=bundle-stable\n" +
                    "    snapshot: true\n" +
                    ofNullable(supplementalUiFiles).filter(File::exists)
                            .map(f -> "  supplemental_files: " + f.getAbsolutePath().replace("\\", "\\\\") + "\n")
                            .orElse("") +
                    "runtime:\n" +
                    "  cache_dir: " + cacheDir.getAbsolutePath().replace("\\", "\\\\") + "\n" +
                    "output:\n" +
                    " clean: false\n" +
                    " dir: " + output.getAbsolutePath().replace("\\", "\\\\") + "\n" +
                    "asciidoc:\n" +
                    "  attributes:\n" +
                    ofNullable(asciidoctorAttributes)
                            .map(e -> e.entrySet().stream()
                                    .map(it -> "    " + it.getKey() + ": " + it.getValue())
                                    .collect(joining("\n", "", "\n")))
                            .orElse("    project_version: ${versions.release}\n" +
                                    "    numbered: true\n" +
                                    "    hide-uri-scheme: true"));
            getLog().info("Created site.yml since " + siteTemplate + " doesn't exist");
        }
        if (!packageTemplate.exists()) {
            packageTemplate = createVolatileTemplate("package.json");
            FileUtils.fileWrite(packageTemplate, "UTF-8", "{\n" +
                    "  \"name\": \"${project.artifactId}\",\n" +
                    "  \"version\": \"${project.version}\",\n" +
                    "  \"private\": true,\n" +
                    "  \"dependencies\": {\n" +
                    // TODO: "    \"js-search\": \"1.4.2\",\n" + // see https://github.com/Talend/component-runtime/blob/master/documentation/src/main/antora/supplemental-ui/partials/search.hbs
                    (includeDefaultSupplementalUiFiles ? "    \"highlight.js\": \"9.12.0 \",\n" : "") +
                    "    \"@antora/cli\": \"" + antoraVersion + "\",\n" +
                    "    \"@antora/site-generator-default\": \"" + antoraVersion + "\"\n" +
                    "  },\n" +
                    "  \"scripts\": {\n" +
                    "    \"antora:build\": \"antora --stacktrace " + new File(workDirectory, siteTemplate.getName()).getAbsolutePath().replace("\\", "\\\\") + "\"\n" +
                    "  }\n" +
                    "}");
            getLog().info("Created package.json since " + packageTemplate + " doesn't exist");
        }
        if (includeDefaultSupplementalUiFiles) {
            if (!supplementalUiFiles.exists()) {
                FileUtils.forceMkdir(supplementalUiFiles);
            }
            final File tempAssetFolder = new File(project.getBuild().getDirectory(), "geronimo-antora/assets");
            Stream.of("css/geronimo.css", "partials/head-meta.hbs", "partials/header-content.hbs", "partials/toolbar.hbs", "partials/footer-content.hbs")
                    .forEach(from -> {
                        try {
                            FileUtils.copyURLToFile(
                                    requireNonNull(Thread.currentThread().getContextClassLoader()
                                            .getResource("geronimo-antora/" + from), "no " + from + " asset found"),
                                    new File(tempAssetFolder, from));
                        } catch (final IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            final MavenResourcesExecution mavenResourcesExecution =
                    new MavenResourcesExecution(singletonList(new Resource() {{
                        setDirectory(tempAssetFolder.getAbsolutePath());
                        setFiltering(true);
                        setLog(getLog());
                    }}), supplementalUiFiles, project, "UTF-8", emptyList(), emptyList(), session);
            mavenResourcesExecution.setInjectProjectBuildFilters(true);
            mavenResourcesExecution.setOverwrite(true);
            try {
                mavenResourcesFiltering.filterResources(mavenResourcesExecution);
            } catch (final MavenFilteringException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
        }
    }

    private String getSiteName() {
        return "${project.artifactId}";
    }

    private String getTitleTemplate() {
        return project.getName() == null ? "${project.artifactId}" : "${project.name}";
    }

    private File createVolatileTemplate(final String name) throws IOException {
        final File file = new File(project.getBuild().getDirectory(), "geronimo-antora/" + name);
        FileUtils.forceMkdir(file.getParentFile());
        return file;
    }

    private void postInstallDependencies() throws IOException {
        // copy idea.css theme
        FileUtils.copyFile(
                new File(workDirectory, "node_modules/highlight.js/styles/idea.css"),
                new File(supplementalUiFiles, "css/idea.css"));
    }

    private void filterTemplates() throws MojoExecutionException, IOException {
        final List<Resource> resources = Stream.of(antoraTemplate, siteTemplate, packageTemplate)
                .map(file ->
                        new Resource() {{
                            setDirectory(file.getParentFile().getAbsolutePath());
                            setIncludes(singletonList(file.getName()));
                            setFiltering(true);
                            setLog(getLog());
                        }})
                .collect(toList());
        final MavenResourcesExecution mavenResourcesExecution =
                new MavenResourcesExecution(resources, workDirectory, project, "UTF-8", emptyList(), emptyList(), session);
        mavenResourcesExecution.setInjectProjectBuildFilters(true);
        mavenResourcesExecution.setOverwrite(true);
        try {
            mavenResourcesFiltering.filterResources(mavenResourcesExecution);
        } catch (final MavenFilteringException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        // we also ensure antora.yml is "there" since it MUST be committed to be able to generate the site
        // in a correct versionned fashion
        //
        // TODO: antora uses a virtual filesystem, would be neat to be able to not require antora.yml
        // so we should plug in that extension point to do it (only the version changes in antora.yml)
        final File antoraYml = new File(siteSource, "antora.yml");
        if (!antoraYml.exists()) {
            FileUtils.copyFile(new File(workDirectory, antoraTemplate.getName()), antoraYml);
        }
    }

    private void setupNodeEnv() throws InstallationException {
        final NodeInstaller nodeInstaller = factory.getNodeInstaller(proxyConfig)
                .setNodeDownloadRoot(nodeDownloadRoot)
                .setNodeVersion(nodeVersion);
        final YarnInstaller yarnInstaller = factory.getYarnInstaller(proxyConfig)
                .setYarnDownloadRoot(yarnDownloadRoot)
                .setYarnVersion(yarnVersion);

        findServer(serverId).ifPresent(s -> {
            nodeInstaller.setUserName(s.getUsername());
            nodeInstaller.setPassword(s.getUsername());
            yarnInstaller.setUserName(s.getUsername());
            yarnInstaller.setPassword(s.getUsername());
        });

        nodeInstaller.install();
        yarnInstaller.install();
    }

    private Optional<Server> findServer(final String serverId) {
        return ofNullable(serverId)
                .map(String::trim)
                .filter(s -> !s.trim().isEmpty())
                .map(s -> session.getSettings().getServer(s))
                .flatMap(s -> of(s)
                        .map(toDecrypt -> decrypter.decrypt(new DefaultSettingsDecryptionRequest(toDecrypt)).getServer()));
    }

    @Data
    private static class RepositoryCacheResolver implements CacheResolver {
        private final RepositorySystemSession repositorySystemSession;

        @Override
        public File resolve(final CacheDescriptor cacheDescriptor) {
            final LocalRepositoryManager manager = repositorySystemSession.getLocalRepositoryManager();
            return new File(
                    manager.getRepository().getBasedir(),
                    manager.getPathForLocalArtifact(createArtifact(cacheDescriptor)));
        }

        private DefaultArtifact createArtifact(CacheDescriptor cacheDescriptor) {
            return new DefaultArtifact(
                    "org.apache.geronimo.tools.cache",
                    cacheDescriptor.getName(),
                    cacheDescriptor.getClassifier() == null ? "" : cacheDescriptor.getClassifier(),
                    cacheDescriptor.getExtension(),
                    cacheDescriptor.getVersion().replaceAll("^v", ""));
        }
    }
}
