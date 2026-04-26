import { withAuth } from 'next-auth/middleware';
import { NextResponse } from 'next/server';

export default withAuth(
  function middleware(req) {
    const { pathname } = req.nextUrl;
    const token = req.nextauth.token;

    // Protect admin routes
    if (pathname.startsWith('/admin') && !token?.email?.endsWith('@visionstudio.app')) {
      return NextResponse.redirect(new URL('/', req.url));
    }

    // Protect mature content routes
    if (pathname.startsWith('/mature') && !token) {
      return NextResponse.redirect(new URL('/auth/login', req.url));
    }

    return NextResponse.next();
  },
  {
    callbacks: {
      authorized({ req, token }) {
        // Public paths that don't require auth
        const publicPaths = ['/', '/auth/login', '/auth/register', '/api/auth'];
        if (publicPaths.some(p => req.nextUrl.pathname.startsWith(p))) {
          return true;
        }
        return token !== null;
      },
    },
  }
);

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|manifest.json|sw.js|icons/).*)'],
};
