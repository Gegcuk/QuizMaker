package uk.gegc.quizmaker.mapper;

import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.Tag;

import java.util.List;

public class QuestionMapper {
    public static QuestionDto toDto(Question question){
        QuestionDto questionDto = new QuestionDto();
        questionDto.setId(question.getId());
        questionDto.setType(question.getType());
        questionDto.setDifficulty(question.getDifficulty());
        questionDto.setQuestionText(question.getQuestionText());
        questionDto.setContent(question.getContent());
        questionDto.setHint(question.getHint());
        questionDto.setExplanation(question.getExplanation());
        questionDto.setAttachmentUrl(question.getAttachmentUrl());
        questionDto.setCreatedAt(question.getCreatedAt());
        questionDto.setUpdatedAt(question.getUpdatedAt());
        questionDto.setQuizIds(question.getQuizId()
                .stream()
                .map(Quiz::getId)
                .toList());
        questionDto.setTagIds(question.getTags()
                .stream()
                .map(Tag::getId)
                .toList());

        return questionDto;
    }

    public static Question fromCreate(CreateQuestionRequest request, List<Quiz> quizzes, List<Tag> tags){
        Question question = new Question();
        question.setDifficulty(request.getDifficulty());
        question.setQuestionText(request.getQuestionText());
        question.setContent(request.getContent());
        question.setHint(request.getHint());
        question.setExplanation(request.getExplanation());
        question.setAttachmentUrl(request.getAttachmentUrl());
        question.setQuizId(quizzes);
        question.setTags(tags);

        return question;
    }

    public static void updateEntity(Question question, UpdateQuestionRequest request,
                                    List<Quiz> quizzes,
                                    List<Tag> tags) {
        question.setType(request.getType());
        question.setDifficulty(request.getDifficulty());
        question.setQuestionText(request.getQuestionText());
        question.setContent(request.getContent());
        question.setHint(request.getHint());
        question.setExplanation(request.getExplanation());
        question.setAttachmentUrl(request.getAttachmentUrl());
        question.setQuizId(quizzes);
        question.setTags(tags);
    }


}
