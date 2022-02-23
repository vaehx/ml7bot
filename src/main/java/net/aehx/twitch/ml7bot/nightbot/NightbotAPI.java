package net.aehx.twitch.ml7bot.nightbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

public class NightbotAPI {

    private static final String NIGHTBOT_API_URL = "https://api.nightbot.tv/1";


    /**
     * @return Map of name -> {@link NightbotCommand}
     */
    public static Map<String, NightbotCommand> fetchChannelCommands(String channelId) throws Exception {
        JSONObject response;
        try {
            URL url = new URL(NIGHTBOT_API_URL + "/commands");
            String str = getJSONHttp(url, new HashMap<String, String>() {{
                put("nightbot-channel", channelId);
            }});

            response = new JSONObject(str);
        } catch (Exception e) {
            throw new Exception("Failed fetch nightbot api for channel commands", e);
        }

        Map<String, NightbotCommand> commands = new HashMap<>();
        JSONArray commandsArr = response.getJSONArray("commands");
        for (int i = 0; i < commandsArr.length(); ++i) {
            JSONObject commandObj = commandsArr.getJSONObject(i);
            NightbotCommand command = new NightbotCommand();
            command.id = commandObj.getString("_id");
            command.createdAt = isoToMillisEpoch(commandObj.getString("createdAt"));
            command.updatedAt = isoToMillisEpoch(commandObj.getString("updatedAt"));
            command.name = commandObj.getString("name");
            command.alias = commandObj.optString("alias");
            command.message = commandObj.optString("message");
            command.userLevel = commandObj.getString("userLevel");
            command.count = commandObj.getInt("count");
            command.coolDown = commandObj.getInt("coolDown");
            commands.put(command.name, command);
        }

        return commands;
    }

    public static NightbotChannel fetchChannelByName(String name) throws Exception {
        JSONObject response;
        try {
            URL url = new URL(NIGHTBOT_API_URL + "/channels/t/" + name);
            String str = getJSONHttp(url);
            response = new JSONObject(str);
        } catch (Exception e) {
            throw new Exception("Failed fetch channel by name from Nighbot api", e);
        }

        JSONObject channelObj = response.getJSONObject("channel");

        NightbotChannel channel = new NightbotChannel();
        channel.id = channelObj.getString("_id");

        return channel;
    }

    private static String getJSONHttp(URL url, Map<String, String> additionalHeaders) throws Exception {
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(10 * 1000);
        con.setReadTimeout(10 * 1000);
        con.setRequestMethod("GET");
        con.setRequestProperty("Content-Type", "application/json");

        if (additionalHeaders != null)
            additionalHeaders.forEach((key, value) -> con.setRequestProperty(key, value));

        int status = con.getResponseCode();
        if (status == 404) {
            throw new Exception("Not found (404)");
        } else if (status != 200) {
            throw new Exception("Got HTTP error for request to URL '" + url.toString() + "': " +
                    "Code " + con.getResponseCode() + ", Message: " + con.getResponseMessage());
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = in.readLine()) != null)
            response.append(line);

        in.close();
        con.disconnect();

        return response.toString();
    }

    private static String getJSONHttp(URL url) throws Exception {
        return getJSONHttp(url, null);
    }

    private static long isoToMillisEpoch(String iso) {
        return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso)).toEpochMilli();
    }
}
