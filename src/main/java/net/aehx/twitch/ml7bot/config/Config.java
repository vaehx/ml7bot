package net.aehx.twitch.ml7bot.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class Config {

    protected Properties props;


    public String getDiscordToken() {
        return props.getProperty("discord.token", "").trim();
    }


    public boolean getModMailEnabled() {
        return Boolean.parseBoolean(props.getProperty("modmail.enabled", "false"));
    }

    public long getModMailDiscordChannelId() {
        return Long.parseLong(props.getProperty("modmail.discord.channelid", "-1"));
    }


    public boolean getCommandChangelogEnabled() {
        return Boolean.parseBoolean(props.getProperty("commandchangelog.enabled", "false"));
    }

    public String getCommandChangelogTwitchChannel() {
        return props.getProperty("commandchangelog.twitch.channel", "ml7support").trim();
    }

    public long getCommandChangelogUpdateIntervalMillis() {
        return Long.parseLong(props.getProperty("commandchangelog.updateinterval.millis",
                String.valueOf(Duration.ofMinutes(5).toMillis())));
    }

    public long getCommandChangelogDiscordChannelId() {
        return Long.parseLong(props.getProperty("commandchangelog.discord.channelid", ""));
    }

    public Set<String> getCommandChangelogIgnoredCommands() {
        String prop = props.getProperty("commandchangelog.ignoredcommands", "").trim();

        Set<String> ignoredCommands = new HashSet<>();
        if (!prop.isEmpty()) {
            String[] splits = prop.split("\\s*,\\s*");
            for (String split : splits)
                ignoredCommands.add(split.toLowerCase());
        }

        return ignoredCommands;
    }


    public boolean getMetricsEnabled() {
        return Boolean.parseBoolean(props.getProperty("metrics.enabled", "false"));
    }

    /** Prefix for all metrics */
    public String getMetricsPrefix() {
        return props.getProperty("metrics.prefix", "ml7bot");
    }

    /** Host on which the Prometheus Web Service should listen on */
    public String getMetricsPrometheusHost() {
        return props.getProperty("metrics.prometheus.host", "0.0.0.0");
    }

    /** HTTP Port of the Prometheus Web Service */
    public int getMetricsPrometheusPort() {
        return Integer.parseInt(props.getProperty("metrics.prometheus.port", "8089"));
    }


    public static Config fromProperties(Properties props) throws InvalidConfigException {
        Config config = new Config();
        config.props = props;

        // Validate:

        if (config.getDiscordToken().isEmpty())
            throw new InvalidConfigException("Missing discord token");

        if (config.getModMailEnabled()) {
            if (config.getModMailDiscordChannelId() <= 0)
                throw new InvalidConfigException("Missing or invalid discord mod mail channel id");
        }

        if (config.getCommandChangelogEnabled()) {
            if (config.getCommandChangelogDiscordChannelId() <= 0)
                throw new InvalidConfigException("Missing or invalid command changelog discord chhannel id");

            if (config.getCommandChangelogTwitchChannel().isEmpty())
                throw new InvalidConfigException("Missing command changelog twitch channel");
        }

        return config;
    }

    public static Config parse(Path path) throws InvalidConfigException {
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(path.toString()));
        } catch (IOException e) {
            throw new InvalidConfigException("Could not read config file: " + path.toString(), e);
        }

        return fromProperties(properties);
    }
}
