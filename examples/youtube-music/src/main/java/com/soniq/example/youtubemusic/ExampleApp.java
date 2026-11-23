package com.soniq.example.youtubemusic;

import com.soniq.base.SoniqApp;
import com.soniq.base.provider.MusicProvider;

/** The example app: one line of wiring — which music source to use. */
public final class ExampleApp extends SoniqApp {

    @Override
    protected MusicProvider createProvider() {
        return new YouTubeMusicProvider(this);
    }
}
