# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

## Reporting a Vulnerability

If you discover a security vulnerability in VisionStudio AI, please email us at **security@visionstudio.app** instead of opening a public issue.

Please include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We will respond within 48 hours and work with you to resolve the issue.

## Security Measures

### Authentication
- JWT tokens with 15-minute expiration
- Refresh token rotation
- Secure password hashing (bcrypt)
- OAuth 2.0 integration

### Authorization
- Role-based access control
- Mature content PIN protection
- Admin dashboard with audit logs

### Data Protection
- HTTPS enforced everywhere
- Signed URLs for media access
- Database encryption at rest
- Encrypted mature content storage

### Content Safety
- Prompt injection filtering
- NSFW content detection
- Abuse prevention system
- Rate limiting per device/IP

### Infrastructure
- Container security scanning
- Dependency vulnerability monitoring
- Automated security updates
- DDoS protection via Cloudflare

## Responsible Disclosure

We follow responsible disclosure practices:
1. Report received and acknowledged within 48 hours
2. Investigation within 1 week
3. Fix deployed within 30 days
4. Public disclosure after fix is deployed

## Bug Bounty

We offer rewards for critical security findings:
- Critical: $500-$2000
- High: $200-$500
- Medium: $50-$200

## Security Headers

Our application implements the following security headers:
- `Strict-Transport-Security`
- `X-Content-Type-Options`
- `X-Frame-Options`
- `X-XSS-Protection`
- `Content-Security-Policy`
- `Referrer-Policy`
- `Permissions-Policy`
