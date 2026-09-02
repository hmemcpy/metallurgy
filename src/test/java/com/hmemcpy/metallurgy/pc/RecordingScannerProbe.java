package com.hmemcpy.metallurgy.pc;

import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.parsing.Scanners.Scanner;
import dotty.tools.dotc.reporting.Profile;
import dotty.tools.dotc.util.SourceFile;

import java.util.List;

/**
 * Captures the token stream of the exact parse that consumes this scanner. Defined into
 * the discovered compiler's isolated classloader by the separator-provenance probe. The
 * superclass constructor consumes the first token before instance fields initialize, so
 * the recording sink is also published through a thread-local for that phase.
 */
public final class RecordingScannerProbe extends Scanner {

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

    public RecordingScannerProbe(
            SourceFile source,
            int startFrom,
            Profile profile,
            boolean allowIndent,
            Context context,
            List<Token> sink) {
        super(source, startFrom, profile, allowIndent, context);
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
