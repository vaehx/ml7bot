package net.aehx.twitch.ml7bot;

import net.aehx.twitch.ml7bot.config.MockConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.aehx.twitch.ml7bot.CommandChangelogService.getModifiedCommand;
import static org.junit.jupiter.api.Assertions.*;

public class CommandChangelogServiceTest {

    private MockCommandChangelogService service;
    private MockCommandChangelogService.CommandsUpdater commandsUpdater;

    @BeforeEach
    public void beforeEach() throws Exception {
        service = new MockCommandChangelogService(new MockConfig());
        commandsUpdater = service.getCommandsUpdater();
    }

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

    @Test
    public void testCommandsUpdater() {
        JSONArray commandsArr = new JSONArray();

        JSONObject command1Obj = new JSONObject();
        command1Obj.put("_id", "cmd1");
        command1Obj.put("createdAt", "2022-02-02T17:33:22.000Z");
        command1Obj.put("updatedAt", "2022-02-02T17:33:22.355Z");
        command1Obj.put("name", "!test");
        command1Obj.put("message", "Testing 123");
        command1Obj.put("userLevel", "everyone");
        command1Obj.put("count", 0);
        command1Obj.put("coolDown", 30);

        JSONObject command2Obj = new JSONObject();
        command2Obj.put("_id", "cmd2");
        command2Obj.put("createdAt", "2022-02-02T17:33:22.000Z");
        command2Obj.put("updatedAt", "2022-02-02T17:33:22.355Z");
        command2Obj.put("name", "!test2");
        command2Obj.put("message", "Testing ABC");
        command2Obj.put("userLevel", "moderator");
        command2Obj.put("alias", "!test3");
        command2Obj.put("count", 10);
        command2Obj.put("coolDown", 30);

        commandsArr.put(command1Obj);
        commandsArr.put(command2Obj);

        JSONObject responseObj = new JSONObject();
        responseObj.put("commands", commandsArr);

        service.getNightbotAPI().setChannelCommandsResponse(responseObj);

        commandsUpdater.runNow();

        assertEquals(2, commandsUpdater.announcedNewCommands.size());
        assertTrue(commandsUpdater.announcedDeletedCommands.isEmpty());
        assertTrue(commandsUpdater.announcedEditedCommands.isEmpty());


        commandsUpdater.clearAnnouncements();


        // Edit the commands
        // updatedAt must be updated or else the command change will be ignored
        command1Obj.put("userLevel", "moderator");
        command1Obj.put("updatedAt", "2022-02-03T10:00:00.000Z");

        command2Obj.put("message", "Updated message");
        command2Obj.put("updatedAt", "2022-02-03T10:00:00.000Z");

        commandsArr = new JSONArray();
        commandsArr.put(command1Obj);
        commandsArr.put(command2Obj);

        responseObj.put("commands", commandsArr);

        service.getNightbotAPI().setChannelCommandsResponse(responseObj);

        commandsUpdater.runNow();

        assertTrue(commandsUpdater.announcedNewCommands.isEmpty());
        assertTrue(commandsUpdater.announcedDeletedCommands.isEmpty());
        assertEquals(2, commandsUpdater.announcedEditedCommands.size());


        commandsUpdater.clearAnnouncements();



        // Now remove the commands to test delete announcements
        responseObj = new JSONObject();
        responseObj.put("commands", new JSONArray());

        service.getNightbotAPI().setChannelCommandsResponse(responseObj);

        commandsUpdater.runNow();

        assertTrue(commandsUpdater.announcedNewCommands.isEmpty());
        assertEquals(2, commandsUpdater.announcedDeletedCommands.size());
        assertTrue(commandsUpdater.announcedEditedCommands.isEmpty());
    }
}
