package com.hmemcpy.metallurgy.pc;

import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.parsing.Scanners.Scanner;
import dotty.tools.dotc.reporting.Profile;
import dotty.tools.dotc.util.SourceFile;

import java.util.List;

/**
 * Captures the token stream of the exact parse that consumes this scanner. The class is
 * compiled against the pinned compiler and packaged as a bridge resource; the bridge
 * defines its bytes into the discovered compiler's isolated classloader so the host
 * loader never initializes it and no compiler type escapes. The superclass constructor
 * consumes the first token before instance fields initialize, so the recording sink is
 * also published through a thread-local for that phase.
 */
public final class SeparatorRecordingScanner extends Scanner {

    public static final class Token {
        public final int token;
        public final int offset;
        public final int lastOffset;

        public Token(int token, int offset, int lastOffset) {
            this.token = token;
            this.offset = offset;
            this.lastOffset = lastOffset;
        }
    }

    public static final ThreadLocal<List<Token>> constructionSink = new ThreadLocal<>();

    private final List<Token> sink;

    public SeparatorRecordingScanner(SourceFile source, Context context, List<Token> sink) {
        super(source, 0, Profile.current(context), true, context);
        this.sink = sink;
        if (constructionSink.get() == null) {
            sink.add(new Token(token(), offset(), lastOffset()));
        }
    }

    @Override
    public void nextToken() {
        super.nextToken();
        List<Token> active = this.sink != null ? this.sink : constructionSink.get();
        if (active != null) {
            active.add(new Token(token(), offset(), lastOffset()));
        }
    }
}
