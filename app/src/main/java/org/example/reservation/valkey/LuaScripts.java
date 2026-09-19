package org.example.reservation.valkey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import org.example.reservation.ReservationException;

final class LuaScripts {

    static final String TRY_RESERVE = "try_reserve";
    static final String RELEASE = "release";
    static final String EXTEND = "extend";
    static final String FIND = "find";
    static final String LIST = "list";
    static final String USAGE = "usage";
    static final String REPLACE_QUOTAS = "replace_quotas";
    static final String DELETE_QUOTAS = "delete_quotas";
    static final String QUOTAS = "quotas";
    static final String MIGRATE_SCOPE = "migrate_scope";

    private static final String[] ALL = {
        TRY_RESERVE, RELEASE, EXTEND, FIND, LIST, USAGE, REPLACE_QUOTAS, DELETE_QUOTAS, QUOTAS,
        MIGRATE_SCOPE
    };

    private final RedisCommands<String, String> commands;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, String> shas = new ConcurrentHashMap<>();

    LuaScripts(RedisCommands<String, String> commands) {
        this.commands = Objects.requireNonNull(commands);
        for (String name : ALL) {
            sources.put(name, read(name));
        }
        loadAll();
    }

    String eval(String name, String[] keys, String... argv) {
        try {
            return evalsha(name, keys, argv);
        } catch (RedisCommandExecutionException e) {
            if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
                load(name);
                return evalsha(name, keys, argv);
            }
            throw e;
        }
    }

    private String evalsha(String name, String[] keys, String... argv) {
        String sha = shas.get(name);
        Object result = commands.evalsha(sha, ScriptOutputType.VALUE, keys, argv);
        return result == null ? "" : result.toString();
    }

    private void loadAll() {
        for (String name : ALL) {
            load(name);
        }
    }

    private void load(String name) {
        shas.put(name, commands.scriptLoad(sources.get(name)));
    }

    private static String read(String name) {
        String path = "/reservation/lua/" + name + ".lua";
        try (InputStream in = LuaScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new ReservationException("missing Lua script " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
