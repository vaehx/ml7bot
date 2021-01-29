package de.prkz.twitch.ml7bot.config;

public class InvalidConfigException extends Exception {

    public InvalidConfigException(String message) {
        super(message);
    }

    public InvalidConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
