// TEMPORARY: remove this diagnostic before merging or deploying to production.
// Only the existing branch preview alias may run it; never return configuration values.
export function onRequest({request, env}) {
  const headers = {'Content-Type':'application/json; charset=utf-8', 'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff'};
  if (new URL(request.url).hostname !== 'arena-d93c71ec-mybridge.streambridgeapp.pages.dev') return new Response(null, {status:404, headers});
  if (request.method !== 'GET') return new Response(null, {status:405, headers});
  const site = Boolean(env.TURNSTILE_SITE_KEY);
  const secret = Boolean(env.TURNSTILE_SECRET_KEY);
  return new Response(JSON.stringify({
    contactUrlPresent: Boolean(env.CONTACT_GAS_WEB_APP_URL),
    sharedSecretPresent: Boolean(env.CONTACT_SHARED_SECRET),
    sharedSecretMeetsMinimum: Boolean(env.CONTACT_SHARED_SECRET && env.CONTACT_SHARED_SECRET.length >= 32),
    turnstile: site && secret ? 'both_present' : !site && !secret ? 'both_absent' : 'mismatched'
  }), {headers});
}
