package uk.gegc.quizmaker.features.ai.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Generated question semantic identity")
class GeneratedQuestionSemanticIdentityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticIdentityFixtures")
    @DisplayName("Recognizes equivalent shuffled content and distinguishes changed assessment semantics")
    void recognizesEquivalentContentAndDistinguishesSemanticChanges(
            String scenario,
            QuestionType type,
            String firstContent,
            String equivalentContent,
            String changedContent
    ) {
        GeneratedQuestionSemanticIdentity.Identity first = identity(question(
                type,
                "  Select   the CORRECT answer  ",
                firstContent
        ));
        GeneratedQuestionSemanticIdentity.Identity equivalent = identity(question(
                type,
                "select the correct answer",
                equivalentContent
        ));
        GeneratedQuestionSemanticIdentity.Identity changed = identity(question(
                type,
                "select the correct answer",
                changedContent
        ));

        assertThat(equivalent).isEqualTo(first);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    @DisplayName("Different question stems remain distinct without fuzzy matching")
    void differentQuestionStemsRemainDistinct() {
        String content = "{\"answer\":true}";

        GeneratedQuestionSemanticIdentity.Identity first = identity(question(
                QuestionType.TRUE_FALSE,
                "Java is statically typed",
                content
        ));
        GeneratedQuestionSemanticIdentity.Identity second = identity(question(
                QuestionType.TRUE_FALSE,
                "JavaScript is statically typed",
                content
        ));

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("Different question types remain distinct even with the same stem and options")
    void differentQuestionTypesRemainDistinct() {
        String content = """
                {"options":[
                  {"id":"a","text":"Paris","correct":true},
                  {"id":"b","text":"London","correct":false}
                ]}
                """;

        GeneratedQuestionSemanticIdentity.Identity singleChoice = identity(question(
                QuestionType.MCQ_SINGLE,
                "Select the capital of France",
                content
        ));
        GeneratedQuestionSemanticIdentity.Identity multipleChoice = identity(question(
                QuestionType.MCQ_MULTI,
                "Select the capital of France",
                content
        ));

        assertThat(multipleChoice).isNotEqualTo(singleChoice);
    }

    @Test
    @DisplayName("Null, blank, and malformed questions do not receive an identity")
    void invalidQuestionsDoNotReceiveIdentity() {
        Question blankStem = question(QuestionType.OPEN, "   ", "{\"answer\":\"Paris\"}");
        Question malformedContent = question(QuestionType.OPEN, "Capital of France", "{not-json");
        Question missingType = question(null, "Capital of France", "{\"answer\":\"Paris\"}");

        assertThat(GeneratedQuestionSemanticIdentity.from(null)).isEmpty();
        assertThat(GeneratedQuestionSemanticIdentity.from(blankStem)).isEmpty();
        assertThat(GeneratedQuestionSemanticIdentity.from(malformedContent)).isEmpty();
        assertThat(GeneratedQuestionSemanticIdentity.from(missingType)).isEmpty();
    }

    private static GeneratedQuestionSemanticIdentity.Identity identity(Question question) {
        Optional<GeneratedQuestionSemanticIdentity.Identity> identity =
                GeneratedQuestionSemanticIdentity.from(question);
        assertThat(identity).isPresent();
        return identity.orElseThrow();
    }

    private static Question question(QuestionType type, String stem, String content) {
        Question question = new Question();
        question.setType(type);
        question.setDifficulty(Difficulty.MEDIUM);
        question.setQuestionText(stem);
        question.setContent(content);
        return question;
    }

    private static Stream<Arguments> semanticIdentityFixtures() {
        return Stream.of(
                Arguments.of(
                        "MCQ_SINGLE ignores option IDs and display order",
                        QuestionType.MCQ_SINGLE,
                        """
                                {"options":[
                                  {"id":"a","text":"Paris","correct":true},
                                  {"id":"b","text":"London","correct":false}
                                ]}
                                """,
                        """
                                {"options":[
                                  {"correct":false,"text":" LONDON ","id":"second"},
                                  {"correct":true,"text":"paris","id":"first"}
                                ]}
                                """,
                        """
                                {"options":[
                                  {"id":"a","text":"Paris","correct":false},
                                  {"id":"b","text":"London","correct":true}
                                ]}
                                """
                ),
                Arguments.of(
                        "MCQ_MULTI ignores option IDs and display order",
                        QuestionType.MCQ_MULTI,
                        """
                                {"options":[
                                  {"id":"a","text":"Paris","correct":true},
                                  {"id":"b","text":"Lyon","correct":true},
                                  {"id":"c","text":"London","correct":false}
                                ]}
                                """,
                        """
                                {"options":[
                                  {"id":"3","text":" london ","correct":false},
                                  {"id":"2","text":"LYON","correct":true},
                                  {"id":"1","text":"paris","correct":true}
                                ]}
                                """,
                        """
                                {"options":[
                                  {"id":"a","text":"Paris","correct":true},
                                  {"id":"b","text":"Lyon","correct":false},
                                  {"id":"c","text":"London","correct":false}
                                ]}
                                """
                ),
                Arguments.of(
                        "TRUE_FALSE uses the boolean answer",
                        QuestionType.TRUE_FALSE,
                        "{\"answer\":true}",
                        "{ \"answer\" : true }",
                        "{\"answer\":false}"
                ),
                Arguments.of(
                        "OPEN normalizes the model answer",
                        QuestionType.OPEN,
                        "{\"answer\":\"Object Oriented Programming\"}",
                        "{\"answer\":\" object   oriented programming \"}",
                        "{\"answer\":\"Functional Programming\"}"
                ),
                Arguments.of(
                        "FILL_GAP ignores drag distractors but preserves the template and answers",
                        QuestionType.FILL_GAP,
                        """
                                {
                                  "text":"The capital of France is {1}.",
                                  "gaps":[{"id":1,"answer":"Paris"}],
                                  "options":["Paris","London","Rome","Berlin","Madrid","Lisbon","Vienna"]
                                }
                                """,
                        """
                                {
                                  "options":["Paris","Prague","Oslo","Dublin","Athens","Warsaw","Sofia"],
                                  "gaps":[{"answer":" paris ","id":1}],
                                  "text":"the capital of france is {1}."
                                }
                                """,
                        """
                                {
                                  "text":"The capital of France is {1}.",
                                  "gaps":[{"id":1,"answer":"Lyon"}],
                                  "options":["Lyon","London","Rome","Berlin","Madrid","Lisbon","Vienna"]
                                }
                                """
                ),
                Arguments.of(
                        "ORDERING ignores item IDs and shuffled display order but preserves the correct sequence",
                        QuestionType.ORDERING,
                        """
                                {
                                  "items":[{"id":2,"text":"Second"},{"id":1,"text":"First"}],
                                  "correctOrder":[1,2]
                                }
                                """,
                        """
                                {
                                  "correctOrder":[10,20],
                                  "items":[{"id":10,"text":" first "},{"id":20,"text":"SECOND"}]
                                }
                                """,
                        """
                                {
                                  "items":[{"id":2,"text":"Second"},{"id":1,"text":"First"}],
                                  "correctOrder":[2,1]
                                }
                                """
                ),
                Arguments.of(
                        "MATCHING ignores IDs and column order but preserves pair semantics",
                        QuestionType.MATCHING,
                        """
                                {
                                  "left":[
                                    {"id":1,"text":"France","matchId":10},
                                    {"id":2,"text":"Italy","matchId":11}
                                  ],
                                  "right":[
                                    {"id":10,"text":"Paris"},
                                    {"id":11,"text":"Rome"}
                                  ]
                                }
                                """,
                        """
                                {
                                  "right":[
                                    {"id":8,"text":" ROME "},
                                    {"id":7,"text":"paris"}
                                  ],
                                  "left":[
                                    {"id":200,"text":" italy ","matchId":8},
                                    {"id":100,"text":"FRANCE","matchId":7}
                                  ]
                                }
                                """,
                        """
                                {
                                  "left":[
                                    {"id":1,"text":"France","matchId":11},
                                    {"id":2,"text":"Italy","matchId":10}
                                  ],
                                  "right":[
                                    {"id":10,"text":"Paris"},
                                    {"id":11,"text":"Rome"}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "HOTSPOT ignores region IDs and display order",
                        QuestionType.HOTSPOT,
                        """
                                {
                                  "imageUrl":"diagram.png",
                                  "regions":[
                                    {"id":1,"x":10,"y":20,"width":30,"height":40,"correct":true},
                                    {"id":2,"x":50,"y":60,"width":20,"height":20,"correct":false}
                                  ]
                                }
                                """,
                        """
                                {
                                  "regions":[
                                    {"id":20,"x":50,"y":60,"width":20,"height":20,"correct":false},
                                    {"id":10,"x":10,"y":20,"width":30,"height":40,"correct":true}
                                  ],
                                  "imageUrl":"DIAGRAM.PNG"
                                }
                                """,
                        """
                                {
                                  "imageUrl":"diagram.png",
                                  "regions":[
                                    {"id":1,"x":11,"y":20,"width":30,"height":40,"correct":true},
                                    {"id":2,"x":50,"y":60,"width":20,"height":20,"correct":false}
                                  ]
                                }
                                """
                ),
                Arguments.of(
                        "COMPLIANCE ignores statement IDs and display order",
                        QuestionType.COMPLIANCE,
                        """
                                {"statements":[
                                  {"id":1,"text":"Encrypt data","compliant":true},
                                  {"id":2,"text":"Share passwords","compliant":false}
                                ]}
                                """,
                        """
                                {"statements":[
                                  {"id":20,"text":" share passwords ","compliant":false},
                                  {"id":10,"text":"ENCRYPT DATA","compliant":true}
                                ]}
                                """,
                        """
                                {"statements":[
                                  {"id":1,"text":"Encrypt data","compliant":false},
                                  {"id":2,"text":"Share passwords","compliant":false}
                                ]}
                                """
                )
        );
    }
}
