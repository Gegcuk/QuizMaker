package uk.gegc.quizmaker.features.document.infra.isolation;

import java.io.IOException;

@FunctionalInterface
interface DocumentParserProcessStarter {

    Process start(ProcessBuilder processBuilder) throws IOException;
}
