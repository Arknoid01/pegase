package com.pegasuscorp.orbe.mcp;

/** Erreur de protocole ou de transport MCP, message lisible par l'utilisateur / le modèle. */
public final class McpException extends Exception {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
