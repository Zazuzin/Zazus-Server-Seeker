package dev.zazu.servernotes.util;

import dev.zazu.servernotes.model.ServerIdentity;

import java.net.IDN;
import java.util.Locale;

public final class ServerAddressNormalizer {
    public static final int DEFAULT_PORT = 25565;

    private ServerAddressNormalizer() {
    }

    public static ServerIdentity normalize(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            throw new IllegalArgumentException("Server address is empty");
        }

        String raw = rawAddress.trim();
        String host;
        int port = DEFAULT_PORT;

        if (raw.startsWith("[")) {
            int close = raw.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("Invalid bracketed IPv6 address");
            }
            host = raw.substring(1, close);
            if (close + 1 < raw.length()) {
                if (raw.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("Invalid address suffix");
                }
                port = parsePort(raw.substring(close + 2));
            }
        } else {
            int firstColon = raw.indexOf(':');
            int lastColon = raw.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) {
                host = raw.substring(0, firstColon);
                port = parsePort(raw.substring(firstColon + 1));
            } else {
                host = raw;
            }
        }

        host = normalizeHost(host);
        boolean ipv6 = host.indexOf(':') >= 0;
        String keyHost = ipv6 ? "[" + host + "]" : host;
        String key = keyHost + ":" + port;
        return new ServerIdentity(key, host, port, rawAddress.trim());
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Server host is empty");
        }
        if (normalized.indexOf(':') < 0) {
            normalized = IDN.toASCII(normalized);
        }
        return normalized;
    }

    private static int parsePort(String rawPort) {
        if (rawPort == null || rawPort.isBlank()) {
            throw new IllegalArgumentException("Server port is empty");
        }
        try {
            int port = Integer.parseInt(rawPort);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Server port is outside 1-65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid server port", exception);
        }
    }
}
