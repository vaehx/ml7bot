package de.prkz.twitch.ml7bot;

import de.prkz.twitch.ml7bot.config.Config;
import de.prkz.twitch.ml7bot.metrics.MetricsService;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.time.Duration;

public class ML7Bot {

    private static final Logger LOG = LoggerFactory.getLogger(ML7Bot.class);

    private static MetricsService metricsService;
    private static DiscordClient discordClient;
    private static GatewayDiscordClient discordGateway;
    private static ModMailService modMailService;
    private static CommandChangelogService commandChangelogService;

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar ... <path-to-config.properties>");
            System.exit(1);
        }

        final Config config = Config.parse(Paths.get(args[0]));

        metricsService = new MetricsService(
                config.getMetricsPrefix(),
                config.getMetricsEnabled(),
                config.getMetricsPrometheusHost(),
                config.getMetricsPrometheusPort());

        connectDiscord(config);

        if (config.getModMailEnabled()) {
            modMailService = new ModMailService(config, discordGateway);
            modMailService.start();
        }

        if (config.getCommandChangelogEnabled()) {
            commandChangelogService = new CommandChangelogService(config, discordGateway, metricsService);
            commandChangelogService.start();

            LOG.info("Command changelog log service started.");
        }

        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Stopping command changelog service...");
            commandChangelogService.stop();

            System.out.println("Stopping discord client...");
            discordGateway.logout().block();

            System.out.println("Waiting for main thread to quit...");
            try {
                mainThread.join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));

        discordGateway.onDisconnect().block();

        System.out.println("Main thread quitting...");
    }

    private static void connectDiscord(Config config) {
        LOG.info("Connecting to discord...");

        discordClient = DiscordClient.builder(config.getDiscordToken())
            .build();
        discordGateway = discordClient.login().block(Duration.ofMinutes(1));

        if (discordGateway == null)
            throw new RuntimeException("Could not create discord gateway");

        LOG.info("Connected to discord.");
    }
}
