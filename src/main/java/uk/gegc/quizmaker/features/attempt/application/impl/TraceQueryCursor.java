package uk.gegc.quizmaker.features.attempt.application.impl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.Context;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException.Reason.*;

/** Bounded metadata only. This digest binds a position to its scope; it authenticates no caller. */
final class TraceQueryCursor {
    private static final int VERSION = 1;
    private static final int BYTES = 49;
    private static final int TEXT_LENGTH = 66;
    private TraceQueryCursor() { }

    record Position(long after, long through) { }

    static String encode(Context context, Position position) {
        var bytes = ByteBuffer.allocate(BYTES).put((byte) VERSION).put(binding(context))
                .putLong(position.after()).putLong(position.through()).array();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Position decode(String cursor, Context context) {
        require(cursor != null && cursor.length() == TEXT_LENGTH, INVALID_CURSOR);
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            require(bytes.length == BYTES && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor), INVALID_CURSOR);
            var buffer = ByteBuffer.wrap(bytes);
            require(buffer.get() == VERSION, INVALID_CURSOR);
            var scope = new byte[32]; buffer.get(scope);
            require(MessageDigest.isEqual(scope, binding(context)), INVALID_CURSOR);
            var position = new Position(buffer.getLong(), buffer.getLong());
            require(position.after() > 0 && position.through() > position.after(), INVALID_CURSOR);
            return position;
        } catch (IllegalArgumentException failure) { throw new uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQueryException(INVALID_CURSOR); }
    }

    private static byte[] binding(Context context) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var ids = ByteBuffer.allocate(48);
            put(ids, context.scope().attemptId()); put(ids, context.scope().policySnapshotId()); put(ids, context.scope().formId());
            digest.update(ids.array());
            digest.update(context.purpose().name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            return digest.digest(context.operation().name().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("Required SHA-256 unavailable"); }
    }

    private static void put(ByteBuffer buffer, UUID id) { buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
}
