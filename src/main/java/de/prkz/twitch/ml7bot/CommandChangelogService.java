package de.prkz.twitch.ml7bot;

import de.prkz.twitch.ml7bot.config.Config;
import de.prkz.twitch.ml7bot.metrics.MetricsService;
import de.prkz.twitch.ml7bot.nightbot.NightbotAPI;
import de.prkz.twitch.ml7bot.nightbot.NightbotChannel;
import de.prkz.twitch.ml7bot.nightbot.NightbotCommand;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.pircbotx.Configuration;
import org.pircbotx.MultiBotManager;
import org.pircbotx.PircBotX;
import org.pircbotx.delay.StaticDelay;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandChangelogService {

    /**
     * In case we detect more changes than this, we will not announce changes, cause it's likely a bug and would
     * only spam the discord channel.
     */
    private static final int MAX_CHANGES_TO_ANNOUNCE = 5;

    private static final Logger LOG = LoggerFactory.getLogger(CommandChangelogService.class);

    private final Config config;
    private final GatewayDiscordClient discord;

    private MultiBotManager botManager;
    private GuildMessageChannel changelogChannel;
    private String nightbotChannelId;
    private Map<String, String> lastTwitchCommandEditors; // command name -> username
    private Map<String, NightbotCommand> commands;
    private Set<String> ignoredCommands;
    private CommandsUpdater commandsUpdater;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledSync;

    // Metrics
    private final Counter processedMessages;
    private final Counter failedCommandFetches;
    private final Counter successfulPings;


    public CommandChangelogService(Config config, GatewayDiscordClient discord, MetricsService metricsService) {
        this.config = config;
        this.discord = discord;

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
            NightbotChannel channel = NightbotAPI.fetchChannelByName(config.getCommandChangelogTwitchChannel());
            nightbotChannelId = channel.id;
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch nightbot channel id from channel name " +
                    "'" + config.getCommandChangelogTwitchChannel() + "'", e);
        }

        LOG.info("Determined Nightbot channel id for name '{}': {}",
                config.getCommandChangelogTwitchChannel(), nightbotChannelId);


        scheduler = Executors.newScheduledThreadPool(1);
        commandsUpdater = new CommandsUpdater();

        LOG.info("Fetching nightbot commands to diff against...");
        try {
            commands = NightbotAPI.fetchChannelCommands(nightbotChannelId);
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


        BotListener listener = new BotListener();

        Configuration botConfig = new Configuration.Builder()
                .setName("justinfan82394")
                .addServer("irc.chat.twitch.tv", 6667)
                .addAutoJoinChannel("#" + config.getCommandChangelogTwitchChannel())
                .setAutoReconnect(true)
                .setAutoReconnectAttempts(20)
                .setAutoReconnectDelay(new StaticDelay(10000))
                .addListener(listener)
                .setShutdownHookEnabled(false)
                .setSocketTimeout(60 * 1000) // PING every 1min
                .setOnJoinWhoEnabled(false) // twitch doesn't support WHO
                .buildConfiguration();

        PircBotX bot = new PircBotX(botConfig);

        botManager = new MultiBotManager();
        botManager.addBot(bot);

        botManager.start();

        LOG.info("Bot manager started");


        scheduleNextCommandsUpdate(config.getCommandChangelogUpdateIntervalMillis());
    }

    public void stop() {
        scheduledSync.cancel(false);

        LOG.info("Stopping twitch chat bot...");

        try {
            botManager.getBots().forEach(PircBotX::stopBotReconnect);
            botManager.stopAndWait();
        } catch (Exception e) {
            throw new RuntimeException("Failed waiting for twitch chat to exit", e);
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


    private class CommandsUpdater implements Runnable {

        @Override
        public void run() {
            Map<String, NightbotCommand> fetchedCommands;
            try {
                fetchedCommands = NightbotAPI.fetchChannelCommands(nightbotChannelId);
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

            scheduleNextCommandsUpdate(config.getCommandChangelogUpdateIntervalMillis());
        }

        private void onNewCommand(NightbotCommand cmd) {
            if (ignoredCommands.contains(cmd.name))
                return;

            String editor = getLikelyEditor(cmd);
            changelogChannel.createMessage("\u2728 **New** command `" + cmd.name + "` added" +
                    (editor != null ? " by **" + editor + "** in Twitch Chat" : " in Dashboard") +
                    ":\n" + getCommandInfo(cmd)).block();
        }

        private void onDeletedCommand(NightbotCommand cmd) {
            if (ignoredCommands.contains(cmd.name))
                return;

            String editor = getLikelyEditor(cmd);
            changelogChannel.createMessage("\u274C **Deleted** command `" + cmd.name + "`" +
                    (editor != null ? " by **" + editor + "** in Twitch Chat" : " in Dashboard") +
                    ":\n" + getCommandInfo(cmd)).block();
        }

        private void onEditedCommand(NightbotCommand oldCmd, NightbotCommand newCmd) {
            if (ignoredCommands.contains(newCmd.name))
                return;

            String editor = getLikelyEditor(newCmd);
            changelogChannel.createMessage("\u270F **Edited** command `" + newCmd.name + "`" +
                    (editor != null ? " by **" + editor + "** in Twitch Chat" : " in Dashboard") +
                    " to:\n" + getCommandInfo(newCmd) + "\n" +
                    " Was:\n" + getCommandInfo(oldCmd)).block();
        }

        private String getCommandInfo(NightbotCommand cmd) {
            return "> User-Level: " + cmd.userLevel + " | " +
                    "Alias: " + (!cmd.alias.isEmpty() ? "`" + cmd.alias + "`" : "-") + " | " +
                    "Cooldown: " + cmd.coolDown + "s\n" +
                    "> ```\n> " + cmd.message + "\n> ```";
        }

        /**
         * Returns the editor's nickname in twitch chat, or null if the command was probably edited in dashboard
         */
        private String getLikelyEditor(NightbotCommand cmd) {
            return lastTwitchCommandEditors.get(cmd.name);
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


    private class BotListener extends ListenerAdapter {

        @Override
        public void onMessage(MessageEvent event) throws Exception {


            if (event.getUser() == null)
                return;

            processedMessages.increment();

            Matcher matcher = Pattern
                    .compile("^!(add|edit|del)com\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(event.getMessage());
            if (!matcher.find())
                return;

            String command = matcher.group(2);
            if (ignoredCommands.contains(command))
                return;

            // Save username as editor. Remember that it is unlikely that another user changes the same command in
            // the dashboard until the next scheduled command update completes
            lastTwitchCommandEditors.put(command, event.getUser().getNick());

            // Here, we don't want to wait for the next periodic sync. But we also don't want to fetch nightbot
            // immediately, since we don't know how long the nightbot api takes to update / is cached. So instead
            // we force the next sync in a few seconds from now.
            scheduleNextCommandsUpdate(Duration.ofSeconds(5).toMillis());
        }

        @Override
        public void onConnectAttemptFailed(ConnectAttemptFailedEvent event) throws Exception {
            if (event.getRemainingAttempts() <= 0) {
                int attempts = event.getBot().getConfiguration().getAutoReconnectAttempts();
                throw new RuntimeException("Bot could not reconnect after " + attempts + " attempts. " +
                        "Forcing crash to restart service...");
            }
        }

        @Override
        public void onUnknown(UnknownEvent event) throws Exception {
            // Pong Server responses to periodic PING requests are not handled anywhere else
            // Server sends PONG responses to our PING responses (every time socket timeout was hit while waiting for
            // another message); and PING requests from the twitch
            if (event.getCommand().equals("PONG") || event.getCommand().equals("PING"))
                successfulPings.increment();
        }
    }
}
