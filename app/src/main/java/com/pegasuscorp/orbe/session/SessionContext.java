package com.pegasuscorp.orbe.session;

/** Contexte de canal pour factory / streaming (PegaseSession étape 2). */
public final class SessionContext {

    public final Channel channel;
    public final boolean streamingEnabled;

    public SessionContext(Channel channel, boolean streamingEnabled) {
        this.channel = channel != null ? channel : Channel.TEXT;
        this.streamingEnabled = streamingEnabled;
    }
}
