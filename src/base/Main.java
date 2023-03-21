package base;

import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.world.blocks.logic.LogicBlock;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public class Main extends Plugin {
    private static final ThreadPoolExecutor executorService = new ThreadPoolExecutor(0, Config.HTTPThreadCount.num(),
            5000L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>());

    private static long Wait404 = System.currentTimeMillis();
    private static long Wait429 = System.currentTimeMillis();

    private record CacheData(JSONObject data, long expiration) {
        boolean expired(long now) {
            return expiration < now;
        }
    }
    private static final ObjectMap<String, CacheData> cache = new ObjectMap<>();

    private static final HashMap<Player, AtomicLong> updateWarningRL = new HashMap<>();

    @Override
    public void init() {
        if (Config.ApiKey.string().isEmpty()) {
            Log.warn("\n\n\nLU: &rAPI key has not been configured. &frPlease go to discord.gg/rtC4mmdWZa and go to the bot channel to get an api key. Configure the api key using the \"luconfig ApiKey insertKeyHere\" command\n\n");
            return;
        }
        HttpGet temp1 = new HttpGet(Vars.ghApi + "/repos/L0615T1C5-216AC-9437/BannedMindustryImage/releases/latest");
        HttpGet temp2 = new HttpGet("http://c-n.ddns.net:8888/lu/v1/ping");
        temp2.addHeader("X-api-key", Config.ApiKey.string());
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            try (CloseableHttpResponse response = client.execute(temp1)) {
                int code = response.getStatusLine().getStatusCode();
                if (code == 200) {
                    String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    final JSONObject data = new JSONObject(responseBody);
                    if (data.getFloat("tag_name") > Float.parseFloat(Vars.mods.list().find(b -> b.meta.name.equals("LU")).meta.version)) {
                        Log.warn("\n\n\nLU: &yNewer version of this plugin found! Please update the mod to maintain access to the LU API!&fr\n\n");
                    } else {
                        Log.info("LU: &gThis plugin is up to date!&fr");
                    }
                } else {
                    Log.err("LU: &rUnable to check for updates&fr");
                }
            }
            try (CloseableHttpResponse response = client.execute(temp2)) {
                int code = response.getStatusLine().getStatusCode();
                switch (code) {
                    case 200 -> Log.info("LU: &gApi connection is OK&fr");
                    case 400 -> {
                        Log.err("LU: &rInvalid Api Key! \"&fr@&r\" is not a valid api key! &lbGo to discord.gg/rtC4mmdWZa to get a new api key.&fr", Config.ApiKey.string());
                        return;
                    }
                    case 404 -> Log.warn("LU: &LU Api is offline!&fr");
                }
            }
        } catch (IOException e) {
            Log.err(e);
        }

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            final long now = System.currentTimeMillis();
            if (removeIf(cache, e -> e.value.expired(now)))
                Log.debug("LU: Removed entries from cache");
        }, 1, 1, TimeUnit.MINUTES);

        Events.on(EventType.PlayerLeave.class, event -> updateWarningRL.remove(event.player));

        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if (event.breaking || event.unit == null || event.unit.getPlayer() == null || Wait404 > System.currentTimeMillis() || Wait429 > System.currentTimeMillis() || !(event.tile.build instanceof LogicBlock.LogicBuild lb))
                return;
            if (lb.code.contains("ucontrol itemDrop")) {
                final String code = lb.code;
                queueUpdate(lb, code, event.unit.getPlayer());
            }
        });
        Events.on(EventType.ConfigEvent.class, event -> {
            if (event.player == null || event.tile == null) return;
            if (event.tile instanceof final LogicBlock.LogicBuild lb) {
                if (event.value instanceof byte[]) {//code update
                    final var code = lb.code;
                    if (code.contains("ucontrol itemDrop"))
                        queueUpdate(lb, code, event.player);
                }
            }
        });
    }

    public static void queueUpdate(final LogicBlock.LogicBuild lb, final String code, final Player p) {
        final var hash = getLogicCodeHash(code);
        if (hash == null) return;
        var c = cache.get(hash);
        if (c != null) {
            if (c.data == null) return;//dummy to not queue api again
            Log.debug("LU:&g Found in cache&fr");
            update(lb, hash, code, c.data, p);
            return;
        }
        executorService.execute(() -> {
            var config = RequestConfig.custom().setConnectTimeout(Config.ConnectionTimeout.num()).setSocketTimeout(Config.ConnectionTimeout.num()).build();
            var hcb = HttpClientBuilder.create();
            hcb.setDefaultRequestConfig(config);
            var a = new URIBuilder(URI.create("http://c-n.ddns.net:8888/lu/v1/check"));
            a.addParameter("hash", hash);
            try (CloseableHttpClient client = hcb.build()) {
                HttpGet get = new HttpGet(a.build());
                get.addHeader("X-api-key", Config.ApiKey.string());
                try (CloseableHttpResponse response = client.execute(get)) {
                    int rc = response.getStatusLine().getStatusCode();
                    switch (rc) {
                        case 200 -> {
                            try {
                                Log.debug("found hash on LU db");
                                //parse json
                                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                                final JSONObject data = new JSONObject(responseBody);
                                //cache
                                cache.put(hash, new CacheData(data, System.currentTimeMillis() + Config.CacheTTL.num() * 60000L));
                                //do
                                update(lb, hash, code, data, p);
                            } catch (Exception e) {
                                e.printStackTrace();
                                Log.err("Code that made LU crash: \n" + b64encoder.encodeToString(LogicBlock.compress(code, new Seq<>())));
                            }
                        }
                        case 204 -> {
                            Log.debug("No update");
                            cache.put(hash, new CacheData(null, System.currentTimeMillis() + Config.CacheTTL.num() * 60000L));
                        }
                        case 404 -> {
                            Log.err("LU: &rFailed to connect to LU api!&fr");
                            Wait404 = System.currentTimeMillis() + 300000L;
                        }
                        case 429 -> {
                            Log.warn("LU: &yRateLimit hit!&fr");
                            Wait429 = System.currentTimeMillis() + 5000;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void update(final LogicBlock.LogicBuild lb, String hash, String code, final JSONObject data, final Player p) {
        Log.debug("Updating " + hash);
        //get old token table
        Log.debug("Get old token table");
        HashMap<String, Integer> oldTokenMap = new HashMap<>();
        var ja = data.getJSONArray("oldTokens");
        for (int i = 0; i < ja.length(); i++) {
            var jo = ja.getJSONObject(i);
            String name = jo.getString("name");
            int row = jo.getInt("row");
            oldTokenMap.put(name, row);
            Log.debug("old token " + name + " at row " + row);
        }
        //get tokens from old code
        Log.debug("Getting token values from old code");
        HashMap<String, String> oldTokenValues = new HashMap<>();
        String[] oldCodeLBL = code.split("\n");
        for (var e : oldTokenMap.entrySet()) {
            var t = oldCodeLBL[e.getValue()].split(" ");
            if (t[0].equals("set")) {
                oldTokenValues.put(e.getKey(), t[2]);
                Log.debug("found old token " + e.getKey() + " = " + t[2]);
            } else if (t[0].equals("ubind")) {
                oldTokenValues.put(e.getKey(), t[1]);
                Log.debug("found old token " + e.getKey() + " = " + t[1]);
            }
        }
        //get token conversion
        Log.debug("Getting token conversion table");
        StringMap updateTokenMap = new StringMap();
        ja = data.getJSONArray("tokenConversion");
        for (int i = 0; i < ja.length(); i++) {
            var jo = ja.getJSONObject(i);
            String old = jo.getString("old");
            String New = jo.getString("new");
            updateTokenMap.put(old, New);
            Log.debug(old + " -> " + New);
        }
        //find new token values
        Log.debug("Get new token values from old code");
        HashMap<String, String> newTokenValues = new HashMap<>();
        for (var e : updateTokenMap.entries()) {
            var tokenValue = oldTokenValues.get(e.key);
            if (tokenValue != null) {
                newTokenValues.put(e.value, tokenValue);
                Log.debug("found new token " + e.value + " = " + tokenValue);
            }
        }
        //get new token table
        Log.debug("Get new token table");
        HashMap<String, Integer> newTokenMap = new HashMap<>();
        ja = data.getJSONArray("newTokens");
        for (int i = 0; i < ja.length(); i++) {
            var jo = ja.getJSONObject(i);
            String name = jo.getString("name");
            int row = jo.getInt("row");
            newTokenMap.put(name, row);
            Log.debug("new token " + name + " at row " + row);
        }
        //replace tokens in new code
        Log.debug("Updating new code");
        String[] newCode = data.getString("newCode").split("\n");
        Log.debug("new code lines " + newCode.length);
        for (var e : newTokenValues.entrySet()) {
            int row = newTokenMap.get(e.getKey());
            String s = newCode[row].split(" ", 2)[0];
            if (s.startsWith("set")) {
                newCode[row] = "set " + e.getKey() + " " + e.getValue();
                Log.debug("Set row " + row + " to \"set " + e.getKey() + " " + e.getValue() + "\"");
            } else {
                newCode[row] = s + " " + e.getValue();
            }
        }
        final String finalCode = String.join("\n", newCode);
        //Log.debug("\n\nFinal Code:\n" + finalCode + "\n\n");//don't uncomment this unless something really went wrong
        Core.app.post(() -> {
            lb.updateCode(finalCode);
            Call.tileConfig(null, lb, lb.config());
            Log.debug("Updated " + lb.pos() + " with new code!");
            var al = updateWarningRL.computeIfAbsent(p, ignored -> new AtomicLong(0));
            long now = System.currentTimeMillis();
            if (al.get() < now) {
                al.set(now + 5000);
                p.sendMessage("[lightgray]" + Iconc.warning + " Your logic code was updated to a newer version!");
            }
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("luconfig", "[name] [value...]", "Configure server settings.", arg -> {
            if (arg.length == 0) {
                Log.info("All config values:");
                for (Config c : Config.all) {
                    Log.info("&lk| @: @", c.name(), "&lc&fi" + c.get());
                    Log.info("&lk| | &lw" + c.description);
                    Log.info("&lk|");
                }
                Log.info("use the command with the value set to \"default\" in order to use the default value.");
                return;
            }

            try {
                Config c = Config.valueOf(arg[0]);
                if (arg.length == 1) {
                    Log.info("'@' is currently @.", c.name(), c.get());
                } else {
                    if (arg[1].equals("default")) {
                        c.set(c.defaultValue);
                    } else if (c.isBool()) {
                        c.set(arg[1].equals("on") || arg[1].equals("true"));
                    } else if (c.isNum()) {
                        try {
                            c.set(Integer.parseInt(arg[1]));
                        } catch (NumberFormatException e) {
                            Log.err("Not a valid number: @", arg[1]);
                            return;
                        }
                    } else if (c.isString()) {
                        c.set(arg[1].replace("\\n", "\n"));
                    }

                    Log.info("@ set to @.", c.name(), c.get());
                    Core.settings.forceSave();
                }
            } catch (IllegalArgumentException e) {
                Log.err("Unknown config: '@'. Run the command with no arguments to get a list of valid configs.", arg[0]);
            }
        });
        handler.register("luclearcache", "Clear LU Cache", arg -> {
            Log.info(cache.size + " items cleared from LU cache.");
            cache.clear();
        });
    }

    private static final Base64.Encoder b64encoder = Base64.getEncoder();
    private static MessageDigest digest;

    static {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    public static byte[] sha256(String string) {
        try {
            return digest.digest(string.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    public static String getLogicCodeHash(String code) {
        StringBuilder builder = new StringBuilder();
        for (String line : code.split("\n")) {
            String[] a = line.split(" ", 3);
            builder.append(a[0]);
            if (a[0].equals("set"))
                builder.append(a[1]);
        }
        return b64encoder.encodeToString(sha256(builder.toString()));
    }

    public static <E> boolean removeIf(Iterable<E> map, Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        boolean removed = false;
        final Iterator<E> each = map.iterator();
        while (each.hasNext()) {
            if (filter.test(each.next())) {
                each.remove();
                removed = true;
            }
        }
        return removed;
    }
}
