package com.company.requests.application;

/** Test-only creation of a valid authenticated token from the preceding generation. */
public final class B21CursorProbe {
    private B21CursorProbe() { }

    public static String precedingGeneration(BrowseCursorCodec codec, AuthenticatedActor actor,
                                            RequestBrowseQuery.Selection selection, String token) {
        var current = codec.open(token, actor, selection);
        return codec.seal(actor, selection, new BrowseCursorCodec.Position(
                current.generation() - 1, current.below(), current.window()));
    }
}
