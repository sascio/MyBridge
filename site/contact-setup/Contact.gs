// Deploy as a Google Apps Script Web App, executing as the owner. See CONTACT-SETUP.md.
// Compare fixed-length digests without an early exit; the runtime does not promise constant-time execution.
function sameSecret(candidate, expected) {
  if (typeof candidate !== 'string' || candidate.length > 1024) return false;
  var a = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, candidate, Utilities.Charset.UTF_8);
  var b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, expected, Utilities.Charset.UTF_8);
  var difference = 0;
  for (var i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}
function doPost(e) {
  function reply(ok) { return ContentService.createTextOutput(JSON.stringify({ok: ok})).setMimeType(ContentService.MimeType.JSON); }
  try {
    var props = PropertiesService.getScriptProperties();
    var secret = props.getProperty('CONTACT_SHARED_SECRET');
    var recipient = props.getProperty('CONTACT_RECIPIENT_EMAIL');
    if (!secret || secret.length < 32 || !recipient || !e || !e.postData || !e.postData.contents || e.postData.contents.length > 16384) return reply(false);
    var d = JSON.parse(e.postData.contents);
    var categories = ['General Enquiry', 'Technical Support', 'Bug Report', 'Feature Request', 'Privacy Request', 'Copyright / Legal', 'Other'];
    var email = /^[A-Za-z0-9.!#$%&'*+\/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;
    if (!d || !sameSecret(d.secret, secret) || typeof d.name !== 'string' || !d.name.trim() || d.name.length > 100 || /[\r\n\x00-\x1f\x7f]/.test(d.name) || typeof d.email !== 'string' || d.email.length > 254 || (!email.test(d.email) || d.email.charAt(0) === '.' || d.email.indexOf('..') !== -1 || d.email.split('@')[0].slice(-1) === '.') || categories.indexOf(d.subject) < 0 || typeof d.message !== 'string' || !d.message.trim() || d.message.length > 5000 || /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(d.message)) return reply(false);
    MailApp.sendEmail({to: recipient, replyTo: d.email, subject: '[StreamBridge Contact] ' + d.subject, body: 'Name: ' + d.name.trim() + '\nEmail: ' + d.email + '\nCategory: ' + d.subject + '\nSubmitted (UTC): ' + new Date().toISOString() + '\n\nMessage:\n' + d.message.trim()});
    return reply(true);
  } catch (err) { return reply(false); }
}
