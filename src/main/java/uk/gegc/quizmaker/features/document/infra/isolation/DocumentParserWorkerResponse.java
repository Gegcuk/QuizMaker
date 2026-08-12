package uk.gegc.quizmaker.features.document.infra.isolation;

import uk.gegc.quizmaker.features.document.application.ConvertedDocument;

record DocumentParserWorkerResponse(
        int protocolVersion,
        ConvertedDocument document,
        DocumentParserWorkerError error
) {

    static DocumentParserWorkerResponse success(ConvertedDocument document) {
        return new DocumentParserWorkerResponse(
                DocumentParserProtocolCodec.PROTOCOL_VERSION, document, null);
    }

    static DocumentParserWorkerResponse failure(DocumentParserWorkerError error) {
        return new DocumentParserWorkerResponse(
                DocumentParserProtocolCodec.PROTOCOL_VERSION, null, error);
    }
}
