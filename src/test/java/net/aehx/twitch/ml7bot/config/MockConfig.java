package net.aehx.twitch.ml7bot.config;

import java.util.Properties;

public class MockConfig extends Config {

    public MockConfig(Properties uncheckedProperties) {
        super();
        props = uncheckedProperties;
    }

    public MockConfig() {
        this(new Properties());
    }

    public Properties getProperties() {
        return props;
    }
}
