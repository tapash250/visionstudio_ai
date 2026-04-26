# VisionStudio AI API Documentation

## Base URL
```
Production: https://api.visionstudio.app
Staging: https://staging-api.visionstudio.app
Local: http://localhost:8000
```

## Authentication

All API requests (except auth endpoints) require a Bearer token in the Authorization header:

```
Authorization: Bearer <access_token>
```

### Token Refresh
When an access token expires (15 minutes), use the refresh token to get a new one:

```http
POST /api/auth/refresh
Content-Type: application/json

{
  "refresh_token": "<refresh_token>"
}
```

---

## Endpoints

### Authentication

#### Register
```http
POST /api/auth/register
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "securepassword",
  "name": "User Name"
}
```

**Response:**
```json
{
  "id": "user-id",
  "email": "user@example.com",
  "message": "Account created"
}
```

#### Login
```http
POST /api/auth/token
Content-Type: application/x-www-form-urlencoded

username=user@example.com&password=securepassword
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "bearer",
  "expires_in": 900
}
```

#### Get Current User
```http
GET /api/auth/me
Authorization: Bearer <token>
```

**Response:**
```json
{
  "id": "user-id",
  "email": "user@example.com",
  "name": "User Name",
  "avatar": "https://...",
  "mature_enabled": false
}
```

---

### Image Generation

#### Create Generation Job
```http
POST /api/generate/
Authorization: Bearer <token>
Content-Type: application/json

{
  "prompt": "a beautiful sunset over mountains",
  "negativePrompt": "blurry, low quality",
  "model": "flux-dev",
  "style": "cinematic",
  "aspectRatio": "16:9",
  "seed": 12345,
  "steps": 30,
  "cfgScale": 7.5,
  "batchSize": 2
}
```

**Response:**
```json
{
  "jobId": "job-uuid",
  "status": "QUEUED"
}
```

#### Check Job Status
```http
GET /api/generate/{job_id}
Authorization: Bearer <token>
```

**Response:**
```json
{
  "status": "COMPLETED",
  "resultUrls": [
    "https://cdn.visionstudio.app/results/img1.png",
    "https://cdn.visionstudio.app/results/img2.png"
  ],
  "error": null,
  "progress": 100
}
```

#### Enhance Prompt
```http
POST /api/generate/enhance
Authorization: Bearer <token>
Content-Type: application/json

{
  "prompt": "a cat"
}
```

**Response:**
```json
{
  "enhanced": "masterpiece, best quality, highly detailed, a cat, professional photography, 8k uhd"
}
```

---

### Image Editing

#### Create Edit Job
```http
POST /api/edit/
Authorization: Bearer <token>
Content-Type: application/json

{
  "sourceUrl": "https://cdn.visionstudio.app/uploads/img.png",
  "operation": "remove_background",
  "prompt": "transparent background",
  "strength": 0.75,
  "preserveFace": true
}
```

**Operations:** `inpaint`, `remove_object`, `remove_background`, `replace_background`, `face_restore`, `face_enhance`, `skin_retouch`, `replace_clothing`, `change_style`, `color_swap`

**Response:**
```json
{
  "jobId": "job-uuid"
}
```

#### Check Edit Status
```http
GET /api/edit/{job_id}
Authorization: Bearer <token>
```

---

### Animation

#### Create Animation Job
```http
POST /api/animate/
Authorization: Bearer <token>
Content-Type: application/json

{
  "sourceUrl": "https://cdn.visionstudio.app/uploads/photo.png",
  "animationType": "face_blink",
  "duration": 3,
  "fps": 24,
  "format": "mp4"
}
```

**Animation Types:** `face_blink`, `smile`, `head_motion`, `talking`, `lip_sync`, `zoom`, `parallax`, `dance`, `pan`, `bg_motion`

**Response:**
```json
{
  "jobId": "job-uuid"
}
```

---

### Projects

#### List Projects
```http
GET /api/projects/?type=generation
Authorization: Bearer <token>
```

**Query Parameters:**
- `type` (optional): Filter by type (`generation`, `edit`, `animation`)

**Response:**
```json
{
  "projects": [
    {
      "id": "project-id",
      "title": "Sunset Mountains",
      "type": "GENERATION",
      "status": "COMPLETED",
      "thumbnailUrl": "https://...",
      "resultUrl": "https://...",
      "isPublic": false,
      "createdAt": "2026-04-26T10:00:00Z"
    }
  ]
}
```

#### Update Project
```http
POST /api/projects/{project_id}
Authorization: Bearer <token>
Content-Type: application/json

{
  "title": "New Title",
  "isPublic": true,
  "tags": ["nature", "sunset"]
}
```

#### Delete Project
```http
POST /api/projects/{project_id}/delete
Authorization: Bearer <token>
```

---

### File Upload

#### Upload Image
```http
POST /api/upload/
Authorization: Bearer <token>
Content-Type: multipart/form-data

file: <binary>
```

**Response:**
```json
{
  "url": "https://cdn.visionstudio.app/uploads/user-id/uuid.png",
  "key": "uploads/user-id/uuid.png",
  "filename": "photo.png"
}
```

---

### Style Presets

#### List Styles
```http
GET /api/styles/?category=photography&include_mature=false
```

**Response:**
```json
{
  "styles": [
    {
      "id": "style-id",
      "name": "realistic",
      "label": "Realistic",
      "category": "Photography",
      "thumbnailUrl": "https://...",
      "colorAccent": "#f59e0b",
      "isMature": false
    }
  ]
}
```

---

### WebSocket (Real-time Updates)

Connect to WebSocket for real-time job status updates:

```javascript
const ws = new WebSocket('wss://api.visionstudio.app/ws?token=<jwt_token>');

ws.onopen = () => {
  // Subscribe to a job
  ws.send(JSON.stringify({
    type: 'subscribe_job',
    jobId: 'job-uuid',
    jobType: 'generation'
  }));
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('Job update:', data);
};
```

**Message Types:**
- `job_update` — Job status changed
- `pong` — Heartbeat response

---

### Push Notifications

#### Register Device
```http
POST /api/push/register
Authorization: Bearer <token>
Content-Type: application/json

{
  "fingerprint": "device-fingerprint",
  "pushToken": "{"endpoint":"...","keys":{...}}",
  "deviceName": "Pixel 7",
  "deviceType": "mobile",
  "os": "Android 14",
  "browser": "Chrome"
}
```

---

## Error Responses

All errors follow this format:

```json
{
  "detail": "Error message",
  "status": "error"
}
```

**Common Status Codes:**
- `200` — Success
- `201` — Created
- `400` — Bad Request
- `401` — Unauthorized
- `403` — Forbidden
- `404` — Not Found
- `429` — Rate Limit Exceeded
- `500` — Internal Server Error

---

## Rate Limits

| Endpoint Type | Limit |
|--------------|-------|
| Anonymous | 10 req/min |
| Authenticated | 60 req/min |
| Generation | 5 jobs/min |
| Upload | 10 files/min |

Rate limit headers:
```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1714123456
```
