package net.aehx.twitch.ml7bot.nightbot;

import org.json.JSONArray;
import org.json.JSONObject;

public class MockNightbotAPI extends NightbotAPI {

    private JSONObject channelCommandsResponse;
    private JSONObject channelByNameResponse;

    public MockNightbotAPI() {
        channelCommandsResponse = new JSONObject();
        channelCommandsResponse.put("commands", new JSONArray());

        channelByNameResponse = new JSONObject();
        JSONObject channelObj = new JSONObject();
        channelObj.put("_id", "1");
        channelByNameResponse.put("channel", channelObj);
    }


    @Override
    protected JSONObject fetchChannelCommandsJson(String channelId) throws Exception {
        return channelCommandsResponse;
    }

    public void setChannelCommandsResponse(JSONObject channelCommandsResponse) {
        this.channelCommandsResponse = channelCommandsResponse;
    }


    @Override
    protected JSONObject fetchChannelByNameJson(String name) throws Exception {
        return channelByNameResponse;
    }

    public void setChannelByNameResponse(JSONObject channelByNameResponse) {
        this.channelByNameResponse = channelByNameResponse;
    }
}
