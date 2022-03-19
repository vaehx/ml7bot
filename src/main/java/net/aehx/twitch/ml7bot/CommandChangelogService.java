package net.aehx.twitch.ml7bot;

import com.github.philippheuer.events4j.core.EventManager;
import com.github.twitch4j.chat.TwitchChat;
import com.github.twitch4j.chat.TwitchChatBuilder;
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent;
import com.github.twitch4j.common.enums.CommandPermission;
import com.github.twitch4j.common.events.domain.EventUser;
import com.google.common.annotations.VisibleForTesting;
import net.aehx.twitch.ml7bot.config.Config;
import net.aehx.twitch.ml7bot.metrics.MetricsService;
import net.aehx.twitch.ml7bot.nightbot.NightbotAPI;
import net.aehx.twitch.ml7bot.nightbot.NightbotChannel;
import net.aehx.twitch.ml7bot.nightbot.NightbotCommand;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandChangelogService {

    /**
     * In case we detect more changes than this, we will not announce changes, cause it's likely a bug and would
     * only spam the discord channel.
     */
    public static final int MAX_CHANGES_TO_ANNOUNCE = 5;

    /**
     * Patterns to use to find moderator messages that modify (add/edit/delete) chat commands
     */
    public static final List<Pattern> COMMAND_MODIFICATION_PATTERNS = Arrays.asList(
            // Legacy commands
            Pattern.compile("^!(add|edit|del)com\\s+(?<command>[^\\s]+)", Pattern.CASE_INSENSITIVE),

            // https://docs.nightbot.tv/commands/commands
            Pattern.compile("^!commands\\s+(add|edit|delete)\\s+(?<command>[^\\s]+)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Name of the regex capture group in the {@link CommandChangelogService#COMMAND_MODIFICATION_PATTERNS} patterns
     * that contains the actual modified chat command
     */
    public static final String MODIFIED_COMMAND_GROUP_NAME = "command";

    private static final Logger LOG = LoggerFactory.getLogger(CommandChangelogService.class);

    private final Config config;
    private final GatewayDiscordClient discord;

    @VisibleForTesting
    protected NightbotAPI nightbot;

    private TwitchChat twitchChat;
    private GuildMessageChannel changelogChannel;
    private String nightbotChannelId;

    @VisibleForTesting
    protected Map<String, String> lastTwitchCommandEditors; // command name -> username

    @VisibleForTesting
    protected Map<String, NightbotCommand> commands;

    private Set<String> ignoredCommands;
    private CommandsUpdater commandsUpdater;
    private AnnouncementFormatter announcementFormatter;
    private Lock commandUpdateLock;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledSync;

    // Metrics
    private final Counter processedMessages;
    private final Counter failedCommandFetches;
    private final Counter successfulPings;


    public CommandChangelogService(Config config, GatewayDiscordClient discord, MetricsService metricsService) {
        this.config = config;
        this.discord = discord;

        nightbot = new NightbotAPI();

        lastTwitchCommandEditors = new HashMap<>();
        ignoredCommands = config.getCommandChangelogIgnoredCommands();

        final MeterRegistry registry = metricsService.getRegistry();
        processedMessages = registry.counter("processed_messages");
        failedCommandFetches = registry.counter("failed_command_fetches");
        successfulPings = registry.counter("successful_pings");
    }

    public void start() {
        // Figure out nightbot channel id from twitch channel name
        try {
            NightbotChannel channel = nightbot.fetchChannelByName(config.getCommandChangelogTwitchChannel());
            nightbotChannelId = channel.id;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch nightbot channel id from channel name " +
                    "'" + config.getCommandChangelogTwitchChannel() + "'", e);
        }

        LOG.info("Determined Nightbot channel id for name '{}': {}",
                config.getCommandChangelogTwitchChannel(), nightbotChannelId);


        scheduler = Executors.newScheduledThreadPool(1);
        commandsUpdater = new CommandsUpdater();
        commandUpdateLock = new ReentrantLock();
        announcementFormatter = new AnnouncementFormatter();

        LOG.info("Fetching nightbot commands to diff against...");
        try {
            commands = nightbot.fetchChannelCommands(nightbotChannelId);
        } catch (Exception e) {
            throw new RuntimeException("Initial nightbot commands fetch failed!", e);
        }

        LOG.info("Got {} initial nightbot commands", commands.size());


        changelogChannel = (GuildMessageChannel)discord
                .getChannelById(Snowflake.of(config.getCommandChangelogDiscordChannelId()))
                .block();
        if (changelogChannel == null)
            throw new RuntimeException("Could not access configured changelog channel");

        LOG.info("Found command changelog channel #{}", changelogChannel.getName());


        twitchChat = TwitchChatBuilder.builder()
                .build();

        twitchChat.joinChannel(config.getCommandChangelogTwitchChannel());

        EventManager eventManager = twitchChat.getEventManager();
        eventManager.onEvent(ChannelMessageEvent.class, this::onChatMessage);


        scheduleNextCommandsUpdate(config.getCommandChangelogUpdateIntervalMillis());
    }

    public void stop() {
        scheduledSync.cancel(false);

        LOG.info("Stopping twitch chat bot...");

        try {
            twitchChat.disconnect();
            twitchChat.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed waiting for twitch chat client to exit", e);
        }
    }

    private void onChatMessage(ChannelMessageEvent event) {
        processedMessages.increment();

        final EventUser user = event.getUser();
        if (user == null)
            return;

        // Ignore attempts to modify a command by non-moderators
        Set<CommandPermission> permissions = event.getPermissions();
        if (!(permissions.contains(CommandPermission.MODERATOR) || permissions.contains(CommandPermission.BROADCASTER)))
            return;

        final String username = user.getName();

        final String modifiedCommand = getModifiedCommand(event.getMessage());
        if (modifiedCommand == null)
            return;

        LOG.info("Found a command change in twitch chat: {} (User: {})", event.getMessage(), username);

        if (ignoredCommands.contains(modifiedCommand)) {
            LOG.info("Command {} was configured to be ignored. Skipping announcement...", modifiedCommand);
            return;
        }

        try {
            commandUpdateLock.lock();

            // Save username as editor. Remember that it is unlikely that another user changes the same command in
            // the dashboard until the next scheduled command update completes
            lastTwitchCommandEditors.put(modifiedCommand, username);

            // Here, we don't want to wait for the next periodic sync. But we also don't want to fetch nightbot
            // immediately, since we don't know how long the nightbot api takes to update / is cached. So instead
            // we force the next sync in a few seconds from now.
            scheduleNextCommandsUpdate(Duration.ofSeconds(5).toMillis());
        } finally {
            commandUpdateLock.unlock();
        }
    }

    /**
     * Replaces any previous scheduled commands update with the given one
     */
    private void scheduleNextCommandsUpdate(long delayMillis) {
        if (scheduledSync != null)
            scheduledSync.cancel(false);

        scheduledSync = scheduler.schedule(commandsUpdater, delayMillis, TimeUnit.MILLISECONDS);
        LOG.info("Scheduled next command list update");
    }


    /**
     * Checks if the given message is a command to modify a chat command and if so, returns the modified command.
     * Otherwise returns <code>null</code>.
     *
     * <p>
     *     For example, for a message of
     *     <pre>!addcom !foo bar...</pre>
     *     the string "!foo" is returned.
     * </p>
     */
    @VisibleForTesting
    static String getModifiedCommand(String message) {
        for (Pattern pattern : COMMAND_MODIFICATION_PATTERNS) {
            Matcher matcher = pattern.matcher(message);
            if (matcher.find())
                return matcher.group(MODIFIED_COMMAND_GROUP_NAME);
        }

        return null;
    }


    /**
     * Escapes discord markdown symbols
     */
    @VisibleForTesting
    static String escapeDiscordMarkdown(String str) {
        if (str == null)
            throw new IllegalArgumentException("str is null");

        return str.replaceAll("(?<!\\\\)([*_~`>])", "\\\\$1");
    }


    @VisibleForTesting
    class CommandsUpdater implements Runnable {

        @Override
        public void run() {
            try {
                commandUpdateLock.lock();
                runIntrnl();
                scheduleNextCommandsUpdate(config.getCommandChangelogUpdateIntervalMillis());
            } finally {
                commandUpdateLock.unlock();
            }
        }

        @VisibleForTesting
        protected void runIntrnl() {
            Map<String, NightbotCommand> fetchedCommands;
            try {
                fetchedCommands = nightbot.fetchChannelCommands(nightbotChannelId);
            } catch (Exception e) {
                failedCommandFetches.increment();
                LOG.error("Failed fetch current nightbot channel commands from API. Will ignore changes...", e);
                return;
            }

            LOG.info("Fetched {} commands from Nightbot API", fetchedCommands.size());

            // Determine command changes
            List<NightbotCommandChange> changes = new ArrayList<>();

            for (String k : fetchedCommands.keySet()) {
                if (!commands.containsKey(k))
                    changes.add(new NightbotCommandChange(null, fetchedCommands.get(k)));
            }

            for (String k : commands.keySet()) {
                if (!fetchedCommands.containsKey(k)) {
                    // Deleted command
                    changes.add(new NightbotCommandChange(commands.get(k), null));
                } else {
                    final NightbotCommand oldCmd = commands.get(k);
                    final NightbotCommand newCmd = fetchedCommands.get(k);

                    // Check change times
                    if (newCmd.updatedAt <= oldCmd.updatedAt)
                        continue;

                    // Check that the command actually changed, not just the count
                    if (newCmd.message.equals(oldCmd.message)
                            && newCmd.alias.equals(oldCmd.alias)
                            && newCmd.userLevel.equals(oldCmd.userLevel)
                            && newCmd.coolDown == oldCmd.coolDown) {
                        continue;
                    }

                    // Changed command
                    changes.add(new NightbotCommandChange(oldCmd, newCmd));
                }
            }

            // Announce changes
            if (changes.size() <= MAX_CHANGES_TO_ANNOUNCE) {
                for (NightbotCommandChange change : changes) {
                    if (change.isNew())
                        onNewCommand(change.newCommand);
                    else if (change.isDeleted())
                        onDeletedCommand(change.oldCommand);
                    else if (change.isEdited())
                        onEditedCommand(change.oldCommand, change.newCommand);
                }
            } else {
                LOG.warn("Found {} changed (new, deleted or edited) commands, which is more than the announcement " +
                                "limit of {} changes. This is likely an error. Skipping discord announcements to prevent spam.",
                        changes.size(), MAX_CHANGES_TO_ANNOUNCE);
            }

            commands = fetchedCommands;

            lastTwitchCommandEditors.clear();
        }

        protected void onNewCommand(NightbotCommand cmd) {
            if (ignoredCommands.contains(cmd.name))
                return;

            String msg = announcementFormatter.formatNewCommandAnnouncement(cmd);
            changelogChannel.createMessage(msg).block();
        }

        protected void onDeletedCommand(NightbotCommand cmd) {
            if (ignoredCommands.contains(cmd.name))
                return;

            String msg = announcementFormatter.formatDeletedCommandAnnouncement(cmd);
            changelogChannel.createMessage(msg).block();
        }

        protected void onEditedCommand(NightbotCommand oldCmd, NightbotCommand newCmd) {
            if (ignoredCommands.contains(newCmd.name))
                return;

            String msg = announcementFormatter.formatEditedCommandAnnouncement(oldCmd, newCmd);
            changelogChannel.createMessage(msg).block();
        }





        private class NightbotCommandChange {
            NightbotCommand oldCommand;
            NightbotCommand newCommand;

            public NightbotCommandChange(NightbotCommand oldCommand, NightbotCommand newCommand) {
                this.oldCommand = oldCommand;
                this.newCommand = newCommand;
            }

            boolean isNew() {
                return oldCommand == null && newCommand != null;
            }

            boolean isDeleted() {
                return oldCommand != null && newCommand == null;
            }

            boolean isEdited() {
                return oldCommand != null && newCommand != null;
            }
        }
    }


    @VisibleForTesting
    class AnnouncementFormatter {
        String formatNewCommandAnnouncement(NightbotCommand cmd) {
            String source = formatChangeSource(cmd);
            return "\u2728 **New** command `" + cmd.name + "` added " + source + ":\n" +
                    formatCommandInfo(cmd);
        }

        String formatDeletedCommandAnnouncement(NightbotCommand cmd) {
            String source = formatChangeSource(cmd);
            return "\u274C **Deleted** command `" + cmd.name + "` " + source + ":\n" +
                    formatCommandInfo(cmd);
        }

        String formatEditedCommandAnnouncement(NightbotCommand oldCmd, NightbotCommand newCmd) {
            String source = formatChangeSource(newCmd);
            return "\u270F **Edited** command `" + newCmd.name + "` " + source +
                    " to:\n" + formatCommandInfo(newCmd) + "\n" +
                    " was before:\n" + formatCommandInfo(oldCmd);
        }

        String formatCommandInfo(NightbotCommand cmd) {
            return "> User-Level: " + cmd.userLevel + " | " +
                    "Alias: " + (!cmd.alias.isEmpty() ? "`" + cmd.alias + "`" : "-") + " | " +
                    "Cooldown: " + cmd.coolDown + "s\n" +
                    "> ```\n> " + cmd.message + "\n> ```";
        }

        String formatChangeSource(NightbotCommand cmd) {
            String editor = getLikelyEditor(cmd);
            if (editor != null) {
                return "by **" + escapeDiscordMarkdown(editor) + "** in Twitch Chat";
            } else {
                return "in Dashboard";
            }
        }

        /**
         * Returns the editor's nickname in twitch chat, or null if the command was probably edited in dashboard
         */
        String getLikelyEditor(NightbotCommand cmd) {
            return lastTwitchCommandEditors.get(cmd.name);
        }
    }
}
