const subjects = ['General Enquiry', 'Technical Support', 'Bug Report', 'Feature Request', 'Privacy Request', 'Copyright / Legal', 'Other'];
const emailPattern = /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;
const respond = (status, error) => new Response(JSON.stringify(error ? { ok: false, error } : { ok: true }), {status, headers: {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'}});

export async function onRequest({request, env}) {
  if (request.method !== 'POST') return respond(405, 'Method not allowed.');
  if (request.headers.get('origin') !== new URL(request.url).origin) return respond(403, 'Request origin not allowed.');
  if (!/^application\/json(?:\s*;|\s*$)/i.test(request.headers.get('content-type') || '')) return respond(415, 'Expected JSON.');
  if (Number(request.headers.get('content-length') || 0) > 16384) return respond(413, 'Message is too large.');
  let data;
  try {
    const reader = request.body.getReader(); let size = 0; const chunks = [];
    while (true) { const {done, value} = await reader.read(); if (done) break; size += value.length; if (size > 16384) { await reader.cancel(); return respond(413, 'Message is too large.'); } chunks.push(value); }
    const bytes = new Uint8Array(size); let offset = 0; for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    data = JSON.parse(new TextDecoder().decode(bytes));
  } catch { return respond(400, 'Invalid request.'); }
  if (!data || typeof data !== 'object' || Array.isArray(data)) return respond(400, 'Invalid request.');
  if (typeof data.website !== 'string' || data.website !== '') return respond(400, 'Unable to submit this message.');
  const {name, email, subject, message, token} = data;
  if (typeof name !== 'string' || !name.trim() || name.length > 100 || /[\r\n\x00-\x1f\x7f]/.test(name) || typeof email !== 'string' || email.length > 254 || (!emailPattern.test(email) || email.startsWith('.') || email.includes('..') || email.split('@')[0].endsWith('.')) || !subjects.includes(subject) || typeof message !== 'string' || !message.trim() || message.length > 5000 || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(message)) return respond(400, 'Please check your name, email, subject and message.');
  const secret = env.CONTACT_SHARED_SECRET, url = env.CONTACT_GAS_WEB_APP_URL;
  if (!secret || secret.length < 32 || !url || Boolean(env.TURNSTILE_SITE_KEY) !== Boolean(env.TURNSTILE_SECRET_KEY)) return respond(503, 'Contact is not configured yet. Please try again later.');
  let target;
  try { target = new URL(url); } catch { return respond(503, 'Contact is not configured yet. Please try again later.'); }
  if (target.protocol !== 'https:' || target.hostname !== 'script.google.com' || !/^\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(target.pathname) || target.search || target.hash || target.username || target.password || target.port) return respond(503, 'Contact is not configured yet. Please try again later.');
  try {
    if (env.TURNSTILE_SECRET_KEY) {
      if (typeof token !== 'string' || !token || token.length > 2048) return respond(400, 'Please complete the security check.');
      const verification = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({secret:env.TURNSTILE_SECRET_KEY, response:token, remoteip:request.headers.get('CF-Connecting-IP') || undefined}), signal:AbortSignal.timeout(10000)});
      const result = await verification.json();
      if (!verification.ok || result.success !== true || result.hostname !== new URL(request.url).hostname) return respond(400, 'Security check failed. Please try again.');
    }
    let response = await fetch(target.toString(), {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({secret, name:name.trim(), email, subject, message:message.trim()}), redirect:'manual', signal:AbortSignal.timeout(15000)});
    // Apps Script ContentService returns a one-time Googleusercontent redirect. Never forward the secret to it.
    if ([301,302,303].includes(response.status)) {
      const location = response.headers.get('location'); const next = new URL(location);
      if (next.protocol !== 'https:' || next.hostname !== 'script.googleusercontent.com' || next.username || next.password || next.port) return respond(502, 'Message delivery could not be confirmed. Please try again later.');
      response = await fetch(next.toString(), {method:'GET', redirect:'error', signal:AbortSignal.timeout(10000)});
    }
    if (!response.ok) return respond(502, 'Message delivery could not be confirmed. Please try again later.');
    const result = await response.json();
    if (result.ok !== true) return respond(502, 'Message delivery could not be confirmed. Please try again later.');
    return respond(200);
  } catch { return respond(502, 'Message delivery could not be confirmed. Please try again later.'); }
}
