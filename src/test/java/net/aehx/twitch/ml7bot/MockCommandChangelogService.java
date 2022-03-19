package net.aehx.twitch.ml7bot;

import net.aehx.twitch.ml7bot.config.Config;
import net.aehx.twitch.ml7bot.metrics.MetricsService;
import net.aehx.twitch.ml7bot.nightbot.MockNightbotAPI;
import net.aehx.twitch.ml7bot.nightbot.NightbotCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class MockCommandChangelogService extends CommandChangelogService {

    private final CommandsUpdater commandsUpdater;
    private final AnnouncementFormatter announcementFormatter;

    public MockCommandChangelogService(Config config) throws Exception {
        super(config, null, new MetricsService("test", false, null, 0));

        nightbot = new MockNightbotAPI();
        commandsUpdater = new CommandsUpdater();
        announcementFormatter = new AnnouncementFormatter();

        // Do initial fetch to diff against
        commands = nightbot.fetchChannelCommands("ignored");
    }

    public class CommandsUpdater extends CommandChangelogService.CommandsUpdater {

        List<NightbotCommand> announcedNewCommands = new ArrayList<>();
        List<NightbotCommand> announcedDeletedCommands = new ArrayList<>();
        List<EditedNightbotCommand> announcedEditedCommands = new ArrayList<>();

        public void runNow() {
            runIntrnl();
        }

        @Override
        protected void onNewCommand(NightbotCommand cmd) {
            announcedNewCommands.add(cmd);
        }

        @Override
        protected void onDeletedCommand(NightbotCommand cmd) {
            announcedDeletedCommands.add(cmd);
        }

        @Override
        protected void onEditedCommand(NightbotCommand oldCmd, NightbotCommand newCmd) {
            announcedEditedCommands.add(new EditedNightbotCommand(oldCmd, newCmd));
        }

        public void clearAnnouncements() {
            announcedNewCommands.clear();
            announcedDeletedCommands.clear();
            announcedEditedCommands.clear();
        }
    }

    public CommandsUpdater getCommandsUpdater() {
        return commandsUpdater;
    }

    public AnnouncementFormatter getAnnouncementFormatter() {
        return announcementFormatter;
    }

    public MockNightbotAPI getNightbotAPI() {
        return (MockNightbotAPI) nightbot;
    }

    public void setLastTwitchCommandEditor(String command, String editor) {
        lastTwitchCommandEditors.put(command, editor);
    }

    public void clearLastTwitchCommandEditors() {
        lastTwitchCommandEditors.clear();
    }



    public static class EditedNightbotCommand {
        NightbotCommand oldCommand;
        NightbotCommand newCommand;

        public EditedNightbotCommand(NightbotCommand oldCommand, NightbotCommand newCommand) {
            this.oldCommand = oldCommand;
            this.newCommand = newCommand;
        }
    }
}
