package de.prkz.twitch.ml7bot;

import org.junit.jupiter.api.Test;

import static de.prkz.twitch.ml7bot.CommandChangelogService.getModifiedCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CommandChangelogServiceTest {

    @Test
    public void testGetModifiedCommand() {
        assertEquals("!twitch", getModifiedCommand("!addcom !twitch @$(touser) -> https://twitch.tv"));
        assertEquals("!twitch", getModifiedCommand("!editcom !twitch @$(touser) -> https://twitch.tv"));
        assertEquals("!twitch", getModifiedCommand("!delcom !twitch       "));

        assertEquals("!twitch", getModifiedCommand("!commands add !twitch @$(touser) -> https://twitch.tv"));
        assertEquals("!twitch", getModifiedCommand("!commands edit !twitch @$(touser) -> https://twitch.tv"));
        assertEquals("!twitch", getModifiedCommand("!commands delete !twitch"));

        assertNull(getModifiedCommand("Hello world"));
        assertNull(getModifiedCommand("!commands This is not a valid command"));
        assertNull(getModifiedCommand("addcom !twitch test")); // missing leading !

        assertEquals("!twitch2", getModifiedCommand("!addcom !twitch2 -a=!twitch"));
        assertEquals("twitch", getModifiedCommand("!addcom twitch test")); // modified needn't start with !
    }
}
