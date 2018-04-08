package org.apache.geronimo.tools.maven.plugin;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.maven.plugins.annotations.LifecyclePhase.PRE_SITE;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;

import org.apache.commons.cli.CommandLine;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.meecrowave.Meecrowave;
import org.apache.meecrowave.runner.Cli;

@Mojo(name = "http", threadSafe = true, defaultPhase = PRE_SITE)
public class AntoraHttpMojo extends AntoraMojo {
    @Parameter
    private String[] meecrowaveArgs;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            FileUtils.forceMkdir(output);
        } catch (final IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        getLog().info("Starting HTTP server");
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<MojoExecutionException> error = new AtomicReference<>();
        try (final Cli cli = new Cli(ofNullable(meecrowaveArgs)
                .orElseGet(() -> new String[]{
                        "--docbase=" + output.getAbsolutePath(),
                        "--use-shutdown-hook=false",
                        "--web-resource-cached=false"
                })) {
            @Override
            protected void doWait(final Meecrowave meecrowave, final CommandLine line) {
                url = "http://localhost:" + meecrowave.getConfiguration().getHttpPort();
                try {
                    AntoraHttpMojo.super.execute();
                } catch (final MojoExecutionException e) {
                    error.set(e);
                } catch (final MojoFailureException e) {
                    error.set(new MojoExecutionException(e.getMessage(), e));
                } finally {
                    started.countDown();
                }
                getLog().info("Server started, you can access the website at " + url);
                super.doWait(meecrowave, line);
            }
        }) {
            final Thread server = new Thread(cli);
            server.setName(getClass().getName() + "_server");
            server.start();

            try {
                if (!started.await(1, MINUTES)) {
                    getLog().warn("Server didn't start in 1mn, something is likely wrong");
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (error.get() != null) {
                throw error.get();
            }

            final Scanner reader = new Scanner(System.in);
            getLog().info("Waiting for command: [r|redeploy|q|quit|X|exit]");

            String line;
            while ((line = reader.nextLine()) != null) {
                final String cmd = line.trim();

                if (Stream.of("quit", "q", "exit", "X").anyMatch(s -> s.equals(cmd))) {
                    break;
                }

                if ("ignore".equals(cmd)) {
                    continue;
                }

                if (Stream.of("r", "redeploy").anyMatch(s -> s.equals(cmd))) {
                    try {
                        super.runAntora();
                    } catch (final TaskRunnerException | IOException e) {
                        getLog().error(e.getMessage(), e);
                    }
                    continue;
                }

                getLog().warn("Command '" + cmd + "' not understood.");
            }
        }
    }
}
