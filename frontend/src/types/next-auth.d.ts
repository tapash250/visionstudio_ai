import 'next-auth';
import 'next-auth/jwt';

// Fields added by the jwt/session callbacks in app/api/auth/[...nextauth]/route.ts.
declare module 'next-auth' {
  interface Session {
    /** Backend API token, forwarded as `Authorization: Bearer` by lib/api.ts and useWebSocket. */
    accessToken?: string;
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    id?: string;
    accessToken?: string;
  }
}
