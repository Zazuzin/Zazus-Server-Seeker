package dev.zazuzin.zst;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Lightweight direct Minecraft Java status probe used for Finder latency. */
final class MinecraftStatusProbe {
    private static final int TIMEOUT_MS = 2_800;

    private MinecraftStatusProbe() {}

    static long measureLatencyMillis(String host, int port) {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0); // handshake packet id
            writeVarInt(handshake, 0); // protocol is not required for status probing
            writeString(handshake, host);
            handshake.write((port >>> 8) & 0xff);
            handshake.write(port & 0xff);
            writeVarInt(handshake, 1); // status state

            byte[] payload = handshake.toByteArray();
            writeVarInt(out, payload.length);
            out.write(payload);
            writeVarInt(out, 1);
            writeVarInt(out, 0); // status request
            out.flush();

            readVarInt(in); // response packet length
            if (readVarInt(in) != 0) return -1L;
            int jsonLength = readVarInt(in);
            if (jsonLength <= 0 || jsonLength > 1_000_000) return -1L;
            byte[] json = in.readNBytes(jsonLength);
            if (json.length != jsonLength) return -1L;

            long elapsed = Math.round((System.nanoTime() - started) / 1_000_000.0);
            return Math.max(1L, elapsed);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7f) != 0) {
            out.write((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = in.read();
            if (current < 0) throw new EOFException("Connection closed while reading VarInt");
            value |= (current & 0x7f) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
            if (position >= 35) throw new IOException("VarInt is too large");
        }
    }
}
