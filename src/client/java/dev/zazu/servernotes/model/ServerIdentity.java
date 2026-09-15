package dev.zazu.servernotes.model;

public record ServerIdentity(String key, String host, int port, String displayAddress) {
}
