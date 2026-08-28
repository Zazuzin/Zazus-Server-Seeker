package dev.zazuzin.zst;

import net.fabricmc.api.ClientModInitializer;

import java.lang.reflect.*;
import java.util.Collection;

/** Standalone Meteor-style title credit; no Meteor dependency. */
public final class TitleCreditEntrypoint implements ClientModInitializer {
    private static final String NAME = "Zazu's Server Seeker";
    private static final String BY = " by ";
    private static final String AUTHOR = "Zazuzin";
    private static final int NAME_COLOR = 0xFFFF5555;
    private static final int BY_COLOR = 0xFFAAAAAA;
    private static final int AUTHOR_COLOR = 0xFF5555FF;

    @Override
    public void onInitializeClient() {
        try {
            Reflection.registerStaticEvent(
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents",
                    "AFTER_INIT",
                    "net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit",
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
                        if (args != null && args.length >= 2 && isTitleScreen(args[1])) registerAfterExtract(args[1]);
                        return null;
                    });
            System.out.println("[Zazu's Server Seeker] Meteor-style title credit registered.");
        } catch (Throwable t) {
            System.err.println("[Zazu's Server Seeker] Could not register title credit:");
            Reflection.unwrap(t).printStackTrace();
        }
    }

    private static void registerAfterExtract(Object screen) throws Exception {
        Class<?> events = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
        Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterExtract");
        Method factory = null;
        for (Method method : events.getMethods()) {
            if (!method.getName().equals("afterExtract") || !Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
            if (method.getParameterTypes()[0].isInstance(screen)) { factory = method; break; }
        }
        if (factory == null) throw new NoSuchMethodException("ScreenEvents.afterExtract(Screen)");
        Object event = factory.invoke(null, screen);
        Object listener = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return Reflection.objectMethod(proxy, method, args);
            if (args != null && args.length >= 2) render(args[0], args[1]);
            return null;
        });
        RuntimeAccess.registerEvent(event, listener);
    }

    private static void render(Object screen, Object extractor) {
        if (!isTitleScreen(screen)) return;
        Object font = RuntimeAccess.font(screen);
        if (font == null) return;
        int lineHeight = RuntimeAccess.intField(font, "lineHeight", 9);
        int screenWidth = RuntimeAccess.intField(screen, "width", 854);
        int y = 3 + meteorCreditCount() * (lineHeight + 2);
        int nameWidth = RuntimeAccess.width(font, NAME);
        int byWidth = RuntimeAccess.width(font, BY);
        int authorWidth = RuntimeAccess.width(font, AUTHOR);
        int x = Math.max(0, screenWidth - 3 - nameWidth - byWidth - authorWidth);
        RuntimeAccess.drawText(extractor, font, NAME, x, y, NAME_COLOR);
        x += nameWidth;
        RuntimeAccess.drawText(extractor, font, BY, x, y, BY_COLOR);
        x += byWidth;
        RuntimeAccess.drawText(extractor, font, AUTHOR, x, y, AUTHOR_COLOR);
    }

    private static int meteorCreditCount() {
        try {
            Class<?> manager = Class.forName("meteordevelopment.meteorclient.addons.AddonManager");
            Object addons = manager.getField("ADDONS").get(null);
            return addons instanceof Collection<?> c ? c.size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean isTitleScreen(Object screen) {
        return screen != null && (screen.getClass().getSimpleName().equals("TitleScreen") || screen.getClass().getName().endsWith(".TitleScreen"));
    }
}
