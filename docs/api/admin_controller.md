# Admin Controller API Reference

Complete frontend integration guide for `/api/v1/admin` REST endpoints. This document includes all DTOs, permissions, validation rules, and error semantics needed for admin panel integration.

## Table of Contents

- [Overview](#overview)
- [Permission Matrix](#permission-matrix)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Endpoints](#endpoints)
  - [Role Management](#role-management)
  - [User Role Assignment](#user-role-assignment)
  - [Permission Management](#permission-management)
  - [Role-Permission Assignment](#role-permission-assignment)
  - [System Administration](#system-administration)
  - [Policy Reconciliation](#policy-reconciliation)
  - [Email Diagnostics](#email-diagnostics)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/admin`
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization**: Permission-based access control. Each endpoint requires specific permissions (see Permission Matrix).
* **Content-Type**: `application/json` for requests and responses
* **Error Format**: All errors return `ProblemDetail` or `ErrorResponse` object

---

## Permission Matrix

All endpoints require authentication and specific permissions. The authenticated user must have the listed permission(s).

| Capability | Endpoint(s) | Required Permission(s) | Notes |
| --- | --- | --- | --- |
| **Browse roles** | `GET /roles`, `GET /roles/paginated`, `GET /roles/{roleId}` | `ROLE_READ` | View role information |
| **Create roles** | `POST /roles` | `ROLE_CREATE` | Create new roles |
| **Update roles** | `PUT /roles/{roleId}` | `ROLE_UPDATE` | Modify existing roles |
| **Delete roles** | `DELETE /roles/{roleId}` | `ROLE_DELETE` | Remove roles (if no users assigned) |
| **Assign/Remove roles** | `POST/DELETE /users/{userId}/roles/{roleId}` | `ROLE_ASSIGN` | Manage user role assignments |
| **Browse permissions** | `GET /permissions`, `GET /permissions/{id}` | `PERMISSION_READ` | View permission information |
| **Create permissions** | `POST /permissions` | `PERMISSION_CREATE` | Create new permissions |
| **Update permissions** | `PUT /permissions/{id}` | `PERMISSION_UPDATE` | Modify existing permissions |
| **Delete permissions** | `DELETE /permissions/{id}` | `PERMISSION_DELETE` | Remove permissions |
| **Assign/Remove permissions** | `POST/DELETE /roles/{roleId}/permissions/{permissionId}` | `ROLE_ASSIGN` | Manage role-permission assignments |
| **System administration** | `POST /system/initialize`, `GET /system/status` | `SYSTEM_ADMIN` | System-level operations |
| **Policy reconciliation** | `POST /policy/reconcile`, `GET /policy/status` | `SYSTEM_ADMIN` | Sync roles/permissions with manifest |
| **Email diagnostics** | `POST /email/test-*`, `GET /email/provider-status` | `SYSTEM_ADMIN` | Test email functionality |
| **Audit access** | `GET /system/status` | `AUDIT_READ` (OR `SYSTEM_ADMIN`) | View system status for auditing |

---

## Request DTOs

### CreateRoleRequest

**Used by**: `POST /roles`

Create a new role in the system.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `roleName` | string | Yes | Non-blank, auto-uppercased | Unique role name (e.g., "ROLE_MODERATOR") |
| `description` | string | No | - | Human-readable role description |
| `isDefault` | boolean | No | Default: false | Whether this is the default role for new users |

**Example**:
```json
{
  "roleName": "ROLE_CONTENT_MANAGER",
  "description": "Can manage content but not users",
  "isDefault": false
}
```

**Notes**:
- Role names are automatically converted to uppercase on the server
- Role names must be unique across the system
- Only one role can be marked as default

---

### UpdateRoleRequest

**Used by**: `PUT /roles/{roleId}`

Update an existing role's metadata.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `description` | string | No | - | Updated description |
| `isDefault` | boolean | No | - | Updated default status |

**Example**:
```json
{
  "description": "Updated description for this role",
  "isDefault": true
}
```

**Notes**:
- Role name cannot be changed after creation
- Omitted fields keep their existing values
- Permissions are managed via separate endpoints

---

### CreatePermissionRequest

**Used by**: `POST /permissions`

Create a new permission in the system.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `permissionName` | string | Yes | Non-blank, auto-uppercased | Unique permission name (e.g., "QUIZ_PUBLISH") |
| `description` | string | No | - | Human-readable permission description |
| `resource` | string | No | - | Resource this permission applies to (e.g., "quiz") |
| `action` | string | No | - | Action this permission grants (e.g., "publish") |

**Example**:
```json
{
  "permissionName": "QUIZ_PUBLISH",
  "description": "Allows publishing quizzes to public catalog",
  "resource": "quiz",
  "action": "publish"
}
```

**Notes**:
- Permission names are automatically converted to uppercase
- Permission names must be unique
- Resource and action are metadata fields for documentation

---

### UpdatePermissionRequest

**Used by**: `PUT /permissions/{permissionId}`

Update an existing permission's metadata.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `description` | string | No | - | Updated description |
| `resource` | string | No | - | Updated resource |
| `action` | string | No | - | Updated action |

**Example**:
```json
{
  "description": "Updated: Allows publishing AND unpublishing quizzes",
  "resource": "quiz",
  "action": "publish_unpublish"
}
```

**Notes**:
- Permission name cannot be changed after creation
- Omitted fields keep their existing values

---

## Response DTOs

### RoleDto

**Returned by**: All role endpoints

| Field | Type | Description |
| --- | --- | --- |
| `roleId` | integer (Long) | Unique role identifier |
| `roleName` | string | Role name (uppercase) |
| `description` | string (nullable) | Role description |
| `isDefault` | boolean | Whether this is the default role |
| `permissions` | array of strings | Permission names assigned to this role |
| `userCount` | integer | Number of users with this role |

**Example**:
```json
{
  "roleId": 3,
  "roleName": "ROLE_MODERATOR",
  "description": "Can moderate content and manage users",
  "isDefault": false,
  "permissions": [
    "QUIZ_READ",
    "QUIZ_UPDATE",
    "QUIZ_DELETE",
    "USER_READ"
  ],
  "userCount": 12
}
```

---

### Permission

**Returned by**: All permission endpoints

Direct entity representation.

| Field | Type | Description |
| --- | --- | --- |
| `permissionId` | integer (Long) | Unique permission identifier |
| `permissionName` | string | Permission name (uppercase) |
| `description` | string (nullable) | Permission description |
| `resource` | string (nullable) | Associated resource type |
| `action` | string (nullable) | Associated action type |

**Example**:
```json
{
  "permissionId": 15,
  "permissionName": "QUIZ_PUBLISH",
  "description": "Allows publishing quizzes to public catalog",
  "resource": "quiz",
  "action": "publish"
}
```

---

### ReconciliationResult

**Returned by**: `POST /policy/reconcile`, `POST /policy/reconcile/{roleName}`

Result of policy reconciliation operation.

| Field | Type | Description |
| --- | --- | --- |
| `success` | boolean | Whether reconciliation succeeded |
| `message` | string | Human-readable result message |
| `rolesAdded` | integer | Number of roles created from manifest |
| `rolesUpdated` | integer | Number of roles updated |
| `permissionsAdded` | integer | Number of permissions created |
| `permissionsRemoved` | integer | Number of permissions removed from roles |
| `errors` | array of strings | Error messages if `success=false` |

**Example (Success)**:
```json
{
  "success": true,
  "message": "Policy reconciliation completed successfully",
  "rolesAdded": 2,
  "rolesUpdated": 5,
  "permissionsAdded": 8,
  "permissionsRemoved": 3,
  "errors": []
}
```

**Example (Failure)**:
```json
{
  "success": false,
  "message": "Policy reconciliation failed",
  "rolesAdded": 0,
  "rolesUpdated": 0,
  "permissionsAdded": 0,
  "permissionsRemoved": 0,
  "errors": [
    "Role ROLE_CUSTOM not found in manifest",
    "Permission INVALID_PERMISSION is not defined"
  ]
}
```

---

### PolicyDiff

**Returned by**: `GET /policy/status`

Difference between current database state and manifest.

| Field | Type | Description |
| --- | --- | --- |
| `missingRoles` | array of strings | Roles in manifest but not in database |
| `extraRoles` | array of strings | Roles in database but not in manifest |
| `missingPermissions` | array of strings | Permissions in manifest but not in database |
| `extraPermissions` | array of strings | Permissions in database but not in manifest |
| `rolePermissionMismatches` | object | Map of role names to permission differences |
| `manifestVersion` | string | Version of the policy manifest |
| `isInSync` | boolean | Whether database matches manifest exactly |

**Example**:
```json
{
  "missingRoles": ["ROLE_CUSTOM_ADMIN"],
  "extraRoles": [],
  "missingPermissions": ["NEW_FEATURE_ACCESS"],
  "extraPermissions": ["DEPRECATED_PERMISSION"],
  "rolePermissionMismatches": {
    "ROLE_ADMIN": {
      "missing": ["SYSTEM_CONFIG"],
      "extra": []
    }
  },
  "manifestVersion": "1.2.0",
  "isInSync": false
}
```

---

### EmailProviderStatus

**Returned by**: `GET /email/provider-status`

Information about the active email provider.

| Field | Type | Description |
| --- | --- | --- |
| `providerClass` | string | Java class name of active email provider |
| `isNoop` | boolean | True if using no-op (disabled) provider |
| `isSes` | boolean | True if using AWS SES provider |
| `isSmtp` | boolean | True if using SMTP provider |

**Example**:
```json
{
  "providerClass": "AwsSesEmailService",
  "isNoop": false,
  "isSes": true,
  "isSmtp": false
}
```

---

## Paginated Responses

### Page\<RoleDto\>

**Returned by**: `GET /roles/paginated`

Standard Spring Data pagination wrapper.

| Field | Type | Description |
| --- | --- | --- |
| `content` | array of `RoleDto` | Page of roles |
| `totalElements` | integer | Total number of roles matching criteria |
| `totalPages` | integer | Total number of pages |
| `number` | integer | Current page number (0-indexed) |
| `size` | integer | Page size |
| `numberOfElements` | integer | Number of elements in current page |
| `first` | boolean | Whether this is the first page |
| `last` | boolean | Whether this is the last page |

**Example**:
```json
{
  "content": [
    { /* RoleDto */ },
    { /* RoleDto */ }
  ],
  "totalElements": 25,
  "totalPages": 3,
  "number": 0,
  "size": 10,
  "numberOfElements": 10,
  "first": true,
  "last": false
}
```

---

## Endpoints

### Role Management

#### 1. List All Roles

```
GET /api/v1/admin/roles
```

**Required Permission**: `ROLE_READ`

**Success Response**: `200 OK`
```json
[
  {
    "roleId": 1,
    "roleName": "ROLE_USER",
    "description": "Default user role",
    "isDefault": true,
    "permissions": ["QUIZ_READ", "QUIZ_CREATE"],
    "userCount": 150
  },
  {
    "roleId": 2,
    "roleName": "ROLE_ADMIN",
    "description": "Administrator role",
    "isDefault": false,
    "permissions": ["SYSTEM_ADMIN", "ROLE_READ", "ROLE_CREATE"],
    "userCount": 5
  }
]
```

**Error Responses**:
- `401` - Unauthorized (missing/invalid token)
- `403` - Forbidden (missing `ROLE_READ` permission)

---

#### 2. List Roles (Paginated)

```
GET /api/v1/admin/roles/paginated
```

**Required Permission**: `ROLE_READ`

**Query Parameters**:
- `page` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 20
- `sort` (string, optional) - Sort specification (e.g., "roleName,asc"), default: "roleName"
- `search` (string, optional) - Search term for filtering by role name or description

**Success Response**: `200 OK` - `Page<RoleDto>`

**Error Responses**:
- `400` - Invalid pagination parameters
- `401` - Unauthorized
- `403` - Forbidden

---

#### 3. Get Role by ID

```
GET /api/v1/admin/roles/{roleId}
```

**Required Permission**: `ROLE_READ`

**Path Parameters**:
- `{roleId}` - Role ID (integer)

**Success Response**: `200 OK` - `RoleDto`

**Error Responses**:
- `404` - Role not found
- `401` - Unauthorized
- `403` - Forbidden

---

#### 4. Create Role

```
POST /api/v1/admin/roles
```

**Required Permission**: `ROLE_CREATE`

**Request Body**: `CreateRoleRequest`
```json
{
  "roleName": "ROLE_CONTENT_MANAGER",
  "description": "Manages content creation and moderation",
  "isDefault": false
}
```

**Success Response**: `200 OK` - `RoleDto`
```json
{
  "roleId": 10,
  "roleName": "ROLE_CONTENT_MANAGER",
  "description": "Manages content creation and moderation",
  "isDefault": false,
  "permissions": [],
  "userCount": 0
}
```

**Error Responses**:
- `400` - Validation error (duplicate role name, missing required fields)
- `401` - Unauthorized
- `403` - Forbidden

**Example Error**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Role already exists: ROLE_CONTENT_MANAGER"
}
```

---

#### 5. Update Role

```
PUT /api/v1/admin/roles/{roleId}
```

**Required Permission**: `ROLE_UPDATE`

**Path Parameters**:
- `{roleId}` - Role ID (integer)

**Request Body**: `UpdateRoleRequest`
```json
{
  "description": "Updated description",
  "isDefault": false
}
```

**Success Response**: `200 OK` - `RoleDto`

**Error Responses**:
- `404` - Role not found
- `400` - Validation error
- `401` - Unauthorized
- `403` - Forbidden

**Notes**:
- Permissions are not updated through this endpoint (use permission assignment endpoints)
- Role name cannot be changed

---

#### 6. Delete Role

```
DELETE /api/v1/admin/roles/{roleId}
```

**Required Permission**: `ROLE_DELETE`

**Path Parameters**:
- `{roleId}` - Role ID (integer)

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Role not found
- `400` - Role has users assigned (must remove users first)
- `401` - Unauthorized
- `403` - Forbidden

**Example Error**:
```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Cannot delete role with assigned users. Please remove all users from role first."
}
```

---

### User Role Assignment

#### 7. Assign Role to User

```
POST /api/v1/admin/users/{userId}/roles/{roleId}
```

**Required Permission**: `ROLE_ASSIGN`

**Path Parameters**:
- `{userId}` - User UUID
- `{roleId}` - Role ID (integer)

**Success Response**: `200 OK` (empty body)

**Error Responses**:
- `404` - User or role not found
- `401` - Unauthorized
- `403` - Forbidden

---

#### 8. Remove Role from User

```
DELETE /api/v1/admin/users/{userId}/roles/{roleId}
```

**Required Permission**: `ROLE_ASSIGN`

**Path Parameters**:
- `{userId}` - User UUID
- `{roleId}` - Role ID (integer)

**Success Response**: `200 OK` (empty body)

**Error Responses**:
- `404` - User or role not found
- `401` - Unauthorized
- `403` - Forbidden

---

### Permission Management

#### 9. List All Permissions

```
GET /api/v1/admin/permissions
```

**Required Permission**: `PERMISSION_READ`

**Success Response**: `200 OK`
```json
[
  {
    "permissionId": 1,
    "permissionName": "QUIZ_READ",
    "description": "View quizzes",
    "resource": "quiz",
    "action": "read"
  },
  {
    "permissionId": 2,
    "permissionName": "QUIZ_CREATE",
    "description": "Create new quizzes",
    "resource": "quiz",
    "action": "create"
  }
]
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

#### 10. Get Permission by ID

```
GET /api/v1/admin/permissions/{permissionId}
```

**Required Permission**: `PERMISSION_READ`

**Path Parameters**:
- `{permissionId}` - Permission ID (integer)

**Success Response**: `200 OK` - `Permission`

**Error Responses**:
- `404` - Permission not found
- `401` - Unauthorized
- `403` - Forbidden

---

#### 11. Create Permission

```
POST /api/v1/admin/permissions
```

**Required Permission**: `PERMISSION_CREATE`

**Request Body**: `CreatePermissionRequest`
```json
{
  "permissionName": "QUIZ_PUBLISH",
  "description": "Publish quizzes to catalog",
  "resource": "quiz",
  "action": "publish"
}
```

**Success Response**: `200 OK` - `Permission`
```json
{
  "permissionId": 25,
  "permissionName": "QUIZ_PUBLISH",
  "description": "Publish quizzes to catalog",
  "resource": "quiz",
  "action": "publish"
}
```

**Error Responses**:
- `400` - Duplicate permission name or validation error
- `401` - Unauthorized
- `403` - Forbidden

---

#### 12. Update Permission

```
PUT /api/v1/admin/permissions/{permissionId}
```

**Required Permission**: `PERMISSION_UPDATE`

**Path Parameters**:
- `{permissionId}` - Permission ID (integer)

**Request Body**: `UpdatePermissionRequest`
```json
{
  "description": "Publish and unpublish quizzes",
  "resource": "quiz",
  "action": "publish_manage"
}
```

**Success Response**: `200 OK` - `Permission`

**Error Responses**:
- `404` - Permission not found
- `400` - Validation error
- `401` - Unauthorized
- `403` - Forbidden

---

#### 13. Delete Permission

```
DELETE /api/v1/admin/permissions/{permissionId}
```

**Required Permission**: `PERMISSION_DELETE`

**Path Parameters**:
- `{permissionId}` - Permission ID (integer)

**Success Response**: `204 No Content`

**Error Responses**:
- `404` - Permission not found
- `401` - Unauthorized
- `403` - Forbidden

**Notes**:
- Permission is automatically removed from all roles that have it

---

### Role-Permission Assignment

#### 14. Assign Permission to Role

```
POST /api/v1/admin/roles/{roleId}/permissions/{permissionId}
```

**Required Permission**: `ROLE_ASSIGN`

**Path Parameters**:
- `{roleId}` - Role ID (integer)
- `{permissionId}` - Permission ID (integer)

**Success Response**: `200 OK` (empty body)

**Error Responses**:
- `404` - Role or permission not found
- `401` - Unauthorized
- `403` - Forbidden

---

#### 15. Remove Permission from Role

```
DELETE /api/v1/admin/roles/{roleId}/permissions/{permissionId}
```

**Required Permission**: `ROLE_ASSIGN`

**Path Parameters**:
- `{roleId}` - Role ID (integer)
- `{permissionId}` - Permission ID (integer)

**Success Response**: `200 OK` (empty body)

**Error Responses**:
- `404` - Role or permission not found
- `401` - Unauthorized
- `403` - Forbidden

---

### System Administration

#### 16. Initialize System

```
POST /api/v1/admin/system/initialize
```

**Required Permission**: `SYSTEM_ADMIN`

Initialize system with default roles and permissions from manifest.

**Success Response**: `200 OK`
```
System initialized successfully
```

**Error Responses**:
- `400` - System initialization is disabled via feature flag
- `500` - Initialization failed
- `401` - Unauthorized
- `403` - Forbidden

**Notes**:
- This endpoint can be disabled via configuration property `app.admin.system-initialization.enabled`
- Should only be called during initial system setup or after major upgrades
- Reconciles roles and permissions with the canonical manifest

---

#### 17. Get System Status

```
GET /api/v1/admin/system/status
```

**Required Permission**: `SYSTEM_ADMIN` OR `AUDIT_READ`

**Success Response**: `200 OK`
```
System status: All systems operational
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden (need either `SYSTEM_ADMIN` or `AUDIT_READ`)

---

#### 18. Dangerous Operation

```
POST /api/v1/admin/super/dangerous-operation
```

**Required Permission**: `SYSTEM_ADMIN`

Placeholder endpoint for dangerous administrative operations.

**Success Response**: `200 OK`
```
Operation completed
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

**Notes**:
- Logs a warning message with username
- Use with extreme caution

---

### Policy Reconciliation

#### 19. Reconcile All Policies

```
POST /api/v1/admin/policy/reconcile
```

**Required Permission**: `SYSTEM_ADMIN`

Synchronize all roles and permissions with the canonical manifest file.

**Success Response**: `200 OK` - `ReconciliationResult`
```json
{
  "success": true,
  "message": "Policy reconciliation completed successfully",
  "rolesAdded": 2,
  "rolesUpdated": 5,
  "permissionsAdded": 3,
  "permissionsRemoved": 1,
  "errors": []
}
```

**Partial Failure Response**: `400 Bad Request` - `ReconciliationResult`
```json
{
  "success": false,
  "message": "Policy reconciliation failed",
  "rolesAdded": 0,
  "rolesUpdated": 0,
  "permissionsAdded": 0,
  "permissionsRemoved": 0,
  "errors": [
    "Invalid manifest format",
    "Permission UNKNOWN_PERM not found"
  ]
}
```

**Error Responses**:
- `500` - Unexpected server error
- `401` - Unauthorized
- `403` - Forbidden

---

#### 20. Get Policy Status

```
GET /api/v1/admin/policy/status
```

**Required Permission**: `SYSTEM_ADMIN`

Get the difference between current database state and manifest.

**Success Response**: `200 OK` - `PolicyDiff`

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

#### 21. Get Policy Manifest Version

```
GET /api/v1/admin/policy/version
```

**Required Permission**: `SYSTEM_ADMIN`

Get the version string from the policy manifest.

**Success Response**: `200 OK`
```
1.2.0
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

#### 22. Reconcile Single Role

```
POST /api/v1/admin/policy/reconcile/{roleName}
```

**Required Permission**: `SYSTEM_ADMIN`

**Path Parameters**:
- `{roleName}` - Role name to reconcile (string, e.g., "ROLE_ADMIN")

Synchronize a specific role with the manifest.

**Success Response**: `200 OK` - `ReconciliationResult`

**Partial Failure Response**: `400 Bad Request` - `ReconciliationResult`

**Error Responses**:
- `500` - Unexpected server error
- `401` - Unauthorized
- `403` - Forbidden

---

### Email Diagnostics

#### 23. Test Verification Email

```
POST /api/v1/admin/email/test-verification?email={email}
```

**Required Permission**: `SYSTEM_ADMIN`

**Query Parameters**:
- `email` (string, required) - Email address to send test to

Send a test email verification message.

**Success Response**: `200 OK`
```
Email verification test sent successfully to: test@example.com
```

**Error Response**: `400 Bad Request`
```
Failed to send test email: SMTP connection failed
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

#### 24. Test Password Reset Email

```
POST /api/v1/admin/email/test-password-reset?email={email}
```

**Required Permission**: `SYSTEM_ADMIN`

**Query Parameters**:
- `email` (string, required) - Email address to send test to

Send a test password reset email.

**Success Response**: `200 OK`
```
Password reset test sent successfully to: test@example.com
```

**Error Response**: `400 Bad Request`
```
Failed to send test email: Invalid email address
```

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

#### 25. Get Email Provider Status

```
GET /api/v1/admin/email/provider-status
```

**Required Permission**: `SYSTEM_ADMIN`

Get information about the currently active email provider.

**Success Response**: `200 OK` - `EmailProviderStatus`

**Error Responses**:
- `401` - Unauthorized
- `403` - Forbidden

---

## Error Handling

All errors follow a consistent format:

### ProblemDetail / ErrorResponse Format

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "detail": "Role already exists: ROLE_CUSTOM"
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Validation errors, duplicate resources, business rule violations |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Missing required permission |
| `404` | Not Found | Resource (role, permission, user) doesn't exist |
| `500` | Internal Server Error | Unexpected server error, manifest parsing failure |

### Common Error Scenarios

**Duplicate Role Name**:
```json
{
  "status": 400,
  "detail": "Role already exists: ROLE_MODERATOR"
}
```

**Cannot Delete Role with Users**:
```json
{
  "status": 400,
  "detail": "Cannot delete role with assigned users. Please remove all users from role first."
}
```

**Role Not Found**:
```json
{
  "status": 404,
  "detail": "Role not found: 999"
}
```

**Missing Permission**:
```json
{
  "status": 403,
  "detail": "Access Denied"
}
```

**System Initialization Disabled**:
```json
{
  "status": 400,
  "detail": "System initialization is disabled"
}
```

---

## Integration Guide

### Initial Setup

1. **Authenticate** with admin credentials to get JWT token
2. **Check permissions** - Ensure your user has required admin permissions
3. **Get current state** - Fetch existing roles and permissions
4. **Reconcile if needed** - Run policy reconciliation to sync with manifest

### Common Workflows

#### Creating a Custom Role

1. **Create the role**: `POST /roles`
2. **Assign permissions**: `POST /roles/{roleId}/permissions/{permissionId}` for each permission
3. **Verify**: `GET /roles/{roleId}` to confirm permissions

**Example Sequence**:
```javascript
// 1. Create role
const createRoleResponse = await fetch('/api/v1/admin/roles', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    roleName: 'ROLE_MODERATOR',
    description: 'Content moderation role',
    isDefault: false
  })
});
const role = await createRoleResponse.json();

// 2. Assign permissions
const permissions = [1, 5, 7]; // Permission IDs
for (const permId of permissions) {
  await fetch(`/api/v1/admin/roles/${role.roleId}/permissions/${permId}`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
}

// 3. Verify
const verifyResponse = await fetch(`/api/v1/admin/roles/${role.roleId}`, {
  headers: { 'Authorization': `Bearer ${token}` }
});
const updatedRole = await verifyResponse.json();
console.log('Permissions:', updatedRole.permissions);
```

---

#### Assigning Roles to Users

1. **Get available roles**: `GET /roles`
2. **Assign to user**: `POST /users/{userId}/roles/{roleId}`
3. **Verify user's roles**: Check via user endpoint

---

#### Policy Reconciliation

1. **Check diff**: `GET /policy/status`
2. **Review differences**: Check `missingRoles`, `extraPermissions`, etc.
3. **Reconcile**: `POST /policy/reconcile`
4. **Verify**: `GET /policy/status` again to confirm `isInSync: true`

**Example**:
```javascript
// 1. Check current status
const statusResponse = await fetch('/api/v1/admin/policy/status', {
  headers: { 'Authorization': `Bearer ${token}` }
});
const diff = await statusResponse.json();

if (!diff.isInSync) {
  console.log('Missing roles:', diff.missingRoles);
  console.log('Extra permissions:', diff.extraPermissions);
  
  // 2. Reconcile
  const reconcileResponse = await fetch('/api/v1/admin/policy/reconcile', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
  const result = await reconcileResponse.json();
  
  if (result.success) {
    console.log('Reconciliation successful');
    console.log(`Roles added: ${result.rolesAdded}`);
    console.log(`Permissions added: ${result.permissionsAdded}`);
  } else {
    console.error('Reconciliation failed:', result.errors);
  }
}
```

---

#### Testing Email Configuration

1. **Check provider**: `GET /email/provider-status`
2. **Test verification email**: `POST /email/test-verification?email=test@example.com`
3. **Test password reset**: `POST /email/test-password-reset?email=test@example.com`
4. **Verify email received** in inbox

---

### Best Practices

1. **Permission Checks**
   - Always handle 403 errors gracefully
   - Cache permission requirements in your frontend to disable unavailable features
   - Display appropriate "access denied" messages to users

2. **Error Handling**
   - Parse `detail` field for user-friendly error messages
   - Handle 400 errors specially for duplicate resources
   - Implement retry logic for 500 errors

3. **Role Management**
   - Check `userCount` before deleting roles
   - Warn users if they're about to delete a role with users
   - Provide workflow to reassign users before deletion

4. **Policy Reconciliation**
   - Run during maintenance windows
   - Check `PolicyDiff` first to preview changes
   - Handle `success=false` responses (still contains useful error info)
   - Log reconciliation results for audit trail

5. **Email Testing**
   - Test email functionality after deployment
   - Handle provider-specific error messages
   - Show provider status in admin dashboard

6. **Pagination**
   - Use pagination for role lists in large systems
   - Implement search functionality for better UX
   - Remember to handle `first` and `last` flags for navigation

7. **Validation**
   - Validate role/permission names client-side (uppercase, no special chars)
   - Check for duplicates before submission
   - Provide clear validation feedback to users

---

## Security Considerations

1. **Least Privilege**: Only grant minimum permissions needed
2. **Audit Trail**: All admin operations are logged with username
3. **Token Expiry**: Handle token expiration and refresh
4. **Permission Escalation**: Cannot assign permissions you don't have
5. **Protected Roles**: System roles (ROLE_USER, ROLE_ADMIN) may have special protections
6. **Dangerous Operations**: Require explicit confirmation in UI

