package base;

import arc.Core;

public enum Config {
    ApiKey("Api Key to access LU API. To get an API key go to discord.gg/v7SyYd2D3y and use the bot slash command.", "", "ApiKey"),
    ConnectionTimeout("How long, in millis, the server will wait for a http response before giving up.", 1000, "ConnectionTimeout"),
    HTTPThreadCount("How many threads used to send HTTP Get request to the api.", 4),
    CacheTTL("How long (in minutes) to keep cached responses from hash. Set to 0 to disable.", 60);

    public static final Config[] all = values();

    public final Object defaultValue;
    public final String key, description;
    final Runnable changed;

    Config(String description, Object def) {
        this(description, def, null, null);
    }

    Config(String description, Object def, String key) {
        this(description, def, key, null);
    }

    Config(String description, Object def, Runnable changed) {
        this(description, def, null, changed);
    }

    Config(String description, Object def, String key, Runnable changed) {
        this.description = description;
        this.key = "lu_" + (key == null ? name() : key);
        this.defaultValue = def;
        this.changed = changed == null ? () -> {
        } : changed;
    }

    public boolean isNum() {
        return defaultValue instanceof Integer;
    }

    public boolean isBool() {
        return defaultValue instanceof Boolean;
    }

    public boolean isString() {
        return defaultValue instanceof String;
    }

    public Object get() {
        return Core.settings.get(key, defaultValue);
    }

    public boolean bool() {
        return Core.settings.getBool(key, (Boolean) defaultValue);
    }

    public int num() {
        return Core.settings.getInt(key, (Integer) defaultValue);
    }

    public String string() {
        return Core.settings.getString(key, (String) defaultValue);
    }

    public void set(Object value) {
        Core.settings.put(key, value);
        changed.run();
    }
}