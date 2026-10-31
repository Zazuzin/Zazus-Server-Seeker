package dev.zazuzin.zst;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/** Optional BreakBlocks filters, kept separate from authentication and player search. */
record ServerSearchFilters(String country, String region, String motd, String modpack) {
    static final ServerSearchFilters EMPTY = new ServerSearchFilters("", "", "", "");
    static final int MAX_LENGTH = 128;

    ServerSearchFilters {
        country = clean(country);
        if (country.equalsIgnoreCase("UK")) country = "GB";
        if (country.matches("[a-zA-Z]{2}")) country = country.toUpperCase(Locale.ROOT);
        region = clean(region);
        motd = clean(motd);
        modpack = clean(modpack);
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() > MAX_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Each filter must be at most 128 characters without line breaks.");
        }
        return text;
    }

    boolean active() { return !(country.isEmpty() && region.isEmpty() && motd.isEmpty() && modpack.isEmpty()); }

    String appendTo(String uri) {
        StringBuilder result = new StringBuilder(uri);
        append(result, "country", country);
        append(result, "region", region);
        append(result, "motd", motd.isEmpty() || motd.contains("*") ? motd : "*" + motd + "*");
        append(result, "modpack", modpack);
        return result.toString();
    }

    private static void append(StringBuilder target, String name, String value) {
        if (!value.isEmpty()) target.append('&').append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    static ServerSearchFilters read(Properties properties) {
        try {
            return new ServerSearchFilters(properties.getProperty("filterCountry", ""),
                    properties.getProperty("filterRegion", ""), properties.getProperty("filterMotd", ""),
                    properties.getProperty("filterModpack", ""));
        } catch (IllegalArgumentException invalid) { return EMPTY; }
    }

    void write(Properties properties) {
        properties.setProperty("filterCountry", country);
        properties.setProperty("filterRegion", region);
        properties.setProperty("filterMotd", motd);
        properties.setProperty("filterModpack", modpack);
    }
}
