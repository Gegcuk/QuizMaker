package uk.gegc.quizmaker.features.attempt.api;

/** Schema-validated examples of the enabled target envelope; IDs and content are synthetic. */
final class ParticipantReviewExamples {
    private ParticipantReviewExamples() { }
    private static final String PREFIX = """
            {"representation":"POLICY_DRIVEN","schemaVersion":1,"attemptId":"10000000-0000-0000-0000-000000000001",
             "quizId":"20000000-0000-0000-0000-000000000001","quizRevisionId":"30000000-0000-0000-0000-000000000001",
             "formId":"40000000-0000-0000-0000-000000000001","stateVersion":3,"policyVersion":1,
             "execution":"SUBMITTED","answerCollection":"COMPLETE","startedAt":"2026-11-06T12:00:00Z",
             "submittedAt":"2026-11-06T12:01:00Z","timing":"UNTIMED","contentVisibility":"VISIBLE",
             "items":[{"id":"50000000-0000-0000-0000-000000000001","position":0,"type":"TRUE_FALSE",
                       "questionText":"Water is a liquid at room temperature.","content":{"kind":"TRUE_FALSE"}}],
             "allowedActions":["VIEW_ATTEMPT"],
             "result":{"releaseVersion":1,"releasedAt":"2026-11-06T12:01:01Z",
                       "totalScore":{"earned":1,"possible":1,"basisPoints":10000},"reviewAvailable":true,
                       "items":[{"formItemId":"50000000-0000-0000-0000-000000000001","score":1,"correctness":true,
            """;
    static final String REVIEW = PREFIX + """
                       "responseState":"ACCEPTED","submittedAnswer":{"kind":"BOOLEAN","answer":true},
                       "correctAnswer":{"kind":"BOOLEAN","answer":true}}]}}
            """;
    static final String ANSWER_KEY = PREFIX + """
                       "correctAnswer":{"kind":"BOOLEAN","answer":true}}]}}
            """;
}
