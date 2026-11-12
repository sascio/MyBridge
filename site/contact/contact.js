(function () {
  'use strict';
  var form = document.getElementById('contact-form'), status = document.getElementById('contact-status'), button = form.querySelector('button'), widget, siteKey;
  function message(text, error) { status.textContent = text; status.classList.toggle('is-error', !!error); }
  function resetChallenge() { if (siteKey && window.turnstile && widget !== undefined) window.turnstile.reset(widget); }
  fetch('/api/contact-config', {cache:'no-store'}).then(function (response) { if (!response.ok) throw Error(); return response.json(); }).then(function (config) {
    if (!config.available) throw Error();
    siteKey = config.siteKey;
    if (!siteKey) { button.disabled = false; message(''); return; }
    var script = document.createElement('script'); script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'; script.async = true;
    script.onload = function () { try { widget = window.turnstile.render('#turnstile', {sitekey:siteKey, callback:function () { button.disabled = false; message(''); }, 'expired-callback':function () { button.disabled = true; message('Security check expired. Please complete it again.', true); }, 'error-callback':function () { button.disabled = true; message('Security check unavailable. Please reload and try again.', true); }}); } catch (_) { message('Security check unavailable. Please reload and try again.', true); } };
    script.onerror = function () { message('Security check unavailable. Please reload and try again.', true); }; document.head.appendChild(script);
  }).catch(function () { message('Contact is not available yet. Please try again later.', true); });
  form.addEventListener('submit', async function (event) {
    event.preventDefault(); if (!form.reportValidity() || button.disabled) return;
    var data = new FormData(form), payload = {name:data.get('name'), email:data.get('email'), subject:data.get('subject'), message:data.get('message'), website:data.get('website'), token:siteKey && window.turnstile ? window.turnstile.getResponse(widget) : ''};
    button.disabled = true; message('Sending your message…');
    try {
      var response = await fetch('/api/contact', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(payload)});
      var result = await response.json();
      if (!response.ok || result.ok !== true) throw Error(typeof result.error === 'string' ? result.error : 'Message delivery could not be confirmed. Please try again later.');
      form.reset(); message('Your message was sent. Thank you for contacting StreamBridge.');
    } catch (error) { message(error.message || 'Message delivery could not be confirmed. Please try again later.', true); }
    resetChallenge(); if (!siteKey) button.disabled = false;
  });
})();
