# User Controller API Reference

Complete frontend integration guide for `/api/v1/users` REST endpoints. This document is self-contained and includes all DTOs, validation rules, caching semantics, error contracts, and practical integration examples needed to build user profile features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
  - [Get User Profile](#get-user-profile)
  - [Update User Profile](#update-user-profile)
  - [Upload Avatar](#upload-avatar)
- [Error Handling](#error-handling)
- [Concurrency & Versioning](#concurrency--versioning)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/users`
* **Authentication**: Required for all endpoints (JWT Bearer token in `Authorization` header)
* **Authorization Model**: Self-service only - users can only access and modify their own profile
* **Content-Type**: 
  * `application/json` for profile operations
  * `multipart/form-data` for avatar upload
* **Caching**: Profile responses include `Cache-Control: no-store`, `Pragma: no-cache`, and `ETag` headers
* **Optimistic Locking**: `ETag` and `If-Match` headers prevent concurrent update conflicts
* **Error Format**: Standardized `ErrorResponse` or `ProblemDetail` body

---

## Authorization Matrix

All user profile endpoints operate on the currently authenticated user only. There are no admin overrides or cross-user access.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **View own profile** | `GET /me` | Authenticated user | Returns current user's profile |
| **Update own profile** | `PATCH /me` | Authenticated user | Partial updates supported |
| **Upload avatar** | `POST /me/avatar` | Authenticated user | Image only (PNG, JPEG) |

**Key Points**:
- No cross-user access - users cannot view or modify other users' profiles via these endpoints
- Admin user management is handled by separate admin APIs
- All requests require valid JWT token

---

## Request DTOs

### UpdateUserProfileRequest

**Used by**: `PATCH /api/v1/users/me`

Follows merge-patch semantics:
* **Omitted field** → no change
* **Field present with `null`** → clear value
* **Field present with non-null** → update value (sanitized by backend)

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `displayName` | string (nullable) | No | Max 50 characters after trimming | Display name (sanitized for XSS) |
| `bio` | string (nullable) | No | Max 500 characters after trimming | User biography (sanitized for XSS) |
| `preferences` | object (nullable) | No | ≤ 50 keys, each key ≤ 64 chars | User preferences as JSON object |

**Example (Update Display Name)**:
```json
{
  "displayName": "John Doe"
}
```

**Example (Update All Fields)**:
```json
{
  "displayName": "Jane Smith",
  "bio": "Software developer passionate about education and technology.",
  "preferences": {
    "theme": "dark",
    "language": "en",
    "notifications": {
      "email": true,
      "push": false
    }
  }
}
```

**Example (Clear Bio)**:
```json
{
  "bio": null
}
```

**Validation Notes**:
- `displayName` and `bio` are sanitized for XSS (HTML tags removed)
- Empty string `""` is valid for `displayName`
- `preferences` must be a valid JSON object if provided
- Unknown preference keys are preserved

---

## Response DTOs

### UserProfileResponse

**Returned by**: `GET /api/v1/users/me`, `PATCH /api/v1/users/me`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Stable user identifier |
| `username` | string | Immutable login username |
| `email` | string | Primary email address |
| `displayName` | string | Display name or username fallback |
| `bio` | string (nullable) | User biography (sanitized, max 500 chars) |
| `avatarUrl` | string (nullable) | HTTPS URL to avatar image, or `null` |
| `preferences` | object | User preferences as JSON object |
| `joinedAt` | ISO-8601 datetime | Account creation timestamp |
| `verified` | boolean | Email verification status |
| `roles` | array of strings | User roles (without `ROLE_` prefix) |
| `version` | number (nullable) | Optimistic locking version |

**Example**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "username": "john_doe",
  "email": "john.doe@example.com",
  "displayName": "John Doe",
  "bio": "Software developer and quiz enthusiast",
  "avatarUrl": "https://cdn.example.com/avatars/a1b2c3d4.png",
  "preferences": {
    "theme": "dark",
    "language": "en",
    "notifications": {
      "email": true,
      "push": false
    }
  },
  "joinedAt": "2024-01-15T10:00:00Z",
  "verified": true,
  "roles": ["USER"],
  "version": 3
}
```

**Example (Minimal Profile)**:
```json
{
  "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "username": "jane_smith",
  "email": "jane.smith@example.com",
  "displayName": "jane_smith",
  "bio": null,
  "avatarUrl": null,
  "preferences": {},
  "joinedAt": "2024-02-01T08:30:00Z",
  "verified": false,
  "roles": ["USER"],
  "version": 0
}
```

---

### AvatarUploadResponse

**Returned by**: `POST /api/v1/users/me/avatar`

| Field | Type | Description |
| --- | --- | --- |
| `avatarUrl` | string | Public HTTPS URL of the uploaded avatar (PNG format) |
| `message` | string | Success message |

**Example**:
```json
{
  "avatarUrl": "https://cdn.example.com/avatars/a1b2c3d4-v2.png",
  "message": "Avatar updated successfully"
}
```

---

## Endpoints

### Get User Profile

```
GET /api/v1/users/me
```

**Purpose**: Retrieve the authenticated user's profile.

**Authentication**: Required (JWT Bearer token)

**Success Response**: `200 OK` - `UserProfileResponse`

**Response Headers**:
- `Cache-Control: no-store`
- `Pragma: no-cache`
- `ETag: "<version>"` - Entity version for optimistic locking

**Example Response**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "username": "john_doe",
  "email": "john.doe@example.com",
  "displayName": "John Doe",
  "bio": "Software developer and quiz enthusiast",
  "avatarUrl": "https://cdn.example.com/avatars/a1b2c3d4.png",
  "preferences": {
    "theme": "dark",
    "language": "en"
  },
  "joinedAt": "2024-01-15T10:00:00Z",
  "verified": true,
  "roles": ["USER"],
  "version": 3
}
```

**Error Responses**:
- `401 Unauthorized` - Missing/invalid token or user inactive/deleted

**Example Request**:
```bash
GET /api/v1/users/me
Authorization: Bearer <token>
```

---

### Update User Profile

```
PATCH /api/v1/users/me
```

**Purpose**: Partial update for the authenticated user profile. Supports merge-patch semantics.

**Authentication**: Required (JWT Bearer token)

**Request Headers**:
- `Authorization: Bearer <token>` (required)
- `If-Match: "<version>"` (optional) - For optimistic locking
- `Content-Type: application/json`

**Request Body**: `UpdateUserProfileRequest` (all fields optional)

**Success Response**: `200 OK` - `UserProfileResponse`

**Response Headers**:
- `ETag: "<new-version>"` - Updated version

**Example Request (Update Display Name)**:
```bash
PATCH /api/v1/users/me
Authorization: Bearer <token>
If-Match: "3"
Content-Type: application/json

{
  "displayName": "John Smith"
}
```

**Example Response**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "username": "john_doe",
  "email": "john.doe@example.com",
  "displayName": "John Smith",
  "bio": "Software developer and quiz enthusiast",
  "avatarUrl": "https://cdn.example.com/avatars/a1b2c3d4.png",
  "preferences": {
    "theme": "dark",
    "language": "en"
  },
  "joinedAt": "2024-01-15T10:00:00Z",
  "verified": true,
  "roles": ["USER"],
  "version": 4
}
```

**Behavior Notes**:
- `displayName` and `bio` are sanitized for XSS (HTML tags removed)
- Text truncated to max lengths (50/500 chars)
- `preferences` must be valid JSON object
- Returns updated profile with new `version`

**Error Responses**:
- `400 Bad Request` - Invalid payload, validation failure, or serialization error
- `401 Unauthorized` - Not authenticated or user inactive/deleted
- `412 Precondition Failed` - `If-Match` version mismatch (concurrent update detected)

---

### Upload Avatar

```
POST /api/v1/users/me/avatar
```

**Purpose**: Upload and assign a new avatar image.

**Authentication**: Required (JWT Bearer token)

**Content-Type**: `multipart/form-data`

**Form Fields**:
- `file` (required) - Image file (PNG or JPEG)

**File Requirements**:
- **Max size**: 10 MB
- **Allowed types**: PNG, JPEG (WEBP and GIF not accepted)
- **Max dimensions**: 10,000 × 10,000 pixels
- **Max total pixels**: 40 million
- **Processing**: Auto-resized to max 512×512, converted to PNG, EXIF normalized

**Success Response**: `200 OK` - `AvatarUploadResponse`

```json
{
  "avatarUrl": "https://cdn.example.com/avatars/a1b2c3d4-v2.png",
  "message": "Avatar updated successfully"
}
```

**Post-conditions**:
- Old avatar (if any) is deleted after successful upload
- New avatar URL is immediately available

**Error Responses**:
- `400 Bad Request` - File missing/empty, too large, invalid dimensions, or unsupported type
- `401 Unauthorized` - Not authenticated
- `404 Not Found` - User not found (should not occur with valid token)
- `500 Internal Server Error` - Storage/IO failure

**Example Request** (using FormData):
```javascript
const formData = new FormData();
formData.append('file', avatarFile);

fetch('/api/v1/users/me/avatar', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`
    // Don't set Content-Type - browser sets it with boundary
  },
  body: formData
});
```

---

## Error Handling

All errors return a standardized `ErrorResponse` format:

```json
{
  "timestamp": "2025-06-01T12:34:56.789Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Detailed error message",
  "path": "/api/v1/users/me"
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Invalid payload, validation failure, or constraint violation |
| `401` | Unauthorized | Missing/invalid token or user inactive/deleted |
| `404` | Not Found | User not found (rare - should not occur with valid token) |
| `412` | Precondition Failed | Optimistic locking version mismatch |
| `500` | Internal Server Error | Storage/IO failure or unexpected error |

### Profile Update Errors

| Status | Message | Trigger |
| --- | --- | --- |
| `401` | `Not authenticated` | Authentication missing or token invalid |
| `401` | `User not found or inactive` | User account inactive or deleted |
| `400` | `Invalid preferences format` | `preferences` serialization failure |
| `400` | `preferences must be an object or null` | `preferences` is not a valid JSON object |
| `400` | `Too many preferences` | More than 50 keys in `preferences` |
| `400` | `Preference key too long` | Key exceeds 64 characters |
| `412` | `Version mismatch` | `If-Match` version doesn't match current version |

**Example (Version Mismatch)**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 412,
  "error": "Precondition Failed",
  "message": "Version mismatch. Current version is 5, but request specified 3.",
  "path": "/api/v1/users/me"
}
```

### Avatar Upload Errors

| Status | Message | Trigger |
| --- | --- | --- |
| `400` | `Avatar file is required` | No file in request |
| `400` | `File too large` | File exceeds 10 MB |
| `400` | `Image dimensions too large` | Dimensions exceed limits |
| `400` | `Unsupported image type. Allowed: PNG, JPEG` | Invalid file type (e.g., WEBP, GIF) |
| `400` | `Invalid image dimensions` | Non-positive width/height |
| `404` | `User <username> not found` | User not found (rare) |
| `500` | `Failed to read file: ...` | File read error |
| `500` | `Failed to store avatar: ...` | Storage/IO error |

**Example (File Too Large)**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "File too large. Maximum size is 10 MB.",
  "path": "/api/v1/users/me/avatar"
}
```

**Example (Unsupported Type)**:
```json
{
  "timestamp": "2025-06-01T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Unsupported image type: image/webp. Allowed: PNG, JPEG",
  "path": "/api/v1/users/me/avatar"
}
```

---

## Concurrency & Versioning

User profiles use optimistic locking to prevent concurrent update conflicts.

### How It Works

1. **Version Tracking**: Each profile has a `version` number that increments on every update
2. **ETag Header**: `GET` and `PATCH` responses include `ETag: "<version>"` header
3. **If-Match Header**: Include in `PATCH` requests to enforce version check
4. **Conflict Detection**: If version doesn't match, returns `412 Precondition Failed`

### Best Practices

**Recommended Flow** (with optimistic locking):
```javascript
// 1. Fetch profile and store ETag
const response = await fetch('/api/v1/users/me');
const etag = response.headers.get('ETag');
const profile = await response.json();

// 2. User makes changes...

// 3. Update with If-Match header
const updateResponse = await fetch('/api/v1/users/me', {
  method: 'PATCH',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json',
    'If-Match': etag  // Enforce version check
  },
  body: JSON.stringify(updates)
});

if (updateResponse.status === 412) {
  // Version mismatch - profile was updated by another session
  alert('Profile was updated elsewhere. Please reload and try again.');
}
```

**Simple Flow** (without optimistic locking):
```javascript
// Update without version check
const response = await fetch('/api/v1/users/me', {
  method: 'PATCH',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify(updates)
});
```

**Notes**:
- `If-Match` header is optional but recommended for multi-device scenarios
- Backend strips quotes from `If-Match` value and parses as number
- Non-numeric `If-Match` values are ignored (treated as missing)
- When omitted, updates proceed without version check (last write wins)

---

## Integration Guide

### Fetch User Profile

**Display user profile data**:

```javascript
const getUserProfile = async () => {
  const response = await fetch('/api/v1/users/me', {
    headers: {
      'Authorization': `Bearer ${token}`
    }
  });

  if (response.status === 401) {
    console.error('Not authenticated');
    redirectToLogin();
    return null;
  }

  if (!response.ok) {
    console.error('Failed to fetch profile');
    return null;
  }

  const profile = await response.json();
  const etag = response.headers.get('ETag');

  // Store ETag for later updates
  localStorage.setItem('profileETag', etag);

  return profile;
};

// Usage
const profile = await getUserProfile();
console.log(`Welcome, ${profile.displayName}!`);
```

---

### Update Profile

**Update profile with optimistic locking**:

```javascript
const updateProfile = async (updates) => {
  const etag = localStorage.getItem('profileETag');

  const headers = {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  };

  // Add If-Match header if we have cached ETag
  if (etag) {
    headers['If-Match'] = etag;
  }

  const response = await fetch('/api/v1/users/me', {
    method: 'PATCH',
    headers: headers,
    body: JSON.stringify(updates)
  });

  if (response.status === 412) {
    // Version mismatch - concurrent update
    alert('Profile was modified elsewhere. Please reload and try again.');
    
    // Reload fresh profile
    await getUserProfile();
    return null;
  }

  if (response.status === 400) {
    const error = await response.json();
    console.error('Validation error:', error.message);
    showValidationError(error.message);
    return null;
  }

  if (!response.ok) {
    console.error('Failed to update profile');
    return null;
  }

  const updatedProfile = await response.json();
  
  // Update stored ETag
  const newETag = response.headers.get('ETag');
  localStorage.setItem('profileETag', newETag);

  return updatedProfile;
};

// Usage - Update display name
await updateProfile({ displayName: 'New Name' });

// Update multiple fields
await updateProfile({
  displayName: 'Jane Doe',
  bio: 'Passionate educator',
  preferences: {
    theme: 'dark',
    language: 'en'
  }
});

// Clear bio
await updateProfile({ bio: null });
```

---

### Upload Avatar

**Upload avatar with progress tracking**:

```javascript
const uploadAvatar = async (file) => {
  // Validate file size client-side
  if (file.size > 10 * 1024 * 1024) {
    alert('File too large. Maximum size is 10 MB.');
    return null;
  }

  // Validate file type client-side
  const allowedTypes = ['image/png', 'image/jpeg'];
  if (!allowedTypes.includes(file.type)) {
    alert('Unsupported file type. Please upload PNG or JPEG.');
    return null;
  }

  const formData = new FormData();
  formData.append('file', file);

  try {
    const response = await fetch('/api/v1/users/me/avatar', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`
        // Don't set Content-Type - browser sets it with boundary
      },
      body: formData
    });

    if (response.status === 400) {
      const error = await response.json();
      alert(error.message);
      return null;
    }

    if (response.status === 500) {
      alert('Failed to upload avatar. Please try again later.');
      return null;
    }

    if (!response.ok) {
      alert('Failed to upload avatar');
      return null;
    }

    const result = await response.json();
    console.log('Avatar uploaded:', result.avatarUrl);

    // Update UI with new avatar URL
    return result.avatarUrl;
  } catch (error) {
    console.error('Upload error:', error);
    alert('Network error. Please check your connection.');
    return null;
  }
};

// Usage with file input
const handleFileSelect = async (event) => {
  const file = event.target.files[0];
  if (file) {
    const newAvatarUrl = await uploadAvatar(file);
    if (newAvatarUrl) {
      // Update UI
      updateAvatarDisplay(newAvatarUrl);
      
      // Optionally refresh full profile to sync version
      await getUserProfile();
    }
  }
};
```

---

### Profile Form Component

**React component for profile editing**:

```javascript
import React, { useState, useEffect } from 'react';

const ProfileForm = () => {
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [formData, setFormData] = useState({
    displayName: '',
    bio: '',
    preferences: {}
  });

  useEffect(() => {
    loadProfile();
  }, []);

  const loadProfile = async () => {
    setLoading(true);
    try {
      const data = await getUserProfile();
      setProfile(data);
      setFormData({
        displayName: data.displayName || '',
        bio: data.bio || '',
        preferences: data.preferences || {}
      });
    } catch (error) {
      console.error('Error loading profile:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);

    try {
      const updates = {};
      
      // Only include changed fields
      if (formData.displayName !== profile.displayName) {
        updates.displayName = formData.displayName;
      }
      if (formData.bio !== profile.bio) {
        updates.bio = formData.bio || null;
      }
      if (JSON.stringify(formData.preferences) !== JSON.stringify(profile.preferences)) {
        updates.preferences = formData.preferences;
      }

      if (Object.keys(updates).length === 0) {
        alert('No changes to save');
        return;
      }

      const updatedProfile = await updateProfile(updates);
      
      if (updatedProfile) {
        setProfile(updatedProfile);
        alert('Profile updated successfully');
      }
    } catch (error) {
      console.error('Error updating profile:', error);
      alert('Failed to update profile');
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div>Loading profile...</div>;

  return (
    <form onSubmit={handleSubmit} className="profile-form">
      <div className="form-group">
        <label>Username (read-only)</label>
        <input 
          type="text" 
          value={profile.username} 
          disabled 
        />
      </div>

      <div className="form-group">
        <label>Email (read-only)</label>
        <input 
          type="email" 
          value={profile.email} 
          disabled 
        />
      </div>

      <div className="form-group">
        <label>Display Name</label>
        <input
          type="text"
          value={formData.displayName}
          onChange={(e) => setFormData({
            ...formData,
            displayName: e.target.value
          })}
          maxLength={50}
        />
        <small>{formData.displayName.length}/50 characters</small>
      </div>

      <div className="form-group">
        <label>Bio</label>
        <textarea
          value={formData.bio}
          onChange={(e) => setFormData({
            ...formData,
            bio: e.target.value
          })}
          maxLength={500}
          rows={4}
        />
        <small>{formData.bio.length}/500 characters</small>
      </div>

      <button type="submit" disabled={saving}>
        {saving ? 'Saving...' : 'Save Changes'}
      </button>
    </form>
  );
};

export default ProfileForm;
```

---

### Avatar Upload Component

**React component for avatar management**:

```javascript
const AvatarUpload = ({ currentAvatarUrl, onAvatarChange }) => {
  const [uploading, setUploading] = useState(false);
  const [preview, setPreview] = useState(currentAvatarUrl);
  const fileInputRef = useRef(null);

  const handleFileChange = async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    // Show preview
    const reader = new FileReader();
    reader.onload = (e) => setPreview(e.target.result);
    reader.readAsDataURL(file);

    // Upload
    setUploading(true);
    try {
      const newAvatarUrl = await uploadAvatar(file);
      if (newAvatarUrl) {
        setPreview(newAvatarUrl);
        onAvatarChange?.(newAvatarUrl);
      } else {
        // Revert preview on error
        setPreview(currentAvatarUrl);
      }
    } catch (error) {
      console.error('Upload failed:', error);
      setPreview(currentAvatarUrl);
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="avatar-upload">
      <div className="avatar-preview">
        {preview ? (
          <img src={preview} alt="Avatar" />
        ) : (
          <div className="avatar-placeholder">No Avatar</div>
        )}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg"
        onChange={handleFileChange}
        style={{ display: 'none' }}
      />

      <button
        type="button"
        onClick={() => fileInputRef.current?.click()}
        disabled={uploading}
      >
        {uploading ? 'Uploading...' : 'Change Avatar'}
      </button>

      <small>PNG or JPEG, max 10 MB</small>
    </div>
  );
};
```

---

### Preferences Management

**Manage user preferences**:

```javascript
const PreferencesManager = {
  async getPreference(key, defaultValue = null) {
    const profile = await getUserProfile();
    return profile.preferences?.[key] ?? defaultValue;
  },

  async setPreference(key, value) {
    const profile = await getUserProfile();
    const updatedPreferences = {
      ...profile.preferences,
      [key]: value
    };

    return await updateProfile({
      preferences: updatedPreferences
    });
  },

  async setPreferences(newPreferences) {
    return await updateProfile({
      preferences: newPreferences
    });
  },

  async removePreference(key) {
    const profile = await getUserProfile();
    const updatedPreferences = { ...profile.preferences };
    delete updatedPreferences[key];

    return await updateProfile({
      preferences: updatedPreferences
    });
  }
};

// Usage
await PreferencesManager.setPreference('theme', 'dark');
await PreferencesManager.setPreference('language', 'en');

const theme = await PreferencesManager.getPreference('theme', 'light');
console.log('Current theme:', theme);
```

---

### Error Handling

**Comprehensive error handling**:

```javascript
const handleProfileError = async (operation) => {
  try {
    return await operation();
  } catch (error) {
    const status = error.status || 500;
    const errorData = await error.json?.() || error;

    switch (status) {
      case 400:
        console.error('Validation error:', errorData.message);
        showNotification(errorData.message, 'error');
        break;

      case 401:
        console.error('Authentication required');
        showNotification('Please log in to continue', 'info');
        redirectToLogin();
        break;

      case 404:
        console.error('Profile not found');
        showNotification('Profile not found', 'error');
        break;

      case 412:
        console.error('Version conflict');
        showNotification(
          'Profile was updated elsewhere. Refreshing...',
          'warning'
        );
        // Reload profile
        await getUserProfile();
        break;

      case 500:
        console.error('Server error:', errorData.message);
        showNotification(
          'An error occurred. Please try again later.',
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
const safelyUpdateProfile = async (updates) => {
  return await handleProfileError(() => updateProfile(updates));
};
```

---

## Security Considerations

### Authentication & Authorization

1. **Token Management**:
   - Store JWT tokens securely (HttpOnly cookies recommended)
   - Include token in all requests via `Authorization: Bearer` header
   - Handle token expiry with automatic refresh
   - Clear tokens on logout

2. **Self-Service Only**:
   - Users can only access their own profile
   - No cross-user access via these endpoints
   - Admin operations handled by separate APIs

3. **Session Validation**:
   - Backend validates token on every request
   - Returns `401` for invalid/expired tokens
   - Handles inactive/deleted user accounts gracefully

### Data Privacy

1. **Sensitive Information**:
   - Email addresses are returned but should be displayed carefully
   - Consider masking email in certain UI contexts
   - Don't expose passwords (never returned by API)

2. **Avatar URLs**:
   - Avatar URLs are public and accessible without authentication
   - Don't upload sensitive information as avatar
   - Old avatars are automatically deleted on replacement

3. **Preferences**:
   - Preferences are user-specific and private
   - Stored as JSON - validate structure before saving
   - Preserve unknown keys to maintain forward compatibility

### Input Validation

1. **Client-Side Validation**:
   - Enforce max lengths (50 chars for displayName, 500 for bio)
   - Validate file size/type before upload (10 MB, PNG/JPEG only)
   - Sanitize inputs to prevent XSS (backend also sanitizes)

2. **Server-Side Validation**:
   - Backend enforces all constraints
   - HTML tags are stripped from displayName and bio
   - Preferences structure validated (max 50 keys, max 64 chars per key)

3. **File Upload Safety**:
   - Validate file type and size client-side for UX
   - Backend validates with Apache Tika (content-based detection)
   - Images resized and converted to PNG (removes potential exploits)

### Concurrency Control

1. **Optimistic Locking**:
   - Use `If-Match` header to prevent lost updates
   - Handle `412 Precondition Failed` by reloading profile
   - Prompt user to re-apply changes after refresh

2. **Multi-Device Scenarios**:
   - ETag ensures consistency across devices
   - Last write wins if `If-Match` not provided
   - Consider displaying "Profile updated on another device" notifications

3. **Version Tracking**:
   - Always store ETag from responses
   - Include in subsequent updates
   - Handle version mismatches gracefully

### Performance & Caching

1. **No Caching**:
   - Profile responses include `Cache-Control: no-store`
   - Always fetch fresh data from server
   - Don't cache sensitive profile data in localStorage

2. **ETag Usage**:
   - Store ETag for optimistic locking, not caching
   - Update stored ETag after each successful update
   - Clear ETag on logout

3. **Avatar Upload**:
   - Show progress indicator during upload
   - Validate file size/type before starting upload
   - Compress images client-side if possible (optional)

### Best Practices

1. **Form Handling**:
   - Disable submit button during save to prevent double-submission
   - Show loading states during operations
   - Validate inputs before submission
   - Display server validation errors clearly

2. **User Experience**:
   - Always refresh UI with response data (server may sanitize)
   - Show character counts for text fields
   - Provide immediate feedback on validation errors
   - Handle network failures gracefully

3. **Error Recovery**:
   - Implement retry logic for 500 errors
   - Handle version conflicts by reloading and preserving changes
   - Display user-friendly error messages
   - Log errors for debugging (without exposing sensitive data)

4. **Accessibility**:
   - Provide alt text for avatar images
   - Use semantic HTML for forms
   - Ensure keyboard navigation works
   - Support screen readers

5. **Testing**:
   - Test with different file types and sizes
   - Test concurrent updates from multiple tabs
   - Test token expiry scenarios
   - Test with various preference structures