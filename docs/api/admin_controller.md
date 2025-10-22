# AdminController API Spec

Base path: `/api/v1/admin`\
Content type: `application/json`

## Auth

| Operation group | Auth | Permissions |
| --------------- | ---- | ----------- |
| Roles (GET) | JWT | `ROLE_READ`
| Roles (POST/PUT/DELETE) | JWT | `ROLE_CREATE`, `ROLE_UPDATE`, `ROLE_DELETE`
| Role assignments | JWT | `ROLE_ASSIGN`
| Permissions (GET/POST/PUT/DELETE) | JWT | `PERMISSION_READ`, `PERMISSION_CREATE`, `PERMISSION_UPDATE`, `PERMISSION_DELETE`
| Policy/system ops | JWT | `SYSTEM_ADMIN` (plus `AUDIT_READ` can read `/system/status`)

---

## DTOs

**RoleDto**

```ts
{
  roleId: number;
  roleName: string;      // uppercase
  description?: string;
  isDefault: boolean;
  permissions: string[]; // permission names
  userCount: number;
}
```

**CreateRoleRequest / UpdateRoleRequest**

```ts
type CreateRoleRequest = {
  roleName: string;      // required, trimmed, uppercased
  description?: string;
  isDefault?: boolean;
};

type UpdateRoleRequest = {
  description?: string;
  isDefault?: boolean;
};
```

**Permission**

```ts
{
  permissionId: number;
  permissionName: string; // uppercase unique
  description?: string;
  resource?: string;
  action?: string;
}
```

**CreatePermissionRequest / UpdatePermissionRequest**

```ts
type CreatePermissionRequest = {
  permissionName: string; // required
  description?: string;
  resource?: string;
  action?: string;
};

type UpdatePermissionRequest = {
  description?: string;
  resource?: string;
  action?: string;
};
```

**ReconciliationResult**

```ts
{
  success: boolean;
  message: string;
  permissionsAdded: number;
  permissionsRemoved: number;
  rolesAdded: number;
  rolesUpdated: number;
  rolePermissionMappingsUpdated: number;
  errors: string[];
}
```

**PolicyDiff**

```ts
{
  missingPermissions: string[];
  extraPermissions: string[];
  missingRoles: string[];
  extraRoles: string[];
  rolePermissionMismatches: Record<string, string[]>;
  manifestVersion: string;
  isInSync: boolean;
}
```

**EmailProviderStatus**

```ts
{
  providerClass: string;
  isNoop: boolean;
  isSes: boolean;
  isSmtp: boolean;
}
```

**ErrorResponse**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/roles` | – | `RoleDto[]` | `ROLE_READ` | Full list |
| GET | `/roles/paginated` | – | `Page<RoleDto>` | `ROLE_READ` | `page`, `size`, optional `search` |
| GET | `/roles/{roleId}` | – | `RoleDto` | `ROLE_READ` | 404 if missing |
| POST | `/roles` | `CreateRoleRequest` | `RoleDto` | `ROLE_CREATE` | Name uppercased server-side |
| PUT | `/roles/{roleId}` | `UpdateRoleRequest` | `RoleDto` | `ROLE_UPDATE` | Full replace |
| DELETE | `/roles/{roleId}` | – | 204 | `ROLE_DELETE` | Hard delete |
| POST | `/users/{userId}/roles/{roleId}` | – | 200 | `ROLE_ASSIGN` | UUID user id |
| DELETE | `/users/{userId}/roles/{roleId}` | – | 200 | `ROLE_ASSIGN` | Removes role |
| POST | `/roles/{roleId}/permissions/{permissionId}` | – | 200 | `ROLE_ASSIGN` | Attach permission |
| DELETE | `/roles/{roleId}/permissions/{permissionId}` | – | 200 | `ROLE_ASSIGN` | Detach permission |
| GET | `/permissions` | – | `Permission[]` | `PERMISSION_READ` | Full list |
| GET | `/permissions/{permissionId}` | – | `Permission` | `PERMISSION_READ` | 404 if missing |
| POST | `/permissions` | `CreatePermissionRequest` | `Permission` | `PERMISSION_CREATE` | Name uppercased |
| PUT | `/permissions/{permissionId}` | `UpdatePermissionRequest` | `Permission` | `PERMISSION_UPDATE` | |
| DELETE | `/permissions/{permissionId}` | – | 204 | `PERMISSION_DELETE` | |
| POST | `/system/initialize` | – | `string` | `SYSTEM_ADMIN` | 400 if disabled via flag |
| GET | `/system/status` | – | `string` | `SYSTEM_ADMIN` or `AUDIT_READ` | Plain text |
| POST | `/super/dangerous-operation` | – | `string` | `SYSTEM_ADMIN` | Audited action |
| POST | `/policy/reconcile` | – | `ReconciliationResult` | `SYSTEM_ADMIN` | Full reconciliation |
| POST | `/policy/reconcile/{roleName}` | – | `ReconciliationResult` | `SYSTEM_ADMIN` | Single role |
| GET | `/policy/status` | – | `PolicyDiff` | `SYSTEM_ADMIN` | Inspect drift |
| GET | `/policy/version` | – | `string` | `SYSTEM_ADMIN` | Manifest version |
| POST | `/email/test-verification` | – | `string` | `SYSTEM_ADMIN` | Query param `email` |
| POST | `/email/test-password-reset` | – | `string` | `SYSTEM_ADMIN` | Query param `email` |
| GET | `/email/provider-status` | – | `EmailProviderStatus` | `SYSTEM_ADMIN` | Current provider |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error | Includes feature-flag guard failures |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks required permission |
| 404 | Not found | Role/permission/user absent |
| 409 | Conflict | Duplicate names, assignment collisions |
| 500 | Server error | Unexpected backend failure |

---

## Validation Summary

- `roleName` and `permissionName` are required and normalized to uppercase; duplicates trigger 409.
- `CreateRoleRequest.roleName` must be non-blank; optional fields trimmed.
- Email test endpoints require valid `email` query parameter.
- Reconciliation endpoints require `SYSTEM_ADMIN`.

---

## Notes for Agents

- All operations require `Authorization: Bearer <jwt>`.
- Refresh cached permission matrices after role/permission mutations.
- Surface `ReconciliationResult.errors` to admins for manual follow-up.
- Email diagnostics send real mail; keep hidden or disabled outside admin flows.
