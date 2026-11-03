package dev.amsam0.voicechatdiscord;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Lets this add-on be loaded/unloaded while Simple Voice Chat keeps running (PlugManX).
 * <p>
 * SVC collects add-on event handlers exactly once, in {@code PluginManager.init()} at its
 * own startup. Without this, a hot-loaded add-on is never initialized or sent events, and
 * an unloaded one keeps receiving events in its dead classloader. Both are patched here
 * by editing SVC's PluginManager through reflection.
 * <p>
 * Written against SVC 2.5.36 (bukkit), which is version-locked to the client modpack.
 * Re-check the field names if SVC is ever updated.
 */
final class VoicechatAddonHotSwap {
    /** Our handlers are lambdas/method refs defined in our own classes. */
    private static final String OWN_PACKAGE = "dev.amsam0.voicechatdiscord.";

    private final List<de.maxhenkel.voicechat.api.VoicechatPlugin> plugins;
    private final Object pluginManager;
    private final Field eventsField;
    private final ClassLoader svcLoader;

    @SuppressWarnings("unchecked")
    private VoicechatAddonHotSwap(BukkitVoicechatService service) throws ReflectiveOperationException {
        svcLoader = service.getClass().getClassLoader();
        // Same list instance PluginManager iterates (PluginManager.init() stores it as-is)
        plugins = (List<de.maxhenkel.voicechat.api.VoicechatPlugin>) service.getClass().getMethod("getPlugins").invoke(service);
        Class<?> pluginManagerClass = Class.forName("de.maxhenkel.voicechat.plugins.PluginManager", true, svcLoader);
        pluginManager = pluginManagerClass.getMethod("instance").invoke(null);
        eventsField = pluginManagerClass.getDeclaredField("events");
        eventsField.setAccessible(true);
    }

    /**
     * Call right after {@code registerPlugin}. On a normal boot SVC hasn't initialized its
     * add-ons yet (it does so on the first tick) and this does nothing.
     *
     * @return true if SVC was already running, i.e. this is a hot load: the plugin has been
     * initialized and its handlers are live, but no startup events will arrive.
     */
    static boolean attach(BukkitVoicechatService service, VoicechatPlugin plugin) throws ReflectiveOperationException {
        VoicechatAddonHotSwap svc = new VoicechatAddonHotSwap(service);
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> current = svc.events();
        if (current == null) return false;

        // A previous instance that didn't detach (a crashed disable, or a build from
        // before this class existed) is still registered: drop it and its handlers.
        svc.plugins.removeIf(p -> p != plugin && Constants.PLUGIN_ID.equals(p.getPluginId()));
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> events = withoutOwnHandlers(current);
        int stale = countHandlers(current) - countHandlers(events);
        if (stale > 0) {
            PaperPlugin.LOGGER.warn("Removed " + stale + " stale voicechat event handler(s) left by a previous instance");
        }

        Object serverApi = Class.forName("de.maxhenkel.voicechat.plugins.impl.VoicechatServerApiImpl", true, svc.svcLoader)
                .getMethod("instance").invoke(null);
        plugin.initialize((VoicechatApi) serverApi);

        // Priorities can't be honored against handlers SVC already sorted; ours never
        // cancel events, so appending them after everyone else's is fine.
        plugin.registerEvents(new EventRegistration() {
            @Override
            public <T extends Event> void registerEvent(Class<T> eventClass, Consumer<T> handler, int priority) {
                events.computeIfAbsent(eventClass, k -> new ArrayList<>()).add(handler);
            }
        });
        svc.setEvents(events);
        return true;
    }

    /** Stops SVC from calling into this (soon dead) plugin instance. */
    static void detach(BukkitVoicechatService service, VoicechatPlugin plugin) throws ReflectiveOperationException {
        VoicechatAddonHotSwap svc = new VoicechatAddonHotSwap(service);
        svc.plugins.remove(plugin);
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> current = svc.events();
        if (current != null) {
            svc.setEvents(withoutOwnHandlers(current));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Class<? extends Event>, List<Consumer<? extends Event>>> events() throws IllegalAccessException {
        return (Map<Class<? extends Event>, List<Consumer<? extends Event>>>) eventsField.get(pluginManager);
    }

    /**
     * Swaps in a whole new map: SVC dispatches from network threads without locking, so
     * the lists it may be iterating right now are never modified.
     */
    private void setEvents(Map<Class<? extends Event>, List<Consumer<? extends Event>>> events) throws IllegalAccessException {
        eventsField.set(pluginManager, events);
    }

    private static int countHandlers(Map<Class<? extends Event>, List<Consumer<? extends Event>>> events) {
        return events.values().stream().mapToInt(List::size).sum();
    }

    private static Map<Class<? extends Event>, List<Consumer<? extends Event>>> withoutOwnHandlers(
            Map<Class<? extends Event>, List<Consumer<? extends Event>>> events) {
        Map<Class<? extends Event>, List<Consumer<? extends Event>>> copy = new HashMap<>();
        events.forEach((eventClass, handlers) -> {
            List<Consumer<? extends Event>> kept = new ArrayList<>();
            for (Consumer<? extends Event> handler : handlers) {
                if (!handler.getClass().getName().startsWith(OWN_PACKAGE)) kept.add(handler);
            }
            copy.put(eventClass, kept);
        });
        return copy;
    }
}
