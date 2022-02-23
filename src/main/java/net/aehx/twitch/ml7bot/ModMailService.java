package net.aehx.twitch.ml7bot;

import net.aehx.twitch.ml7bot.config.Config;
import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.PrivateChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModMailService {

    private final static Logger LOG = LoggerFactory.getLogger(ModMailService.class);

    private final Config config;
    private final GatewayDiscordClient discord;
    private GuildMessageChannel modMailChannel;


    public ModMailService(Config config, GatewayDiscordClient discord) {
        this.config = config;
        this.discord = discord;
    }

    public void start() {
        modMailChannel = (GuildMessageChannel)discord
                .getChannelById(Snowflake.of(config.getModMailDiscordChannelId()))
                .block();

        if (modMailChannel == null)
            throw new RuntimeException("Could not find mod mail channel");

        LOG.info("Found mod mail channel");


        discord.on(MessageCreateEvent.class).subscribe(event -> {
            final Message message = event.getMessage();
            final MessageChannel channel = message.getChannel().block();
            if (channel == null)
                return;

            LOG.info("Channel: {}", channel.getId().asLong());

            if (channel.getType() == Channel.Type.DM) {
                if (!message.getAuthor().isPresent()) {
                    LOG.info("Ignored private message from unknown author: {}", message.getContent());
                    return; // ignore
                }

                final User author = message.getAuthor().get();

                if (author.isBot())
                    return;

                // Pass the message straight on to modmail channel
                long authorId = author.getId().asLong();
                String msg = "**User " + author.getMention() + " (Id: " + authorId + ") sent message:**\n" +
                        quoteMessage(message.getContent());

                sendMessageToModmailChannel(msg);

                LOG.info("Handled private DM by user {}, sent to modmail channel.", author.getTag());
            } else if (channel.getId().equals(modMailChannel.getId())) {
                // Check for replies on original messages; send as replies via DM to original author
                if (message.getType() == Message.Type.DEFAULT && message.getReferencedMessage().isPresent()) {
                    LOG.info("Found mod-mail reply");

                    final Message refMsg = message.getReferencedMessage().get();

                    LOG.info("RefMsg: {}", refMsg.getContent());

                    Matcher userIdMatcher = Pattern
                            .compile("\\*\\*User [^ ]+ \\(Id: (\\d+)\\) sent message:.*", Pattern.DOTALL)
                            .matcher(refMsg.getContent());

                    if (!userIdMatcher.find()) {
                        sendMessageToModmailChannel("Error: Referenced message does not contain a proper User ID to " +
                                "respond to");
                        return;
                    }

                    String refUserId = userIdMatcher.group(1);

                    User refUser = discord.getUserById(Snowflake.of(refUserId)).block();
                    if (refUser == null) {
                        sendMessageToModmailChannel("Error: Could not send reply to user " + refUserId + ": " +
                                "Not found");
                        return;
                    }

                    PrivateChannel privateChannel = refUser.getPrivateChannel().block();
                    if (privateChannel == null) {
                        sendMessageToModmailChannel("Error: Could not send reply to user: Could not send DM");
                        return;
                    }

                    privateChannel
                            .createMessage("**Response by the moderators:**\n" + quoteMessage(message.getContent()))
                            .block();

                    sendMessageToModmailChannel("Reply sent to user via DM.");

                    LOG.info("Relayed modmail response to User via DM");
                }
            }
        });

        LOG.info("Mod mail set up successfully");
    }

    private void sendMessageToModmailChannel(String msg) {
        modMailChannel.createMessage(msg).block();
    }

    private static String quoteMessage(String message) {
        return message.replaceAll("(?m)^(.*)$", "> $1");
    }
}
