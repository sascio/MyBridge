// Lightweight tests: node site/tests/contact.test.mjs (no network or credentials).
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import vm from 'node:vm';

const source = await readFile(new URL('../../functions/api/contact.js', import.meta.url), 'utf8');
const {onRequest} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const origin = 'https://preview.example';
const env = {CONTACT_GAS_WEB_APP_URL:'https://script.google.com/macros/s/example/exec', CONTACT_SHARED_SECRET:'x'.repeat(48)};
const valid = {name:'Test Visitor', email:'visitor@example.com', subject:'Technical Support', message:'Contact test.', website:''};
function request(data, options = {}) {
  return new Request(origin + '/api/contact', {method: options.method || 'POST', headers: options.headers || {origin, 'content-type':'application/json'}, ...(options.method === 'GET' ? {} : {body:options.raw ?? JSON.stringify(data)})});
}
async function run(data, options = {}) { return onRequest({request:request(data, options), env:options.env ?? env}); }
assert.equal((await run(valid, {method:'GET'})).status, 405);
assert.equal((await run(valid, {env:{}})).status, 503);
assert.equal((await run(valid, {headers:{origin,'content-type':'text/plain'}})).status, 415);
assert.equal((await run(valid, {headers:{origin:'https://other.example','content-type':'application/json'}})).status, 403);
assert.equal((await run(valid, {raw:'{bad'})).status, 400);
assert.equal((await run({...valid, name:''})).status, 400);
assert.equal((await run({...valid, email:'x\r\nBcc:bad@example.com'})).status, 400);
assert.equal((await run({...valid, website:'spam'})).status, 400);
assert.equal((await run({...valid, message:'x'.repeat(17000)})).status, 413);
let forwarded;
globalThis.fetch = async (url, options) => { forwarded = JSON.parse(options.body); return new Response('{"ok":true}'); };
let result = await run(valid); assert.equal(result.status, 200); assert.deepEqual(await result.json(), {ok:true}); assert.equal(forwarded.email, valid.email);
globalThis.fetch = async () => new Response('{"ok":false}');
result = await run(valid); assert.equal(result.status, 502); assert(!JSON.stringify(await result.json()).includes(env.CONTACT_SHARED_SECRET));
globalThis.fetch = async () => { throw new Error('private upstream error'); };
result = await run(valid); assert.equal(result.status, 502); assert(!JSON.stringify(await result.json()).includes('private upstream'));
globalThis.fetch = async () => new Response('{"success":false}', {headers:{'content-type':'application/json'}});
assert.equal((await run({...valid, token:'invalid'}, {env:{...env,TURNSTILE_SITE_KEY:'public',TURNSTILE_SECRET_KEY:'private'}})).status,400);
let mail;
const context = {Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(alg,s)=>[...createHash(alg).update(s).digest()]},PropertiesService:{getScriptProperties:()=>({getProperty:k=>k==='CONTACT_SHARED_SECRET'?env.CONTACT_SHARED_SECRET:'private-recipient'})},ContentService:{MimeType:{JSON:'json'},createTextOutput:value=>({value,setMimeType(){return this}})},MailApp:{sendEmail:value=>{mail=value;}}};
vm.createContext(context); vm.runInContext(await readFile(new URL('../contact-setup/Contact.gs', import.meta.url),'utf8'), context);
const script = d => JSON.parse(context.doPost({postData:{contents:JSON.stringify(d)}}).value).ok;
assert.equal(script(forwarded),true); assert.equal(mail.replyTo,valid.email); assert.equal(mail.subject,'StreamBridge Website Contact — Technical Support');
assert.equal(script({...forwarded,secret:'wrong'}),false); assert.equal(script({...forwarded,email:'a\r\nBcc:x@example.com'}),false);
console.log('Contact function and Apps Script mock validation passed. Real delivery not tested.');
