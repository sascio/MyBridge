export function onRequest({request, env}) {
  if (request.method !== 'GET') return new Response(null, {status:405});
  const configured = Boolean(env.CONTACT_GAS_WEB_APP_URL && env.CONTACT_SHARED_SECRET && env.CONTACT_SHARED_SECRET.length >= 32 && Boolean(env.TURNSTILE_SITE_KEY) === Boolean(env.TURNSTILE_SECRET_KEY));
  return new Response(JSON.stringify({available:configured, siteKey:configured ? (env.TURNSTILE_SITE_KEY || null) : null}), {headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
}
