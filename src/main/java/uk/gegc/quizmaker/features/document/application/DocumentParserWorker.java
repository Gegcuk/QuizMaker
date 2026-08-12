package uk.gegc.quizmaker.features.document.application;

import java.time.Duration;

/** One killable parser process owned by the document application boundary. */
public interface DocumentParserWorker extends AutoCloseable {

    boolean await(Duration timeout) throws InterruptedException;

    void requestTermination();

    void forceTermination();

    boolean isAlive();

    ConvertedDocument readResult();

    @Override
    void close();
}
