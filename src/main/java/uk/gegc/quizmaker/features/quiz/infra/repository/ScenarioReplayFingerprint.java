package uk.gegc.quizmaker.features.quiz.infra.repository;

import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

/** Stable binary encoding for the format-4 publication key. Domain records already canonicalize list order. */
final class ScenarioReplayFingerprint {
    private ScenarioReplayFingerprint() { }

    static byte[] of(QuizRevision revision) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new BufferedOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest)))) {
                write(revision, out);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Scenario publication fingerprint is unavailable", impossible);
        }
    }

    private static void write(QuizRevision revision, DataOutputStream out) throws IOException {
        // Version of this encoding, independent of revision and graph schema versions.
        out.writeInt(1);
        id(out, revision.id());
        id(out, revision.quizId());
        id(out, revision.ownerId());
        out.writeInt(revision.schemaVersion());
        text(out, revision.title());
        text(out, revision.description());
        instant(out, revision.createdAt());
        out.writeInt(revision.items().size());
        var questions = new HashSet<UUID>();
        for (var item : revision.items()) {
            id(out, item.id());
            out.writeInt(item.authoredPosition());
            var question = item.question();
            id(out, question.id());
            if (questions.add(question.id())) question(out, question);
        }

        var graph = revision.scenarioGraph();
        out.writeInt(graph.schemaVersion());
        id(out, graph.entryNodeId());
        out.writeInt(graph.nodes().size());
        for (var node : graph.nodes()) {
            id(out, node.id());
            out.writeInt(node.authoredPosition());
            text(out, node.type().name());
            id(out, node.itemId());
        }
        out.writeInt(graph.edges().size());
        for (var edge : graph.edges()) {
            id(out, edge.id());
            id(out, edge.fromNodeId());
            id(out, edge.toNodeId());
            out.writeInt(edge.authoredPosition());
            text(out, edge.type().name());
            out.writeInt(edge.conditionVersion() == null ? 0 : edge.conditionVersion());
            text(out, edge.condition());
        }
    }

    private static void question(DataOutputStream out, QuestionRevision question) throws IOException {
        id(out, question.questionId());
        out.writeInt(question.schemaVersion());
        text(out, question.type().name());
        text(out, question.difficulty().name());
        text(out, question.questionText());
        text(out, question.content());
        text(out, question.hint());
        text(out, question.explanation());
        text(out, question.attachmentUrl());
        id(out, question.attachmentAssetId());
        instant(out, question.createdAt());
        out.writeInt(question.options().size());
        for (var option : question.options()) {
            id(out, option.id());
            text(out, option.sourceKey());
            out.writeInt(option.authoredPosition());
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        if (value.codePoints().anyMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE))
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static void id(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeLong(value.getMostSignificantBits());
            out.writeLong(value.getLeastSignificantBits());
        }
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }
}
