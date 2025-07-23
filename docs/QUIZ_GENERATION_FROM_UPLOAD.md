# Quiz Generation from Upload - Frontend Documentation

## Overview

The `/api/v1/quizzes/generate-from-upload` endpoint allows you to upload a document and start quiz generation in a single API call. This simplifies the frontend integration by combining document upload, processing, and quiz generation initiation into one operation. The actual quiz generation happens asynchronously in the background.

## Endpoint Details

**URL:** `POST /api/v1/quizzes/generate-from-upload`  
**Content-Type:** `multipart/form-data`  
**Authorization:** `Bearer <access_token>` (Admin role required)  
**Response:** `QuizGenerationResponse` (job ID for tracking progress)

## Request Parameters

### Required Parameters

| Parameter | Type | Description | Example |
|-----------|------|-------------|---------|
| `file` | File | The document file to upload | `document.pdf` |
| `questionsPerType` | String (JSON) | Number of questions per type to generate per chunk | `"{\"MCQ_SINGLE\": 3, \"TRUE_FALSE\": 2}"` |
| `difficulty` | String | Difficulty level for generated questions | `"MEDIUM"` |

### Optional Parameters

| Parameter | Type | Default | Description | Example |
|-----------|------|---------|-------------|---------|
| `chunkingStrategy` | String | `"CHAPTER_BASED"` | How to split the document into chunks | `"SIZE_BASED"` |
| `maxChunkSize` | Number | `50000` | Maximum characters per chunk | `30000` |
| `quizScope` | String | `"ENTIRE_DOCUMENT"` | Which parts of document to use | `"SPECIFIC_CHAPTER"` |
| `chunkIndices` | Array[Number] | - | Specific chunks to include (for SPECIFIC_CHUNKS scope) | `[0, 1, 2]` |
| `chapterTitle` | String | - | Chapter title (for SPECIFIC_CHAPTER/SECTION scope) | `"Introduction"` |
| `chapterNumber` | Number | - | Chapter number (for SPECIFIC_CHAPTER/SECTION scope) | `1` |
| `quizTitle` | String | - | Custom quiz title (AI generates if not provided) | `"My Quiz"` |
| `quizDescription` | String | - | Custom quiz description (AI generates if not provided) | `"Test your knowledge"` |
| `estimatedTimePerQuestion` | Number | `2` | Estimated minutes per question | `3` |
| `categoryId` | UUID | - | Category ID for the quiz | `"uuid-here"` |
| `tagIds` | Array[UUID] | `[]` | List of tag IDs for the quiz | `["tag1", "tag2"]` |

## Parameter Values

### Chunking Strategies
- `"AUTO"` - Automatically determine best strategy
- `"CHAPTER_BASED"` - Split by chapters only
- `"SECTION_BASED"` - Split by sections only
- `"SIZE_BASED"` - Split by size only
- `"PAGE_BASED"` - Split by page count

### Quiz Scopes
- `"ENTIRE_DOCUMENT"` - Generate from all chunks
- `"SPECIFIC_CHUNKS"` - Generate from specific chunk indices
- `"SPECIFIC_CHAPTER"` - Generate from specific chapter
- `"SPECIFIC_SECTION"` - Generate from specific section

### Question Types
- `"MCQ_SINGLE"` - Multiple choice (single answer)
- `"MCQ_MULTIPLE"` - Multiple choice (multiple answers)
- `"TRUE_FALSE"` - True/False questions
- `"OPEN"` - Open-ended questions
- `"FILL_GAP"` - Fill in the gap
- `"ORDERING"` - Ordering questions
- `"HOTSPOT"` - Hotspot questions
- `"COMPLIANCE"` - Compliance questions

### Difficulty Levels
- `"EASY"` - Easy questions
- `"MEDIUM"` - Medium difficulty questions
- `"HARD"` - Hard questions

## Response Format

The endpoint returns a `QuizGenerationResponse` object:

```json
{
  "jobId": "job-uuid-here",
  "status": "PROCESSING",
  "message": "Quiz generation started successfully",
  "estimatedTimeSeconds": 300
}
```

## Frontend Workflow

1. **Upload document and start generation**
2. **Get job ID immediately** (no waiting)
3. **Continue using the app** (non-blocking)
4. **Optionally check job status** when needed
5. **See new quizzes in the list** when ready

### Optional Status Checking

You can check the generation progress using the existing endpoints:

```javascript
// Check job status (optional)
const status = await fetch(`/api/v1/quizzes/generation-status/${jobId}`);
const statusData = await status.json();

// Get generated quiz when complete
if (statusData.isCompleted()) {
  const quiz = await fetch(`/api/v1/quizzes/generated-quiz/${jobId}`);
  const quizData = await quiz.json();
}
```

## Usage Examples

### Basic Usage (JavaScript)

```javascript
async function startQuizGeneration(file) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('questionsPerType', JSON.stringify({
    "MCQ_SINGLE": 5,
    "TRUE_FALSE": 3
  }));
  formData.append('difficulty', 'MEDIUM');

  try {
    const response = await fetch('/api/v1/quizzes/generate-from-upload', {
      method: 'POST',
      headers: {
        'Authorization': 'Bearer ' + getAuthToken()
      },
      body: formData
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const result = await response.json();
    console.log('Generation started:', result);
    return result.jobId; // Return job ID for optional tracking
  } catch (error) {
    console.error('Error starting quiz generation:', error);
    throw error;
  }
}
```

### Advanced Usage with All Parameters

```javascript
async function generateAdvancedQuiz(file) {
  const formData = new FormData();
  
  // File
  formData.append('file', file);
  
  // Document processing
  formData.append('chunkingStrategy', 'CHAPTER_BASED');
  formData.append('maxChunkSize', '40000');
  
  // Quiz generation
  formData.append('quizScope', 'ENTIRE_DOCUMENT');
  formData.append('questionsPerType', JSON.stringify({
    "MCQ_SINGLE": 3,
    "TRUE_FALSE": 2,
    "OPEN": 1,
    "FILL_GAP": 1
  }));
  formData.append('difficulty', 'HARD');
  formData.append('quizTitle', 'Advanced Machine Learning Quiz');
  formData.append('quizDescription', 'Comprehensive test of ML concepts');
  formData.append('estimatedTimePerQuestion', '3');
  formData.append('categoryId', 'category-uuid-here');
  formData.append('tagIds', JSON.stringify(['tag1-uuid', 'tag2-uuid']));

  const response = await fetch('/api/v1/quizzes/generate-from-upload', {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + getAuthToken()
    },
    body: formData
  });

  return await response.json();
}
```

### React Hook Example

```javascript
import { useState } from 'react';

function useQuizGeneration() {
  const [isGenerating, setIsGenerating] = useState(false);
  const [error, setError] = useState(null);
  const [quiz, setQuiz] = useState(null);

  const generateQuiz = async (file, options = {}) => {
    setIsGenerating(true);
    setError(null);
    
    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('questionsPerType', JSON.stringify(options.questionsPerType || {
        "MCQ_SINGLE": 5,
        "TRUE_FALSE": 3
      }));
      formData.append('difficulty', options.difficulty || 'MEDIUM');
      
      if (options.quizTitle) formData.append('quizTitle', options.quizTitle);
      if (options.quizDescription) formData.append('quizDescription', options.quizDescription);
      if (options.chunkingStrategy) formData.append('chunkingStrategy', options.chunkingStrategy);
      if (options.maxChunkSize) formData.append('maxChunkSize', options.maxChunkSize);

      const response = await fetch('/api/v1/quizzes/generate-from-upload', {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + getAuthToken()
        },
        body: formData
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.message || 'Failed to generate quiz');
      }

      const generatedQuiz = await response.json();
      setQuiz(generatedQuiz);
      return generatedQuiz;
    } catch (err) {
      setError(err.message);
      throw err;
    } finally {
      setIsGenerating(false);
    }
  };

  return { generateQuiz, isGenerating, error, quiz };
}
```

### Vue.js Example

```javascript
// Composition API
import { ref } from 'vue';

export function useQuizGeneration() {
  const isGenerating = ref(false);
  const error = ref(null);
  const quiz = ref(null);

  const generateQuiz = async (file, options = {}) => {
    isGenerating.value = true;
    error.value = null;

    try {
      const formData = new FormData();
      formData.append('file', file);
      formData.append('questionsPerType', JSON.stringify(options.questionsPerType || {
        "MCQ_SINGLE": 5,
        "TRUE_FALSE": 3
      }));
      formData.append('difficulty', options.difficulty || 'MEDIUM');

      const response = await fetch('/api/v1/quizzes/generate-from-upload', {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + getAuthToken()
        },
        body: formData
      });

      if (!response.ok) {
        throw new Error('Failed to generate quiz');
      }

      const generatedQuiz = await response.json();
      quiz.value = generatedQuiz;
      return generatedQuiz;
    } catch (err) {
      error.value = err.message;
      throw err;
    } finally {
      isGenerating.value = false;
    }
  };

  return {
    generateQuiz,
    isGenerating,
    error,
    quiz
  };
}
```

## Error Handling

### Common Error Responses

| Status Code | Description | Solution |
|-------------|-------------|----------|
| `400` | Bad Request - Invalid parameters | Check parameter values and format |
| `401` | Unauthorized - Missing/invalid token | Ensure valid authentication token |
| `403` | Forbidden - Insufficient permissions | Ensure user has ADMIN role |
| `422` | Unprocessable Entity - Document processing failed | Check file format and content |
| `500` | Internal Server Error | Contact support |

### Error Response Format

```json
{
  "timestamp": "2025-07-23T22:15:00",
  "status": 400,
  "error": "Bad Request",
  "details": "Invalid questionsPerType format",
  "path": "/api/v1/quizzes/generate-from-upload"
}
```

### Validation Errors

Common validation errors and their solutions:

- **Invalid questionsPerType JSON**: Ensure proper JSON format
- **File too large**: Check file size limits
- **Unsupported file type**: Use PDF, DOCX, or TXT files
- **Invalid difficulty**: Use EASY, MEDIUM, or HARD
- **Invalid chunking strategy**: Use valid strategy values

## Performance Considerations

- **Timeout**: The operation has a 5-minute timeout
- **File Size**: Large documents may take longer to process
- **Question Count**: More questions = longer generation time
- **Chunking Strategy**: Different strategies affect processing time

## Best Practices

1. **Show Loading State**: Display a loading indicator during generation
2. **Handle Errors Gracefully**: Provide user-friendly error messages
3. **Validate File Type**: Check file format before upload
4. **Set Reasonable Question Counts**: Don't exceed 10 questions per type per chunk
5. **Use Appropriate Difficulty**: Match difficulty to target audience
6. **Provide Meaningful Titles**: Help users identify generated quizzes

## File Requirements

- **Supported Formats**: PDF, DOCX, TXT
- **Maximum Size**: 150MB
- **Content**: Text-based documents work best
- **Language**: English is recommended for best AI results

## Security Notes

- Requires ADMIN role authentication
- File content is processed securely
- Generated quizzes are private by default
- No sensitive data is logged

## Troubleshooting

### Common Issues

1. **"Invalid questionsPerType format"**
   - Ensure JSON is properly formatted
   - Check that question types are valid

2. **"Document processing failed"**
   - Verify file format is supported
   - Check file isn't corrupted
   - Ensure file contains readable text

3. **"Generation timed out"**
   - Try with smaller documents
   - Reduce question count
   - Use simpler chunking strategy

4. **"Unauthorized"**
   - Check authentication token
   - Verify user has ADMIN role
   - Ensure token hasn't expired 