# Tag Controller API Reference

Complete frontend integration guide for `/api/v1/tags` REST endpoints. This document is self-contained and includes all DTOs, validation rules, authorization requirements, and error semantics needed to integrate without accessing backend code.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Query Parameters](#query-parameters)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
- [Error Handling](#error-handling)
- [Integration Notes](#integration-notes)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/tags`
* **Authentication**:
  * `GET` endpoints are public (no token required) to allow tag suggestions in public experiences.
  * Mutating endpoints (`POST`, `PATCH`, `DELETE`) require JWT Bearer token with admin role.
* **Content-Type**: `application/json` for requests and responses (except `204` responses).
* **Pagination**: All list responses are Spring `Page` objects containing pagination metadata in the body.
* **Error Format**: Errors are returned as [`ErrorResponse`](#errorresponse) objects unless otherwise specified.

---

## Authorization Matrix

| Capability | Endpoint(s) | Authentication | Authorization Rule | Notes |
| --- | --- | --- | --- | --- |
| **List tags** | `GET /` | Optional | Public access. Authenticated users also allowed. | Results are identical regardless of role. |
| **Fetch tag details** | `GET /{tagId}` | Optional | Public access. Authenticated users also allowed. | Returns 404 when tag does not exist. |
| **Create tag** | `POST /` | Required | `hasRole('ADMIN')` | Returns 403 for non-admin authenticated users. |
| **Update tag** | `PATCH /{tagId}` | Required | `hasRole('ADMIN')` and tag must exist. | 404 if tag missing. |
| **Delete tag** | `DELETE /{tagId}` | Required | `hasRole('ADMIN')` and tag must exist. | 204 on success, 404 if tag missing. |

*Admin-only endpoints must include `Authorization: Bearer <JWT>` header. Tokens must encode the `ADMIN` role.*

---

## Query Parameters

### Pagination & Sorting

Supported on `GET /api/v1/tags` via Spring pageable resolver:

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `page` | integer (0-based) | `0` | Page index to retrieve. |
| `size` | integer | `20` | Page size. |
| `sort` | string | `name,asc` | Accepts standard Spring sort syntax (`field,direction`). Multiple sort clauses allowed. |

Invalid pagination parameters trigger a `400 Bad Request` with `ErrorResponse` body.

---

## Request DTOs

### CreateTagRequest

**Used by**: `POST /api/v1/tags`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | string | Yes | 3–50 characters | Display name shown throughout the UI |
| `description` | string | No | Max 1000 characters | Optional long description for hover/tooltips |

**Example**:
```json
{
  "name": "science",
  "description": "Questions about scientific topics"
}
```

**Example with Minimal Fields**:
```json
{
  "name": "mathematics"
}
```

**Validation Notes**:
- `name` is case-sensitive and should be unique (though not enforced by backend)
- Leading/trailing whitespace is typically trimmed
- Special characters are allowed in `name`

---

### UpdateTagRequest

**Used by**: `PATCH /api/v1/tags/{tagId}`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | string | No | 3–50 characters if provided | New display name |
| `description` | string | No | Max 1000 characters if provided | Updated description |

**Example (Update Name Only)**:
```json
{
  "name": "advanced-science"
}
```

**Example (Update Description Only)**:
```json
{
  "description": "Advanced scientific topics including physics, chemistry, and biology"
}
```

**Example (Update Both)**:
```json
{
  "name": "stem",
  "description": "Science, Technology, Engineering, and Mathematics topics"
}
```

**Notes**:
- All fields are optional; send only fields that should change
- Omitted fields remain unchanged
- At least one field must be provided

---

## Response DTOs

### TagDto

**Returned by**: `GET /api/v1/tags`, `GET /api/v1/tags/{tagId}`, `POST /api/v1/tags`, `PATCH /api/v1/tags/{tagId}`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Unique identifier of the tag |
| `name` | string | Display name |
| `description` | string (nullable) | Optional descriptive text |

**Example**:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "mathematics",
  "description": "Questions related to mathematics and mathematical concepts"
}
```

**Example (No Description)**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "science",
  "description": null
}
```

---

### Page<TagDto>

Structure returned by `GET /api/v1/tags`. Standard Spring page JSON:

```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "math",
      "description": "Questions related to mathematics"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 42,
  "totalPages": 3,
  "last": false,
  "size": 20,
  "number": 0,
  "sort": {
    "sorted": true,
    "unsorted": false,
    "empty": false
  },
  "first": true,
  "numberOfElements": 20,
  "empty": false
}
```

### CreateTagResponse

**Returned by**: `POST /api/v1/tags`

| Field | Type | Description |
| --- | --- | --- |
| `tagId` | UUID | ID of the newly created tag |

**Example**:
```json
{
  "tagId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

---

## Endpoints

### List Tags

```
GET /api/v1/tags
```

**Purpose**: Retrieve paginated list of tags sorted by `name ASC` by default.

**Authentication**: Optional (public endpoint)

**Query Parameters**:
- `page` (integer, optional, default: `0`) - Page number (0-indexed)
- `size` (integer, optional, default: `20`) - Page size
- `sort` (string, optional, default: `name,asc`) - Sort specification

**Success Response**: `200 OK` - `Page<TagDto>`

```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "mathematics",
      "description": "Questions related to mathematics"
    },
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "name": "science",
      "description": "Scientific topics"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "sorted": true,
      "unsorted": false,
      "empty": false
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 42,
  "totalPages": 3,
  "last": false,
  "size": 20,
  "number": 0,
  "first": true,
  "numberOfElements": 20,
  "empty": false
}
```

**Error Responses**:
- `400 Bad Request` - Invalid pagination parameters

---

### Get Tag by ID

```
GET /api/v1/tags/{tagId}
```

**Purpose**: Fetch a single tag by UUID.

**Authentication**: Optional (public endpoint)

**Path Parameters**:
- `{tagId}` (UUID, required) - Tag identifier

**Success Response**: `200 OK` - `TagDto`

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "mathematics",
  "description": "Questions related to mathematics and mathematical concepts"
}
```

**Error Responses**:
- `400 Bad Request` - Invalid UUID format
- `404 Not Found` - Tag does not exist

---

### Create Tag

```
POST /api/v1/tags
```

**Purpose**: Create a new tag. Admin-only operation.

**Authentication**: Required (JWT Bearer token with `ADMIN` role)

**Request Body**: `CreateTagRequest`

**Success Response**: `201 Created` - `CreateTagResponse`

```json
{
  "tagId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

**Error Responses**:
- `400 Bad Request` - Validation failure (e.g., name too short)
- `401 Unauthorized` - Missing or invalid token
- `403 Forbidden` - User lacks `ADMIN` role

**Example Request**:
```bash
POST /api/v1/tags
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "name": "artificial-intelligence",
  "description": "Questions about AI, machine learning, and deep learning"
}
```

---

### Update Tag

```
PATCH /api/v1/tags/{tagId}
```

**Purpose**: Update name and/or description of an existing tag. Partial updates supported.

**Authentication**: Required (JWT Bearer token with `ADMIN` role)

**Path Parameters**:
- `{tagId}` (UUID, required) - Tag identifier

**Request Body**: `UpdateTagRequest`

**Success Response**: `200 OK` - `TagDto`

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "advanced-mathematics",
  "description": "Advanced mathematical topics including calculus and linear algebra"
}
```

**Error Responses**:
- `400 Bad Request` - Validation failure or invalid UUID
- `401 Unauthorized` - Missing or invalid token
- `403 Forbidden` - User lacks `ADMIN` role
- `404 Not Found` - Tag does not exist

**Example Request**:
```bash
PATCH /api/v1/tags/3fa85f64-5717-4562-b3fc-2c963f66afa6
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "name": "advanced-mathematics",
  "description": "Advanced mathematical topics"
}
```

---

### Delete Tag

```
DELETE /api/v1/tags/{tagId}
```

**Purpose**: Delete a tag permanently.

**Authentication**: Required (JWT Bearer token with `ADMIN` role)

**Path Parameters**:
- `{tagId}` (UUID, required) - Tag identifier

**Success Response**: `204 No Content`

**Error Responses**:
- `400 Bad Request` - Invalid UUID format
- `401 Unauthorized` - Missing or invalid token
- `403 Forbidden` - User lacks `ADMIN` role
- `404 Not Found` - Tag does not exist

**Example Request**:
```bash
DELETE /api/v1/tags/3fa85f64-5717-4562-b3fc-2c963f66afa6
Authorization: Bearer <admin-token>
```

---

## Error Handling

### ErrorResponse

All handled errors use a unified payload:

```json
{
  "timestamp": "2024-04-23T10:15:30.123",
  "status": 400,
  "error": "Validation Failed",
  "details": [
    "name: Tag length must be between 3 and 50 characters"
  ]
}
```

| Field | Type | Description |
| --- | --- | --- |
| `timestamp` | ISO-8601 string | Server time the error was generated. |
| `status` | integer | HTTP status code. |
| `error` | string | Short status phrase for display/logging. |
| `details` | array of strings | Human-readable explanations (one or more). |

### Common Error Scenarios

| Status | When it occurs | Suggested UI handling |
| --- | --- | --- |
| `400 Bad Request` | Invalid UUID in path, JSON parse errors, Bean Validation failures (`name`/`description`). | Surface `details` messages inline with form fields. |
| `401 Unauthorized` | Missing or invalid JWT for admin actions. | Prompt re-authentication or refresh token. |
| `403 Forbidden` | Authenticated user lacks `ADMIN` role. | Show "You need administrator access" message. |
| `404 Not Found` | Tag ID not present in database. | Display toast or inline message and redirect away from stale resource. |
| `409 Conflict` | Database constraint issues (rare). | Ask user to retry or contact support. |
| `500 Internal Server Error` | Unhandled server exception. | Show generic error and allow retry. |

---

## Integration Guide

### Fetch All Tags

**Display tags with pagination**:

```javascript
const fetchTags = async (page = 0, size = 20, sort = 'name,asc') => {
  const params = new URLSearchParams({
    page: page,
    size: size,
    sort: sort
  });

  const response = await fetch(`/api/v1/tags?${params}`);
  
  if (!response.ok) {
    console.error('Failed to fetch tags');
    return null;
  }

  const data = await response.json();
  
  console.log(`Total tags: ${data.totalElements}`);
  console.log(`Page ${data.number + 1} of ${data.totalPages}`);
  
  return data;
};

// Usage
const tagsPage = await fetchTags(0, 20);
tagsPage.content.forEach(tag => {
  console.log(`${tag.name}: ${tag.description}`);
});
```

---

### Get Single Tag

**Fetch tag details**:

```javascript
const getTag = async (tagId) => {
  const response = await fetch(`/api/v1/tags/${tagId}`);
  
  if (response.status === 404) {
    console.log('Tag not found');
    return null;
  }
  
  if (!response.ok) {
    console.error('Failed to fetch tag');
    return null;
  }

  const tag = await response.json();
  return tag;
};
```

---

### Create Tag (Admin)

**Create a new tag**:

```javascript
const createTag = async (name, description = null) => {
  const response = await fetch('/api/v1/tags', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${adminToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      name: name,
      description: description
    })
  });

  if (response.status === 401) {
    console.error('Authentication required');
    redirectToLogin();
    return null;
  }

  if (response.status === 403) {
    console.error('Admin privileges required');
    showNotification('You need administrator access to create tags', 'error');
    return null;
  }

  if (response.status === 400) {
    const error = await response.json();
    console.error('Validation error:', error.details);
    showValidationErrors(error.details);
    return null;
  }

  const result = await response.json();
  console.log('Tag created:', result.tagId);
  
  // Optionally fetch the full tag details
  return await getTag(result.tagId);
};

// Usage
const newTag = await createTag('programming', 'Software development topics');
```

---

### Update Tag (Admin)

**Update an existing tag**:

```javascript
const updateTag = async (tagId, updates) => {
  const response = await fetch(`/api/v1/tags/${tagId}`, {
    method: 'PATCH',
    headers: {
      'Authorization': `Bearer ${adminToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(updates)
  });

  if (response.status === 404) {
    console.error('Tag not found');
    showNotification('Tag not found', 'error');
    return null;
  }

  if (response.status === 403) {
    console.error('Admin privileges required');
    showNotification('You need administrator access to update tags', 'error');
    return null;
  }

  if (response.status === 400) {
    const error = await response.json();
    console.error('Validation error:', error.details);
    showValidationErrors(error.details);
    return null;
  }

  const updatedTag = await response.json();
  console.log('Tag updated:', updatedTag);
  return updatedTag;
};

// Usage - Update name only
await updateTag('tag-uuid-here', { 
  name: 'advanced-programming' 
});

// Update both name and description
await updateTag('tag-uuid-here', {
  name: 'web-development',
  description: 'HTML, CSS, JavaScript, and modern web frameworks'
});
```

---

### Delete Tag (Admin)

**Delete a tag**:

```javascript
const deleteTag = async (tagId) => {
  const response = await fetch(`/api/v1/tags/${tagId}`, {
    method: 'DELETE',
    headers: {
      'Authorization': `Bearer ${adminToken}`
    }
  });

  if (response.status === 404) {
    console.error('Tag not found');
    return false;
  }

  if (response.status === 403) {
    console.error('Admin privileges required');
    showNotification('You need administrator access to delete tags', 'error');
    return false;
  }

  if (response.status === 204) {
    console.log('Tag deleted successfully');
    return true;
  }

  console.error('Failed to delete tag');
  return false;
};

// Usage with confirmation
const confirmAndDelete = async (tagId) => {
  const tag = await getTag(tagId);
  
  if (!tag) {
    console.error('Tag not found');
    return;
  }

  const confirmed = confirm(`Delete tag "${tag.name}"?`);
  
  if (confirmed) {
    const success = await deleteTag(tagId);
    if (success) {
      // Refresh tag list
      await fetchTags();
    }
  }
};
```

---

### Tag Selector Component

**React component for tag selection**:

```javascript
import React, { useState, useEffect } from 'react';

const TagSelector = ({ selectedTags, onChange }) => {
  const [tags, setTags] = useState([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  useEffect(() => {
    loadTags();
  }, [page]);

  const loadTags = async () => {
    setLoading(true);
    try {
      const data = await fetchTags(page, 20);
      setTags(data.content);
      setTotalPages(data.totalPages);
    } catch (error) {
      console.error('Error loading tags:', error);
    } finally {
      setLoading(false);
    }
  };

  const toggleTag = (tagId) => {
    const isSelected = selectedTags.includes(tagId);
    
    if (isSelected) {
      onChange(selectedTags.filter(id => id !== tagId));
    } else {
      onChange([...selectedTags, tagId]);
    }
  };

  if (loading) return <div>Loading tags...</div>;

  return (
    <div className="tag-selector">
      <div className="tag-list">
        {tags.map(tag => (
          <button
            key={tag.id}
            className={`tag-button ${selectedTags.includes(tag.id) ? 'selected' : ''}`}
            onClick={() => toggleTag(tag.id)}
            title={tag.description}
          >
            {tag.name}
          </button>
        ))}
      </div>

      {totalPages > 1 && (
        <div className="pagination">
          <button 
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <span>Page {page + 1} of {totalPages}</span>
          <button 
            disabled={page === totalPages - 1}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
};

export default TagSelector;
```

---

### Tag Management UI (Admin)

**Complete tag management interface**:

```javascript
const TagManagement = () => {
  const [tags, setTags] = useState([]);
  const [editingTag, setEditingTag] = useState(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    loadAllTags();
  }, []);

  const loadAllTags = async () => {
    const data = await fetchTags(0, 100); // Fetch all tags
    setTags(data.content);
  };

  const handleCreate = async (formData) => {
    const newTag = await createTag(formData.name, formData.description);
    
    if (newTag) {
      await loadAllTags();
      setCreating(false);
      showNotification('Tag created successfully', 'success');
    }
  };

  const handleUpdate = async (tagId, updates) => {
    const updatedTag = await updateTag(tagId, updates);
    
    if (updatedTag) {
      await loadAllTags();
      setEditingTag(null);
      showNotification('Tag updated successfully', 'success');
    }
  };

  const handleDelete = async (tagId) => {
    const success = await deleteTag(tagId);
    
    if (success) {
      await loadAllTags();
      showNotification('Tag deleted successfully', 'success');
    }
  };

  return (
    <div className="tag-management">
      <h2>Tag Management</h2>
      
      <button onClick={() => setCreating(true)}>
        Create New Tag
      </button>

      <table className="tags-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Description</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {tags.map(tag => (
            <tr key={tag.id}>
              <td>{tag.name}</td>
              <td>{tag.description || 'No description'}</td>
              <td>
                <button onClick={() => setEditingTag(tag)}>
                  Edit
                </button>
                <button onClick={() => handleDelete(tag.id)}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {creating && (
        <TagFormModal
          onSubmit={handleCreate}
          onCancel={() => setCreating(false)}
        />
      )}

      {editingTag && (
        <TagFormModal
          tag={editingTag}
          onSubmit={(updates) => handleUpdate(editingTag.id, updates)}
          onCancel={() => setEditingTag(null)}
        />
      )}
    </div>
  );
};
```

---

### Error Handling

**Comprehensive error handling for tag operations**:

```javascript
const handleTagOperationError = async (operation) => {
  try {
    return await operation();
  } catch (error) {
    const status = error.status || 500;
    const errorData = await error.json?.() || error;

    switch (status) {
      case 400:
        console.error('Validation error:', errorData.details);
        errorData.details?.forEach(detail => {
          showFieldError(detail);
        });
        break;

      case 401:
        console.error('Authentication required');
        showNotification('Please log in to continue', 'info');
        redirectToLogin();
        break;

      case 403:
        console.error('Admin access required');
        showNotification(
          'You need administrator access to perform this action',
          'warning'
        );
        break;

      case 404:
        console.error('Tag not found');
        showNotification('Tag not found', 'error');
        break;

      case 409:
        console.error('Conflict:', errorData.details);
        showNotification('Operation failed due to conflict. Please retry.', 'error');
        break;

      case 500:
        console.error('Server error:', errorData);
        showNotification(
          'An unexpected error occurred. Please try again later.',
          'error'
        );
        break;

      default:
        console.error('Unexpected error:', errorData);
        showNotification('An unexpected error occurred', 'error');
    }
  }
};

// Usage
const safelyCreateTag = async (name, description) => {
  return await handleTagOperationError(() => 
    createTag(name, description)
  );
};
```

---

### Caching Strategy

**Implement client-side tag caching**:

```javascript
class TagCache {
  constructor(ttl = 5 * 60 * 1000) { // 5 minutes default TTL
    this.cache = new Map();
    this.ttl = ttl;
  }

  get(key) {
    const entry = this.cache.get(key);
    
    if (!entry) return null;
    
    if (Date.now() - entry.timestamp > this.ttl) {
      this.cache.delete(key);
      return null;
    }
    
    return entry.data;
  }

  set(key, data) {
    this.cache.set(key, {
      data: data,
      timestamp: Date.now()
    });
  }

  invalidate(key) {
    if (key) {
      this.cache.delete(key);
    } else {
      this.cache.clear();
    }
  }
}

const tagCache = new TagCache();

const fetchTagsCached = async (page = 0, size = 20, sort = 'name,asc') => {
  const cacheKey = `tags_${page}_${size}_${sort}`;
  
  // Try cache first
  const cached = tagCache.get(cacheKey);
  if (cached) {
    console.log('Returning cached tags');
    return cached;
  }

  // Fetch fresh data
  const data = await fetchTags(page, size, sort);
  
  // Update cache
  tagCache.set(cacheKey, data);
  
  return data;
};

// Invalidate cache after mutations
const createTagAndInvalidateCache = async (name, description) => {
  const tag = await createTag(name, description);
  
  if (tag) {
    tagCache.invalidate(); // Clear all cached tag lists
  }
  
  return tag;
};
```

---

## Security Considerations

### Authorization Best Practices

1. **Admin Role Enforcement**:
   - Only users with `ADMIN` role can mutate tags (`POST`, `PATCH`, `DELETE`)
   - Hide admin controls in UI for non-admin users
   - Handle `403 Forbidden` responses gracefully
   - Display clear messaging when actions require elevated permissions

2. **Token Management**:
   - Store JWT tokens securely (HttpOnly cookies recommended for web)
   - Include `Authorization: Bearer <JWT>` header for admin operations
   - Implement token refresh before expiry
   - Clear tokens on logout
   - Handle `401 Unauthorized` with automatic re-authentication

3. **Permission Checks**:
   - Check user role client-side before showing admin UI
   - Always respect server-side authorization decisions
   - Don't rely solely on client-side checks for security

### Input Validation

1. **Frontend Validation**:
   - Mirror backend constraints (3-50 characters for name, max 1000 for description)
   - Provide instant feedback on validation errors
   - Prevent submission of invalid data
   - Trim whitespace before submission

2. **Backend Validation**:
   - Trust that backend enforces all constraints
   - Display backend validation errors clearly to users
   - Map validation messages to form fields

3. **Special Characters**:
   - Tag names support special characters
   - No special escaping needed for JSON
   - Consider UI/UX implications of special characters in tag names

### Data Privacy

1. **Public Access**:
   - Tag listings are public by default
   - Consider if tags should expose sensitive information
   - Admin operations leave audit trails (server-side)

2. **Tag Content**:
   - Tag names and descriptions are publicly visible
   - Don't include sensitive data in tag content
   - Consider content moderation for user-generated tags (if applicable)

### Performance & Resource Management

1. **Rate Limiting**:
   - Respect global rate limits
   - Handle `429 Too Many Requests` responses
   - Check `Retry-After` header when present
   - Implement exponential backoff for retries

2. **Caching**:
   - Cache tag lists client-side to reduce server load
   - Invalidate cache after create/update/delete operations
   - Use appropriate TTL (e.g., 5-10 minutes for tag lists)
   - Consider ETag-based caching if supported

3. **Pagination**:
   - Don't fetch all tags at once without pagination
   - Use reasonable page sizes (20-50 items)
   - Implement infinite scroll or pagination for large tag lists

### Best Practices

1. **Idempotency**:
   - Creating the same tag twice creates duplicate records
   - Implement client-side duplicate prevention
   - Disable submit buttons while operations are pending
   - Consider checking for existing tags before creating new ones

2. **Optimistic Updates**:
   - Update UI optimistically for better UX
   - Revert changes if operation fails
   - Refresh data after mutations to ensure consistency

3. **Error Handling**:
   - Display validation errors inline with form fields
   - Show user-friendly error messages
   - Provide retry options for transient failures
   - Log errors for debugging (without exposing sensitive data)

4. **Accessibility**:
   - Ensure tag selectors are keyboard-navigable
   - Provide proper ARIA labels for screen readers
   - Use semantic HTML for tag lists and controls
   - Support keyboard shortcuts for common operations

5. **User Experience**:
   - Show loading states during API calls
   - Provide feedback for successful operations
   - Implement search/filter for large tag lists
   - Allow sorting by name or other criteria
   - Display tag counts if relevant (e.g., number of quizzes using each tag)
