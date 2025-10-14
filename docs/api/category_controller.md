# Category Controller API Reference

Complete frontend integration guide for `/api/v1/categories` REST endpoints. This document is self-contained and includes all DTOs, validation rules, and error semantics needed to integrate category management features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/categories`
* **Authentication**: 
  - Read operations: No authentication required (public)
  - Write operations: Requires JWT Bearer token with `ROLE_ADMIN`
* **Authorization**: Role-based. Admin role required for create, update, delete operations.
* **Content-Type**: `application/json` for requests and responses (except `204` responses)
* **Error Format**: All errors return `ErrorResponse` object

---

## Authorization Matrix

Category endpoints use a mixed authorization model - public reads, admin-only writes.

| Capability | Endpoint(s) | Authorization Rule | Notes |
| --- | --- | --- | --- |
| **List categories** | `GET /` | Public (no auth) | Anyone can view categories |
| **Get category** | `GET /{categoryId}` | Public (no auth) | Anyone can view individual category |
| **Create category** | `POST /` | `ROLE_ADMIN` required | Admin users only |
| **Update category** | `PATCH /{categoryId}` | `ROLE_ADMIN` required | Admin users only |
| **Delete category** | `DELETE /{categoryId}` | `ROLE_ADMIN` required | Admin users only |

**Public Access**:
- Category listing and individual category viewing is public
- No authentication needed for read operations
- Useful for category selection in quiz creation

**Admin Control**:
- All write operations require admin role
- Authenticated admin users identified via JWT token
- Username extracted from authentication for audit logging

---

## Request DTOs

### CreateCategoryRequest

**Used by**: `POST /categories`

Create a new category in the system.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | string | Yes | 3-100 characters | Category name (must be unique) |
| `description` | string | No | Max 1000 characters | Category description |

**Example**:
```json
{
  "name": "Science",
  "description": "All science-related quizzes including physics, chemistry, and biology"
}
```

**Validation Rules**:
- `name` is required and must be 3-100 characters
- `name` must be unique across all categories (case-sensitive)
- `description` is optional, max 1000 characters
- Empty string `""` is allowed for description

---

### UpdateCategoryRequest

**Used by**: `PATCH /categories/{categoryId}`

Update an existing category's information.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `name` | string | Yes | 3-100 characters | Updated category name (must be unique) |
| `description` | string | No | Max 1000 characters | Updated description |

**Example**:
```json
{
  "name": "History & Culture",
  "description": "Historical events, cultural topics, and world civilizations"
}
```

**Notes**:
- Both fields must be provided (not a partial update)
- `name` uniqueness checked against all categories except current one
- Changing to an existing category name triggers `409 Conflict`

---

## Response DTOs

### CategoryDto

**Returned by**: All category endpoints

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Category unique identifier |
| `name` | string | Category name |
| `description` | string (nullable) | Category description |

**Example**:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Technology",
  "description": "Software, hardware, and IT topics"
}
```

---

### Page\<CategoryDto\>

**Returned by**: `GET /categories`

Standard Spring Data pagination wrapper.

| Field | Type | Description |
| --- | --- | --- |
| `content` | array of `CategoryDto` | Page of categories |
| `totalElements` | integer | Total number of categories |
| `totalPages` | integer | Total number of pages |
| `number` | integer | Current page number (0-indexed) |
| `size` | integer | Page size |
| `numberOfElements` | integer | Number of elements in current page |
| `first` | boolean | Whether this is the first page |
| `last` | boolean | Whether this is the last page |
| `sort` | object | Sort information |

**Example**:
```json
{
  "content": [
    {
      "id": "uuid-1",
      "name": "Mathematics",
      "description": "Math and arithmetic topics"
    },
    {
      "id": "uuid-2",
      "name": "Science",
      "description": "Scientific topics"
    }
  ],
  "totalElements": 25,
  "totalPages": 2,
  "number": 0,
  "size": 20,
  "numberOfElements": 20,
  "first": true,
  "last": false,
  "sort": {
    "sorted": true,
    "unsorted": false,
    "empty": false
  },
  "empty": false
}
```

---

### CreateCategoryResponse

**Returned by**: `POST /categories`

| Field | Type | Description |
| --- | --- | --- |
| `categoryId` | UUID | Newly created category ID |

**Example**:
```json
{
  "categoryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

---

## Endpoints

### 1. List Categories

```
GET /api/v1/categories
```

**Authorization**: Public (no authentication required)

**Query Parameters**:
- `page` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 20
- `sort` (string, optional) - Sort specification, default: "name,asc"

**Example URLs**:
```
GET /api/v1/categories
GET /api/v1/categories?page=0&size=10&sort=name,asc
GET /api/v1/categories?page=1&size=50&sort=name,desc
```

**Success Response**: `200 OK` - `Page<CategoryDto>`

**Example Response**:
```json
{
  "content": [
    {
      "id": "cat-uuid-1",
      "name": "General Knowledge",
      "description": "Broad range of general topics"
    },
    {
      "id": "cat-uuid-2",
      "name": "Science",
      "description": "Physics, chemistry, biology topics"
    }
  ],
  "totalElements": 15,
  "totalPages": 1,
  "number": 0,
  "size": 20,
  "first": true,
  "last": true
}
```

**Error Responses**:
- `400` - Invalid pagination parameters

**Notes**:
- Default sort is by name ascending
- Public endpoint - no authentication needed
- Use for category dropdown in quiz creation

---

### 2. Get Category by ID

```
GET /api/v1/categories/{categoryId}
```

**Authorization**: Public (no authentication required)

**Path Parameters**:
- `{categoryId}` - Category UUID

**Success Response**: `200 OK` - `CategoryDto`

**Example Response**:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Technology",
  "description": "Computer science, programming, and IT topics"
}
```

**Error Responses**:
- `404` - Category not found

---

### 3. Create Category

```
POST /api/v1/categories
```

**Required Role**: `ROLE_ADMIN`

**Request Body**: `CreateCategoryRequest`
```json
{
  "name": "Mathematics",
  "description": "Algebra, geometry, calculus, and statistics"
}
```

**Success Response**: `201 Created`
```json
{
  "categoryId": "newly-created-uuid"
}
```

**Error Responses**:
- `400` - Validation error (name too short/long, description too long)
- `401` - Unauthorized (missing or invalid token)
- `403` - Forbidden (not an admin)
- `409` - Conflict (duplicate category name)

**Example Errors**:

**Validation Error**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "details": [
    "name: Category name length must be between 3 and 100 characters"
  ]
}
```

**Duplicate Name**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 409,
  "error": "Conflict",
  "details": [
    "Category with name 'Science' already exists"
  ]
}
```

---

### 4. Update Category

```
PATCH /api/v1/categories/{categoryId}
```

**Required Role**: `ROLE_ADMIN`

**Path Parameters**:
- `{categoryId}` - Category UUID

**Request Body**: `UpdateCategoryRequest`
```json
{
  "name": "Updated Category Name",
  "description": "Updated description with more details"
}
```

**Success Response**: `200 OK` - `CategoryDto`
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Updated Category Name",
  "description": "Updated description with more details"
}
```

**Error Responses**:
- `400` - Validation error
- `401` - Unauthorized
- `403` - Forbidden (not an admin)
- `404` - Category not found
- `409` - Conflict (duplicate name)

**Notes**:
- Both fields must be provided
- Can update to same name (no conflict with self)
- Cannot update to another category's name

---

### 5. Delete Category

```
DELETE /api/v1/categories/{categoryId}
```

**Required Role**: `ROLE_ADMIN`

**Path Parameters**:
- `{categoryId}` - Category UUID

**Success Response**: `204 No Content`

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden (not an admin)
- `404` - Category not found

**Notes**:
- Deletion is permanent
- Quizzes associated with this category may be affected
- Consider implementing soft deletes in production

---

## Error Handling

### ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "details": [
    "name: Category name length must be between 3 and 100 characters"
  ]
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `200` | OK | Successful GET or PATCH |
| `201` | Created | Successful POST |
| `204` | No Content | Successful DELETE |
| `400` | Bad Request | Validation errors, malformed JSON |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Not an admin user |
| `404` | Not Found | Category doesn't exist |
| `409` | Conflict | Duplicate category name |
| `500` | Internal Server Error | Unexpected server error |

### Common Error Scenarios

**Invalid Name Length**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["name: Category name length must be between 3 and 100 characters"]
}
```

**Description Too Long**:
```json
{
  "status": 400,
  "error": "Bad Request",
  "details": ["description: Category description must be less than 1000 characters"]
}
```

**Duplicate Category**:
```json
{
  "status": 409,
  "error": "Conflict",
  "details": ["Category with name 'Science' already exists"]
}
```

**Category Not Found**:
```json
{
  "status": 404,
  "error": "Not Found",
  "details": ["Category 3fa85f64-5717-4562-b3fc-2c963f66afa6 not found"]
}
```

**Not Admin**:
```json
{
  "status": 403,
  "error": "Forbidden",
  "details": ["Access Denied"]
}
```

---

## Integration Guide

### Loading Categories for Dropdown

**Public category list for quiz creation**:
```javascript
const loadCategories = async () => {
  try {
    // No auth needed for public category list
    const response = await fetch('/api/v1/categories?size=100&sort=name,asc');
    
    if (!response.ok) {
      console.error('Failed to load categories');
      return [];
    }

    const data = await response.json();
    return data.content;
  } catch (error) {
    console.error('Error loading categories:', error);
    return [];
  }
};

// Usage in React
const CategoryDropdown = () => {
  const [categories, setCategories] = useState([]);

  useEffect(() => {
    loadCategories().then(setCategories);
  }, []);

  return (
    <select name="category">
      <option value="">Select a category...</option>
      {categories.map(cat => (
        <option key={cat.id} value={cat.id}>
          {cat.name}
        </option>
      ))}
    </select>
  );
};
```

---

### Admin Category Management

**Complete CRUD implementation**:
```javascript
class CategoryManager {
  constructor(adminToken) {
    this.token = adminToken;
    this.baseUrl = '/api/v1/categories';
  }

  async list(page = 0, size = 20, sort = 'name,asc') {
    const url = `${this.baseUrl}?page=${page}&size=${size}&sort=${sort}`;
    const response = await fetch(url);
    
    if (!response.ok) {
      throw new Error('Failed to load categories');
    }
    
    return await response.json();
  }

  async get(categoryId) {
    const response = await fetch(`${this.baseUrl}/${categoryId}`);
    
    if (response.status === 404) {
      throw new Error('Category not found');
    }
    
    return await response.json();
  }

  async create(name, description = '') {
    const response = await fetch(this.baseUrl, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, description })
    });

    if (!response.ok) {
      const error = await response.json();
      
      if (response.status === 409) {
        throw new Error(`Category "${name}" already exists`);
      }
      
      throw new Error(error.details?.[0] || 'Failed to create category');
    }

    const { categoryId } = await response.json();
    return categoryId;
  }

  async update(categoryId, name, description = '') {
    const response = await fetch(`${this.baseUrl}/${categoryId}`, {
      method: 'PATCH',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, description })
    });

    if (!response.ok) {
      const error = await response.json();
      
      if (response.status === 404) {
        throw new Error('Category not found');
      }
      
      if (response.status === 409) {
        throw new Error(`Category "${name}" already exists`);
      }
      
      throw new Error(error.details?.[0] || 'Failed to update category');
    }

    return await response.json();
  }

  async delete(categoryId) {
    const response = await fetch(`${this.baseUrl}/${categoryId}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${this.token}`
      }
    });

    if (!response.ok && response.status !== 204) {
      const error = await response.json();
      
      if (response.status === 404) {
        throw new Error('Category not found');
      }
      
      throw new Error(error.details?.[0] || 'Failed to delete category');
    }

    return true;
  }
}

// Usage
const manager = new CategoryManager(adminToken);

// Create
const newCatId = await manager.create('Programming', 'Software development topics');

// Update
const updated = await manager.update(newCatId, 'Software Development', 'Code and apps');

// Delete
await manager.delete(newCatId);
```

---

### Admin Panel with CRUD

**Complete React admin component**:
```javascript
const CategoryAdminPanel = ({ adminToken }) => {
  const [categories, setCategories] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [editingId, setEditingId] = useState(null);
  const [formData, setFormData] = useState({ name: '', description: '' });
  const [error, setError] = useState(null);

  const loadCategories = async () => {
    try {
      const response = await fetch(`/api/v1/categories?page=${page}&size=20&sort=name,asc`);
      const data = await response.json();
      setCategories(data.content);
      setTotalPages(data.totalPages);
    } catch (err) {
      setError('Failed to load categories');
    }
  };

  useEffect(() => {
    loadCategories();
  }, [page]);

  const handleCreate = async (e) => {
    e.preventDefault();
    setError(null);

    try {
      const response = await fetch('/api/v1/categories', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${adminToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(formData)
      });

      if (!response.ok) {
        const error = await response.json();
        if (response.status === 409) {
          setError('A category with this name already exists');
        } else {
          setError(error.details?.[0] || 'Failed to create category');
        }
        return;
      }

      setFormData({ name: '', description: '' });
      loadCategories();
      alert('Category created successfully');
    } catch (err) {
      setError('Network error occurred');
    }
  };

  const handleUpdate = async (categoryId) => {
    setError(null);

    try {
      const response = await fetch(`/api/v1/categories/${categoryId}`, {
        method: 'PATCH',
        headers: {
          'Authorization': `Bearer ${adminToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(formData)
      });

      if (!response.ok) {
        const error = await response.json();
        setError(error.details?.[0] || 'Failed to update category');
        return;
      }

      setEditingId(null);
      setFormData({ name: '', description: '' });
      loadCategories();
      alert('Category updated successfully');
    } catch (err) {
      setError('Network error occurred');
    }
  };

  const handleDelete = async (categoryId, categoryName) => {
    if (!confirm(`Delete category "${categoryName}"? This cannot be undone.`)) {
      return;
    }

    try {
      const response = await fetch(`/api/v1/categories/${categoryId}`, {
        method: 'DELETE',
        headers: {
          'Authorization': `Bearer ${adminToken}`
        }
      });

      if (!response.ok && response.status !== 204) {
        const error = await response.json();
        setError(error.details?.[0] || 'Failed to delete category');
        return;
      }

      loadCategories();
      alert('Category deleted successfully');
    } catch (err) {
      setError('Network error occurred');
    }
  };

  const startEdit = (category) => {
    setEditingId(category.id);
    setFormData({ name: category.name, description: category.description || '' });
  };

  return (
    <div className="category-admin">
      <h2>Category Management</h2>

      {error && <div className="error">{error}</div>}

      {/* Create Form */}
      <form onSubmit={handleCreate} className="create-form">
        <h3>Create New Category</h3>
        <input
          type="text"
          placeholder="Category name (3-100 chars)"
          value={formData.name}
          onChange={(e) => setFormData({...formData, name: e.target.value})}
          maxLength={100}
          required
        />
        <textarea
          placeholder="Description (optional, max 1000 chars)"
          value={formData.description}
          onChange={(e) => setFormData({...formData, description: e.target.value})}
          maxLength={1000}
        />
        <p>{formData.name.length}/100 | {formData.description.length}/1000</p>
        <button type="submit">Create Category</button>
      </form>

      {/* Category List */}
      <div className="category-list">
        <h3>Existing Categories</h3>
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {categories.map(cat => (
              <tr key={cat.id}>
                {editingId === cat.id ? (
                  <>
                    <td>
                      <input
                        value={formData.name}
                        onChange={(e) => setFormData({...formData, name: e.target.value})}
                        maxLength={100}
                      />
                    </td>
                    <td>
                      <textarea
                        value={formData.description}
                        onChange={(e) => setFormData({...formData, description: e.target.value})}
                        maxLength={1000}
                      />
                    </td>
                    <td>
                      <button onClick={() => handleUpdate(cat.id)}>Save</button>
                      <button onClick={() => setEditingId(null)}>Cancel</button>
                    </td>
                  </>
                ) : (
                  <>
                    <td>{cat.name}</td>
                    <td>{cat.description || '-'}</td>
                    <td>
                      <button onClick={() => startEdit(cat)}>Edit</button>
                      <button onClick={() => handleDelete(cat.id, cat.name)}>Delete</button>
                    </td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>

        {/* Pagination */}
        <div className="pagination">
          <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>
            Previous
          </button>
          <span>Page {page + 1} of {totalPages}</span>
          <button onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>
            Next
          </button>
        </div>
      </div>
    </div>
  );
};
```

---

### Simple Category Selector

**Basic category dropdown component**:
```javascript
const CategorySelector = ({ value, onChange, includeEmpty = true }) => {
  const [categories, setCategories] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      try {
        const response = await fetch('/api/v1/categories?size=100&sort=name,asc');
        const data = await response.json();
        setCategories(data.content);
      } catch (error) {
        console.error('Failed to load categories:', error);
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

  if (loading) {
    return <select disabled><option>Loading categories...</option></select>;
  }

  return (
    <select value={value || ''} onChange={(e) => onChange(e.target.value || null)}>
      {includeEmpty && <option value="">No category</option>}
      {categories.map(cat => (
        <option key={cat.id} value={cat.id}>
          {cat.name}
        </option>
      ))}
    </select>
  );
};

// Usage
<CategorySelector 
  value={selectedCategoryId} 
  onChange={setCategoryId}
  includeEmpty={true}
/>
```

---

### Category Card Grid

**Visual category browser**:
```javascript
const CategoryGrid = ({ onSelect }) => {
  const [categories, setCategories] = useState([]);

  useEffect(() => {
    fetch('/api/v1/categories?size=50&sort=name,asc')
      .then(res => res.json())
      .then(data => setCategories(data.content));
  }, []);

  return (
    <div className="category-grid">
      {categories.map(category => (
        <div 
          key={category.id} 
          className="category-card"
          onClick={() => onSelect(category.id)}
        >
          <h3>{category.name}</h3>
          {category.description && (
            <p className="description">{category.description}</p>
          )}
        </div>
      ))}
    </div>
  );
};
```

---

### Validation Helper

**Client-side validation before submission**:
```javascript
const validateCategory = (name, description) => {
  const errors = [];

  // Name validation
  if (!name || name.trim().length === 0) {
    errors.push('Category name is required');
  } else if (name.length < 3) {
    errors.push('Category name must be at least 3 characters');
  } else if (name.length > 100) {
    errors.push('Category name must not exceed 100 characters');
  }

  // Description validation
  if (description && description.length > 1000) {
    errors.push('Description must not exceed 1000 characters');
  }

  return {
    valid: errors.length === 0,
    errors: errors
  };
};

// Usage
const handleSubmit = async () => {
  const validation = validateCategory(formData.name, formData.description);
  
  if (!validation.valid) {
    setErrors(validation.errors);
    return;
  }

  // Proceed with API call
  await createCategory(formData);
};
```

---

### Duplicate Name Handling

**Handle duplicate name conflicts gracefully**:
```javascript
const createCategoryWithDuplicateCheck = async (name, description) => {
  try {
    const response = await fetch('/api/v1/categories', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${adminToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, description })
    });

    if (response.status === 409) {
      // Duplicate name - offer to edit existing
      const existingCategories = await fetch(
        `/api/v1/categories?size=100`
      ).then(r => r.json());
      
      const existing = existingCategories.content.find(
        c => c.name.toLowerCase() === name.toLowerCase()
      );

      if (existing) {
        const useExisting = confirm(
          `Category "${name}" already exists. Would you like to edit it instead?`
        );
        
        if (useExisting) {
          return existing.id; // Return existing ID
        }
      }
      
      throw new Error('Category name already in use. Please choose a different name.');
    }

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Failed to create category');
    }

    const { categoryId } = await response.json();
    return categoryId;
  } catch (error) {
    console.error('Create category error:', error);
    throw error;
  }
};
```

---

### Pagination Controls

**Paginated category browser**:
```javascript
const PaginatedCategoryList = () => {
  const [categories, setCategories] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size, setSize] = useState(20);
  const [sortField, setSortField] = useState('name');
  const [sortDir, setSortDir] = useState('asc');

  const loadPage = async () => {
    const url = `/api/v1/categories?page=${page}&size=${size}&sort=${sortField},${sortDir}`;
    const response = await fetch(url);
    const data = await response.json();
    
    setCategories(data.content);
    setTotalPages(data.totalPages);
  };

  useEffect(() => {
    loadPage();
  }, [page, size, sortField, sortDir]);

  const toggleSort = (field) => {
    if (sortField === field) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDir('asc');
    }
    setPage(0); // Reset to first page on sort change
  };

  return (
    <div>
      <div className="controls">
        <label>
          Per page:
          <select value={size} onChange={(e) => setSize(Number(e.target.value))}>
            <option value={10}>10</option>
            <option value={20}>20</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
          </select>
        </label>
      </div>

      <table>
        <thead>
          <tr>
            <th onClick={() => toggleSort('name')} style={{cursor: 'pointer'}}>
              Name {sortField === 'name' && (sortDir === 'asc' ? '↑' : '↓')}
            </th>
            <th>Description</th>
          </tr>
        </thead>
        <tbody>
          {categories.map(cat => (
            <tr key={cat.id}>
              <td>{cat.name}</td>
              <td>{cat.description || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="pagination">
        <button 
          onClick={() => setPage(p => p - 1)} 
          disabled={page === 0}
        >
          Previous
        </button>
        <span>Page {page + 1} of {totalPages}</span>
        <button 
          onClick={() => setPage(p => p + 1)} 
          disabled={page >= totalPages - 1}
        >
          Next
        </button>
      </div>
    </div>
  );
};
```

---

### Error Handling

**Comprehensive error handling**:
```javascript
const handleCategoryOperation = async (operation, ...args) => {
  try {
    const result = await operation(...args);
    return { success: true, data: result };
  } catch (error) {
    // Parse error response
    let message = error.message;
    let userMessage = message;

    if (error.response) {
      const errorData = await error.response.json();
      
      switch (error.response.status) {
        case 400:
          userMessage = errorData.details?.[0] || 'Validation error';
          break;
        case 401:
          userMessage = 'Please log in to continue';
          // Redirect to login
          window.location.href = '/login';
          break;
        case 403:
          userMessage = 'Admin access required';
          break;
        case 404:
          userMessage = 'Category not found';
          break;
        case 409:
          userMessage = 'Category name already exists. Please choose a different name.';
          break;
        case 500:
          userMessage = 'Server error. Please try again later.';
          break;
        default:
          userMessage = 'An error occurred';
      }
    }

    return { success: false, error: userMessage };
  }
};

// Usage
const result = await handleCategoryOperation(
  createCategory, 
  'New Category', 
  'Description'
);

if (result.success) {
  console.log('Category created:', result.data);
} else {
  showError(result.error);
}
```

---

## Security Considerations

### Role-Based Access Control

1. **Public Reads**: List and get operations are public (no authentication needed)
2. **Admin Writes**: Create, update, delete require `ROLE_ADMIN`
3. **Token Validation**: Admin endpoints validate JWT token and role
4. **Audit Logging**: Username logged for all admin operations

### Input Validation

1. **Client-Side**: Validate before submission to improve UX
2. **Server-Side**: All validation enforced server-side
3. **Sanitization**: Sanitize input to prevent injection
4. **Length Limits**: Enforce character limits in UI

### Data Integrity

1. **Unique Names**: System enforces unique category names
2. **Case Sensitivity**: Name uniqueness is case-sensitive
3. **Referential Integrity**: Deleting categories may affect quizzes
4. **Atomicity**: Operations are atomic

### Best Practices

**Frontend Implementation**:
- Cache category list for dropdown (changes infrequently)
- Validate input client-side before submission
- Handle 409 conflicts with clear messaging
- Show character counters for name and description
- Implement optimistic UI updates
- Provide feedback for all operations
- Use HTTPS for admin operations

**Admin Panel**:
- Restrict admin features to users with `ROLE_ADMIN`
- Confirm before deleting categories
- Show impact (number of quizzes using category)
- Implement undo for accidental changes
- Log all admin actions client-side

**Error Handling**:
- Display validation errors clearly
- Handle duplicate name conflicts gracefully
- Show user-friendly error messages
- Implement retry for 500 errors
- Handle network failures

**Performance**:
- Cache categories list (low mutation rate)
- Use appropriate page sizes
- Implement infinite scroll for large lists
- Debounce search inputs
- Lazy load details

**Testing**:
- Test without authentication (public endpoints)
- Test with non-admin users (should get 403)
- Test with admin users (should succeed)
- Test duplicate name handling
- Verify validation rules
- Test pagination edge cases

---

## Common Use Cases

### 1. Public Category Browser

```javascript
const PublicCategoryBrowser = ({ onSelectCategory }) => {
  const [categories, setCategories] = useState([]);

  useEffect(() => {
    // No auth needed - public endpoint
    fetch('/api/v1/categories?size=100&sort=name,asc')
      .then(res => res.json())
      .then(data => setCategories(data.content))
      .catch(err => console.error('Failed to load categories:', err));
  }, []);

  return (
    <div className="category-browser">
      <h2>Browse by Category</h2>
      <div className="category-tiles">
        {categories.map(cat => (
          <div 
            key={cat.id} 
            className="tile"
            onClick={() => onSelectCategory(cat.id)}
          >
            <h3>{cat.name}</h3>
            <p>{cat.description}</p>
          </div>
        ))}
      </div>
    </div>
  );
};
```

---

### 2. Category Filter for Quiz Search

```javascript
const QuizSearchWithCategory = () => {
  const [categories, setCategories] = useState([]);
  const [selectedCategory, setSelectedCategory] = useState(null);

  useEffect(() => {
    fetch('/api/v1/categories?size=100')
      .then(res => res.json())
      .then(data => setCategories(data.content));
  }, []);

  const searchQuizzes = (categoryId) => {
    const url = categoryId 
      ? `/api/v1/quizzes?categoryId=${categoryId}`
      : `/api/v1/quizzes`;
    
    // Fetch and display quizzes
  };

  return (
    <div>
      <select onChange={(e) => {
        const catId = e.target.value || null;
        setSelectedCategory(catId);
        searchQuizzes(catId);
      }}>
        <option value="">All Categories</option>
        {categories.map(cat => (
          <option key={cat.id} value={cat.id}>
            {cat.name}
          </option>
        ))}
      </select>
    </div>
  );
};
```

---

### 3. Admin Quick Actions

```javascript
const CategoryQuickActions = ({ adminToken }) => {
  const quickCreateCategory = async (name) => {
    try {
      const response = await fetch('/api/v1/categories', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${adminToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ name, description: '' })
      });

      if (response.status === 409) {
        alert('Category already exists');
        return null;
      }

      if (!response.ok) {
        alert('Failed to create category');
        return null;
      }

      const { categoryId } = await response.json();
      return categoryId;
    } catch (error) {
      console.error('Create error:', error);
      return null;
    }
  };

  return (
    <div className="quick-actions">
      <h3>Quick Create</h3>
      <button onClick={() => quickCreateCategory('Programming')}>
        + Programming
      </button>
      <button onClick={() => quickCreateCategory('Mathematics')}>
        + Mathematics
      </button>
      <button onClick={() => quickCreateCategory('Science')}>
        + Science
      </button>
    </div>
  );
};
```

---

## Troubleshooting

### Common Issues

**1. Cannot Create Category (403 Forbidden)**
- **Cause**: User doesn't have `ROLE_ADMIN`
- **Solution**: Ensure admin role is assigned to user
- **Detection**: Check JWT token payload for roles

**2. Duplicate Name Error (409)**
- **Cause**: Category with same name already exists
- **Solution**: Choose different name or update existing category
- **Prevention**: Check existing categories before creating

**3. Validation Error (400)**
- **Cause**: Name too short/long or description too long
- **Solution**: Fix validation issues
- **Prevention**: Client-side validation with character counters

**4. Category Not Found (404)**
- **Cause**: Category was deleted or ID is invalid
- **Solution**: Refresh category list
- **Prevention**: Validate UUID format before requests

**5. Public Endpoints Not Loading**
- **Cause**: Network issue or server down
- **Solution**: Check network connectivity
- **Fallback**: Show cached categories if available

### Debug Checklist

- [ ] For admin ops: Valid admin token provided
- [ ] For admin ops: User has `ROLE_ADMIN` role
- [ ] Category name is 3-100 characters
- [ ] Description is ≤ 1000 characters (if provided)
- [ ] Valid UUID format for category ID
- [ ] Network connection stable
- [ ] HTTPS used for admin operations
- [ ] CORS configured if calling from different domain

---

## API Design Notes

### Why Public Reads?

Categories are designed to be publicly viewable because:
- Needed for quiz browsing by non-authenticated users
- Used in quiz creation forms
- Enable category-based quiz discovery
- Low sensitivity data (just names/descriptions)

### Admin-Only Writes

Write operations restricted to admins because:
- Prevents category proliferation
- Ensures category quality and consistency
- Maintains organized taxonomy
- Prevents spam/abuse

### Future Enhancements

Potential future features:
- Category icons/images
- Category hierarchy (parent/child categories)
- Quiz count per category
- Popular categories ranking
- Category search functionality
- Soft delete with restore
- Category merge operations
- Bulk operations

---

## Implementation Examples

### Complete Category Management Module

```javascript
// category-service.js
export class CategoryService {
  constructor(baseUrl = '/api/v1/categories') {
    this.baseUrl = baseUrl;
  }

  // Public methods
  async list(page = 0, size = 20, sort = 'name,asc') {
    const url = `${this.baseUrl}?page=${page}&size=${size}&sort=${sort}`;
    const response = await fetch(url);
    return await response.json();
  }

  async getById(id) {
    const response = await fetch(`${this.baseUrl}/${id}`);
    if (response.status === 404) {
      throw new Error('Category not found');
    }
    return await response.json();
  }

  // Admin methods
  async create(name, description, adminToken) {
    const response = await fetch(this.baseUrl, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${adminToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, description: description || '' })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Create failed');
    }

    return await response.json();
  }

  async update(id, name, description, adminToken) {
    const response = await fetch(`${this.baseUrl}/${id}`, {
      method: 'PATCH',
      headers: {
        'Authorization': `Bearer ${adminToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ name, description: description || '' })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Update failed');
    }

    return await response.json();
  }

  async delete(id, adminToken) {
    const response = await fetch(`${this.baseUrl}/${id}`, {
      method: 'DELETE',
      headers: {
        'Authorization': `Bearer ${adminToken}`
      }
    });

    if (!response.ok && response.status !== 204) {
      const error = await response.json();
      throw new Error(error.details?.[0] || 'Delete failed');
    }

    return true;
  }
}

// Usage
const categoryService = new CategoryService();

// Public access
const categories = await categoryService.list();

// Admin access
const newId = await categoryService.create('New Category', 'Description', adminToken);
await categoryService.update(newId, 'Updated Name', 'New description', adminToken);
await categoryService.delete(newId, adminToken);
```

---

### TypeScript Interface Definitions

```typescript
// For TypeScript projects
export interface CategoryDto {
  id: string; // UUID
  name: string;
  description: string | null;
}

export interface CreateCategoryRequest {
  name: string; // 3-100 characters
  description?: string; // max 1000 characters
}

export interface UpdateCategoryRequest {
  name: string; // 3-100 characters
  description?: string; // max 1000 characters
}

export interface CreateCategoryResponse {
  categoryId: string; // UUID
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  numberOfElements: number;
  first: boolean;
  last: boolean;
  sort: {
    sorted: boolean;
    unsorted: boolean;
    empty: boolean;
  };
  empty: boolean;
}

export interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  details: string[];
}
```

---

## Best Practices Summary

### For Public Endpoints
1. **No Authentication**: Don't send auth headers (not needed)
2. **Caching**: Cache categories list locally (changes infrequently)
3. **Refresh Strategy**: Refresh on user action or periodically
4. **Error Fallback**: Show cached data if network fails

### For Admin Endpoints
1. **Token Management**: Secure token storage and refresh
2. **Validation**: Validate client-side before submission
3. **Confirmation**: Confirm destructive actions (delete)
4. **Feedback**: Provide immediate visual feedback
5. **Error Display**: Show user-friendly error messages

### General
1. **Character Counters**: Show real-time character counts
2. **Uniqueness Check**: Warn about potential duplicates
3. **Optimistic Updates**: Update UI immediately, rollback on error
4. **Loading States**: Show loading indicators for async operations
5. **Empty States**: Handle empty category lists gracefully

