package com.streambridge.app.addon.plugin.compat

/**
 * The provider compatibility layer (v2), evaluated in every provider
 * execution right after the runtime's base prelude.
 *
 * Contents (all provider-agnostic, capability-based):
 *  - a real CommonJS `require()` with a module registry: built-in
 *    adapters (buffer, events, path, url, querystring, util, assert,
 *    string_decoder, stream, crypto, process, timers), host-registered
 *    bundles (cheerio, node-forge) and pre-fetched relative modules;
 *  - Buffer (Uint8Array subclass, Node semantics, differential-tested);
 *  - timers with REAL delays through the host clock, microtasks;
 *  - a `process` surface that is honest about the sandbox;
 *  - EventEmitter and a functional minimal stream layer;
 *  - path/url/querystring/util/assert ports (differential-tested against
 *    Node's own modules);
 *  - crypto: node `crypto` plus a WebCrypto-shaped `crypto.subtle`, all
 *    backed by the host [CryptoCapability] (javax.crypto);
 *  - localStorage/sessionStorage scoped to the execution, quota-enforced;
 *  - browser globals: window/self (global aliases), a real navigator,
 *    location derived from the provider's code URL (navigation is a
 *    controlled error), document.cookie backed by the HTTP engine's
 *    cookie jar, <a> URL parsing, controlled errors for everything
 *    that genuinely cannot exist here (DOM rendering, events, media);
 *  - TextEncoder, AbortController/AbortSignal, DOMException.
 *
 * Everything unsupported throws a CONTROLLED error that states exactly
 * what is missing — nothing is faked with empty objects.
 *
 * The JavaScript is kept in one raw string with NO dollar-template
 * hazards (enforced by tooling) so Kotlin passes it through verbatim.
 */
object CompatPrelude {
    val JS: String = """
// =============================================================================
// StreamBridge provider compatibility layer (v2)
// =============================================================================
// Loaded after the base prelude (console, fetch, atob/btoa, URL,
// URLSearchParams, TextDecoder). It provides the browser and Node.js
// surfaces that real-world providers expect:
//
//   - real functionality wherever the sandbox can genuinely provide it
//     (Buffer, timers with real delays, EventEmitter, path/url/querystring/
//     util/assert, storage, crypto via the host capability, document.cookie
//     backed by the HTTP engine's cookie jar, <a> URL parsing, location
//     derived from the provider's real code URL);
//   - controlled, descriptive errors wherever it cannot (no DOM rendering,
//     no navigation, no native modules), so a provider fails with an exact
//     statement of what is missing instead of a cryptic crash.
//
// Nothing here is provider-specific: every provider gets the same layer.

// -----------------------------------------------------------------------------
// Encoding core (shared by Buffer, crypto and TextEncoder)
// -----------------------------------------------------------------------------

function __sbNormalizeEncoding(enc) {
  if (enc == null) { return "utf8"; }
  var e = String(enc).toLowerCase();
  if (e === "" || e === "utf8" || e === "utf-8") { return "utf8"; }
  if (e === "latin1" || e === "binary") { return "latin1"; }
  if (e === "ascii") { return "ascii"; }
  if (e === "base64") { return "base64"; }
  if (e === "base64url") { return "base64url"; }
  if (e === "hex") { return "hex"; }
  if (e === "utf16le" || e === "utf-16le" || e === "ucs2" || e === "ucs-2") { return "utf16le"; }
  throw new Error("Unsupported encoding: " + enc);
}

function __sbBase64UrlToBase64(s) {
  var out = String(s).replace(/-/g, "+").replace(/_/g, "/");
  while (out.length % 4 !== 0) { out += "="; }
  return out;
}

function __sbBase64ToBase64Url(s) {
  return String(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Bytes -> binary string (for btoa), chunked to avoid argument limits. */
function __sbBytesToBinaryString(u8) {
  var binary = "";
  var CHUNK = 0x8000;
  for (var j = 0; j < u8.length; j += CHUNK) {
    var end = Math.min(j + CHUNK, u8.length);
    var part = "";
    for (var m = j; m < end; m++) { part += String.fromCharCode(u8[m]); }
    binary += part;
  }
  return binary;
}

function __sbEncodeString(str, enc) {
  var s = String(str == null ? "" : str);
  switch (enc) {
    case "utf8":
      return new Uint8Array(__sbUtf8Encode(s));
    case "latin1":
    case "ascii": {
      var out = new Uint8Array(s.length);
      for (var i = 0; i < s.length; i++) { out[i] = s.charCodeAt(i) & 0xff; }
      return out;
    }
    case "base64":
    case "base64url": {
      var b64 = enc === "base64url" ? __sbBase64UrlToBase64(s) : s;
      var bin = atob(b64.replace(/\s+/g, ""));
      var bytes = new Uint8Array(bin.length);
      for (var k = 0; k < bin.length; k++) { bytes[k] = bin.charCodeAt(k); }
      return bytes;
    }
    case "hex": {
      var clean = s.replace(/\s+/g, "");
      if (clean.length % 2 !== 0 || /[^0-9a-fA-F]/.test(clean)) {
        throw new Error("Invalid hex string");
      }
      var hb = new Uint8Array(clean.length / 2);
      for (var h = 0; h < hb.length; h++) {
        hb[h] = parseInt(clean.substr(h * 2, 2), 16);
      }
      return hb;
    }
    case "utf16le": {
      var wb = new Uint8Array(s.length * 2);
      for (var w = 0; w < s.length; w++) {
        var code = s.charCodeAt(w);
        wb[w * 2] = code & 0xff;
        wb[w * 2 + 1] = (code >> 8) & 0xff;
      }
      return wb;
    }
    default:
      throw new Error("Unsupported encoding: " + enc);
  }
}

function __sbDecodeBytes(bytes, enc, start, end) {
  var u8 = bytes;
  if (start == null) { start = 0; }
  if (end == null) { end = u8.length; }
  start = Math.max(0, start | 0);
  end = Math.min(u8.length, end | 0);
  if (end < start) { end = start; }
  switch (enc) {
    case "utf8": {
      var view = u8.subarray(start, end);
      return new TextDecoder("utf-8").decode(view);
    }
    case "latin1":
    case "ascii": {
      var out = "";
      for (var i = start; i < end; i++) {
        var b = u8[i] & 0xff;
        out += String.fromCharCode(enc === "ascii" ? (b & 0x7f) : b);
      }
      return out;
    }
    case "base64":
      return btoa(__sbBytesToBinaryString(u8.subarray(start, end)));
    case "base64url":
      return __sbBase64ToBase64Url(btoa(__sbBytesToBinaryString(u8.subarray(start, end))));
    case "hex": {
      var hex = "";
      for (var h = start; h < end; h++) {
        var v = u8[h] & 0xff;
        hex += (v < 16 ? "0" : "") + v.toString(16);
      }
      return hex;
    }
    case "utf16le": {
      var str = "";
      for (var w = start; w + 1 < end; w += 2) {
        str += String.fromCharCode(u8[w] | (u8[w + 1] << 8));
      }
      return str;
    }
    default:
      throw new Error("Unsupported encoding: " + enc);
  }
}

// -----------------------------------------------------------------------------
// Buffer
// -----------------------------------------------------------------------------

class Buffer extends Uint8Array {
  constructor(arg, encoding) {
    if (arguments.length >= 3 && arg instanceof ArrayBuffer &&
        typeof arguments[1] === "number") {
      // Species-constructor call from subarray() on a Buffer: share the
      // parent's memory, exactly like Node.
      super(arg, arguments[1], arguments[2]);
    } else if (typeof arg === "number") {
      if (arg < 0 || arg > 0x7fffffff || Math.floor(arg) !== arg) {
        throw new RangeError("Invalid typed array length: " + arg);
      }
      super(arg);
    } else if (typeof arg === "string") {
      super(__sbEncodeString(arg, __sbNormalizeEncoding(encoding)));
    } else if (arg && arg.type === "Buffer" && Array.isArray(arg.data)) {
      super(arg.data);
    } else {
      super(arg);
    }
  }
}
Object.defineProperty(Buffer.prototype, Symbol.toStringTag, { value: "Buffer" });

Buffer.from = function(value, encodingOrOffset, length) {
  if (typeof value === "string") {
    return new Buffer(value, encodingOrOffset);
  }
  if (typeof value === "number") {
    return new Buffer(value);
  }
  if (value instanceof ArrayBuffer || ArrayBuffer.isView(value) ||
      Array.isArray(value)) {
    if (ArrayBuffer.isView(value) && typeof encodingOrOffset === "number" &&
        typeof length === "number") {
      // Buffer.from(view, byteOffset, length)
      var bytes = new Uint8Array(value.buffer, value.byteOffset + encodingOrOffset, length);
      var copy = new Buffer(bytes.length);
      copy.set(bytes);
      return copy;
    }
    return new Buffer(value);
  }
  if (value && value.type === "Buffer" && Array.isArray(value.data)) {
    return new Buffer(value);
  }
  throw new TypeError("Buffer.from(): unsupported value type " + (typeof value));
};

Buffer.alloc = function(size, fill, encoding) {
  var buf = new Buffer(size);
  if (fill !== undefined && fill !== null && size > 0) { buf.fill(fill, 0, size, encoding); }
  return buf;
};
// The engine zeroes fresh memory, so "unsafe" is equally zeroed here.
Buffer.allocUnsafe = function(size) { return Buffer.alloc(size); };
Buffer.allocUnsafeSlow = function(size) { return Buffer.alloc(size); };

Buffer.isBuffer = function(value) { return value instanceof Buffer; };

Buffer.byteLength = function(value, encoding) {
  if (typeof value === "string") {
    return __sbEncodeString(value, __sbNormalizeEncoding(encoding)).length;
  }
  if (value && value.length != null) { return value.length; }
  throw new TypeError("Buffer.byteLength(): unsupported value");
};

Buffer.concat = function(list) {
  var total = 0;
  for (var i = 0; i < list.length; i++) {
    if (!ArrayBuffer.isView(list[i])) {
      throw new TypeError("Buffer.concat(): list must contain only Buffers");
    }
    total += list[i].length;
  }
  var out = new Buffer(total);
  var offset = 0;
  for (var j = 0; j < list.length; j++) {
    out.set(list[j], offset);
    offset += list[j].length;
  }
  return out;
};

Buffer.compare = function(a, b) {
  if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b)) {
    throw new TypeError("Buffer.compare(): arguments must be Buffers");
  }
  return a.compare(b);
};

Buffer.prototype.toString = function(encoding, start, end) {
  return __sbDecodeBytes(this, __sbNormalizeEncoding(encoding), start, end);
};

Buffer.prototype.toJSON = function() {
  var data = new Array(this.length);
  for (var i = 0; i < this.length; i++) { data[i] = this[i]; }
  return { type: "Buffer", data: data };
};

Buffer.prototype.inspect = function(depth, opts) {
  var shown = Math.min(this.length, 50);
  var parts = [];
  for (var i = 0; i < shown; i++) {
    parts.push((this[i] < 16 ? "0" : "") + this[i].toString(16));
  }
  return "<Buffer " + parts.join(" ") + (this.length > shown ? " ... " : "") + ">";
};
Object.defineProperty(Buffer.prototype, Symbol.for("nodejs.util.inspect.custom"),
  { value: Buffer.prototype.inspect });

Buffer.prototype.subarray = function(start, end) {
  // TypedArray.subarray uses the species constructor; our constructor
  // handles the (arrayBuffer, byteOffset, length) form by sharing memory.
  return Uint8Array.prototype.subarray.call(this, start, end);
};

Buffer.prototype.slice = function(start, end) {
  var len = this.length;
  var s = start == null ? 0 : (start < 0 ? Math.max(len + start, 0) : Math.min(start, len));
  var e = end == null ? len : (end < 0 ? Math.max(len + end, 0) : Math.min(end, len));
  var out = new Buffer(Math.max(0, e - s));
  for (var i = 0; i < out.length; i++) { out[i] = this[s + i]; }
  return out;
};

Buffer.prototype.copy = function(target, targetStart, sourceStart, sourceEnd) {
  if (!ArrayBuffer.isView(target)) { throw new TypeError("copy(): target must be a typed array"); }
  var ts = targetStart == null ? 0 : targetStart;
  var ss = sourceStart == null ? 0 : sourceStart;
  var se = sourceEnd == null ? this.length : sourceEnd;
  var count = Math.max(0, Math.min(se - ss, target.length - ts, this.length - ss));
  for (var i = 0; i < count; i++) { target[ts + i] = this[ss + i]; }
  return count;
};

Buffer.prototype.equals = function(other) {
  if (!Buffer.isBuffer(other)) { throw new TypeError("equals(): argument must be a Buffer"); }
  return this.compare(other) === 0;
};

Buffer.prototype.compare = function(other, targetStart, targetEnd, sourceStart, sourceEnd) {
  // Common single-argument form (and the full form, Node-compatible).
  var aStart = sourceStart == null ? 0 : sourceStart;
  var aEnd = sourceEnd == null ? this.length : sourceEnd;
  var bStart = targetStart == null ? 0 : targetStart;
  var bEnd = targetEnd == null ? other.length : targetEnd;
  var len = Math.min(aEnd - aStart, bEnd - bStart);
  for (var i = 0; i < len; i++) {
    var diff = this[aStart + i] - other[bStart + i];
    if (diff !== 0) { return diff < 0 ? -1 : 1; }
  }
  var lenDiff = (aEnd - aStart) - (bEnd - bStart);
  return lenDiff === 0 ? 0 : (lenDiff < 0 ? -1 : 1);
};

Buffer.prototype.fill = function(value, start, end, encoding) {
  var len = this.length;
  var s = start == null ? 0 : (start < 0 ? Math.max(len + start, 0) : Math.min(start, len));
  var e = end == null ? len : (end < 0 ? Math.max(len + end, 0) : Math.min(end, len));
  if (e <= s) { return this; }
  var bytes;
  if (typeof value === "number") {
    bytes = [value & 0xff];
  } else if (typeof value === "string") {
    bytes = __sbEncodeString(value, __sbNormalizeEncoding(encoding));
    if (bytes.length === 0) { bytes = [0]; }
  } else if (Buffer.isBuffer(value)) {
    bytes = value;
  } else {
    throw new TypeError("fill(): unsupported fill value");
  }
  for (var i = s; i < e; i++) { this[i] = bytes[(i - s) % bytes.length]; }
  return this;
};

Buffer.prototype.write = function(string, offset, length, encoding) {
  var enc = __sbNormalizeEncoding(
    typeof offset === "string" ? offset :
    typeof length === "string" ? length : encoding);
  var off = typeof offset === "number" ? offset : 0;
  var max = typeof length === "number" ? Math.min(length, this.length - off) : this.length - off;
  var bytes = __sbEncodeString(string, enc);
  var count = Math.min(max, bytes.length);
  for (var i = 0; i < count; i++) { this[off + i] = bytes[i]; }
  return count;
};

Buffer.prototype.indexOf = function(value, byteOffset, encoding) {
  return __sbBufferIndexOf(this, value, byteOffset, encoding, false);
};
Buffer.prototype.lastIndexOf = function(value, byteOffset, encoding) {
  return __sbBufferIndexOf(this, value, byteOffset, encoding, true);
};
Buffer.prototype.includes = function(value, byteOffset, encoding) {
  return this.indexOf(value, byteOffset, encoding) !== -1;
};

function __sbBufferIndexOf(buf, value, byteOffset, encoding, last) {
  var enc = __sbNormalizeEncoding(
    typeof byteOffset === "string" ? byteOffset : encoding);
  var from = typeof byteOffset === "number" ?
    (byteOffset < 0 ? buf.length + byteOffset : byteOffset) : (last ? 0 : 0);
  if (from < 0) { from = 0; }
  var needle;
  if (typeof value === "string") {
    needle = __sbEncodeString(value, enc);
  } else if (typeof value === "number") {
    needle = [value & 0xff];
  } else if (ArrayBuffer.isView(value)) {
    needle = value;
  } else {
    throw new TypeError("indexOf(): unsupported search value");
  }
  if (needle.length === 0) { return last ? buf.length : from; }
  if (last) {
    for (var i = buf.length - needle.length; i >= from; i--) {
      var ok = true;
      for (var j = 0; j < needle.length; j++) {
        if (buf[i + j] !== needle[j]) { ok = false; break; }
      }
      if (ok) { return i; }
    }
    return -1;
  }
  outer:
  for (var k = from; k <= buf.length - needle.length; k++) {
    for (var m = 0; m < needle.length; m++) {
      if (buf[k + m] !== needle[m]) { continue outer; }
    }
    return k;
  }
  return -1;
}

// Numeric reads/writes via DataView semantics.
function __sbDataView(buf) {
  return new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
}
function __sbReadNumber(buf, offset, bytes, littleEndian, signed) {
  if (offset < 0 || offset + bytes > buf.length) {
    throw new RangeError("Index out of range");
  }
  var view = __sbDataView(buf);
  if (bytes === 1) {
    var v = view.getUint8(offset);
    return signed && v & 0x80 ? v - 0x100 : v;
  }
  if (bytes === 2) {
    var v2 = view.getInt16(offset, littleEndian);
    return signed ? v2 : (v2 < 0 ? v2 + 0x10000 : v2);
  }
  if (bytes === 4) {
    var v4 = view.getInt32(offset, littleEndian);
    return signed ? v4 : (v4 < 0 ? v4 + 0x100000000 : v4);
  }
  throw new Error("Unsupported read width");
}
function __sbWriteNumber(buf, offset, value, bytes, littleEndian, signed) {
  if (offset < 0 || offset + bytes > buf.length) {
    throw new RangeError("Index out of range");
  }
  var view = __sbDataView(buf);
  if (bytes === 1) {
    view.setUint8(offset, value & 0xff);
  } else if (bytes === 2) {
    view.setInt16(offset, signed ? value : (value & 0xffff), littleEndian);
    if (!signed) { view.setUint16(offset, value & 0xffff, littleEndian); }
  } else if (bytes === 4) {
    if (signed) { view.setInt32(offset, value, littleEndian); }
    else { view.setUint32(offset, value >>> 0, littleEndian); }
  } else {
    throw new Error("Unsupported write width");
  }
}
__sbDefineIntAccessors(Buffer.prototype);

function __sbDefineIntAccessors(proto) {
  var specs = [
    ["Int8", 1, false, true], ["UInt8", 1, false, false],
    ["Int16LE", 2, true, true], ["Int16BE", 2, false, true],
    ["UInt16LE", 2, true, false], ["UInt16BE", 2, false, false],
    ["Int32LE", 4, true, true], ["Int32BE", 4, false, true],
    ["UInt32LE", 4, true, false], ["UInt32BE", 4, false, false]
  ];
  for (var i = 0; i < specs.length; i++) {
    (function(spec) {
      var name = spec[0], bytes = spec[1], le = spec[2], signed = spec[3];
      proto["read" + name] = function(offset) {
        return __sbReadNumber(this, offset, bytes, le, signed);
      };
      proto["write" + name] = function(value, offset) {
        __sbWriteNumber(this, offset, Number(value), bytes, le, signed);
      };
    })(specs[i]);
  }
  proto.readDoubleLE = function(offset) { return __sbDataView(this).getFloat64(offset, true); };
  proto.writeDoubleLE = function(value, offset) { __sbDataView(this).setFloat64(offset, Number(value), true); };
  proto.readDoubleBE = function(offset) { return __sbDataView(this).getFloat64(offset, false); };
  proto.writeDoubleBE = function(value, offset) { __sbDataView(this).setFloat64(offset, Number(value), false); };
  proto.readFloatLE = function(offset) { return __sbDataView(this).getFloat32(offset, true); };
  proto.writeFloatLE = function(value, offset) { __sbDataView(this).setFloat32(offset, Number(value), true); };
  proto.readFloatBE = function(offset) { return __sbDataView(this).getFloat32(offset, false); };
  proto.writeFloatBE = function(value, offset) { __sbDataView(this).setFloat32(offset, Number(value), false); };
}

// -----------------------------------------------------------------------------
// Module registry and require()
// -----------------------------------------------------------------------------

var __sbModuleExports = Object.create(null);
var __sbModuleInit = Object.create(null);
var __sbModuleSource = Object.create(null);

function __sbRegisterBuiltin(name, init) {
  __sbModuleInit[String(name)] = init;
}

/** Host hook: register a pre-fetched bundle (e.g. cheerio) as a module. */
globalThis.__sbRegisterModuleSource = function(name, source) {
  __sbModuleSource[String(name)] = String(source);
};

function __sbProviderHref() {
  if (typeof location === "object" && location && location.href) {
    return location.href;
  }
  return "";
}

function __sbModuleFilename(key) {
  var href = __sbProviderHref();
  if (!href) { return String(key); }
  if (key.charAt(0) === "/" || key.indexOf("://") !== -1) { return String(key); }
  return __sbResolveUrl(href, String(key));
}

function __sbModuleDirname(filename) {
  var index = String(filename).lastIndexOf("/");
  return index > 0 ? String(filename).slice(0, index) : "/";
}

function __sbHasModule(key) {
  return Object.prototype.hasOwnProperty.call(__sbModuleExports, key) ||
    Object.prototype.hasOwnProperty.call(__sbModuleInit, key) ||
    Object.prototype.hasOwnProperty.call(__sbModuleSource, key);
}

function __sbLoadModule(key, filename) {
  if (Object.prototype.hasOwnProperty.call(__sbModuleExports, key)) {
    return __sbModuleExports[key];
  }
  if (Object.prototype.hasOwnProperty.call(__sbModuleInit, key)) {
    var mod = { exports: {} };
    __sbModuleInit[key](mod, require);
    __sbModuleExports[key] = mod.exports;
    return mod.exports;
  }
  var source = __sbModuleSource[key];
  delete __sbModuleSource[key];
  var bundleModule = { exports: {} };
  // The bundle sees its own module/exports; failures surface as the
  // provider's error, never as a sandbox crash.
  __sbModuleExports[key] = bundleModule.exports;
  try {
    (new Function("module", "exports", "require", "__filename", "__dirname", source))(
      bundleModule, bundleModule.exports, require,
      filename, __sbModuleDirname(filename));
  } catch (e) {
    delete __sbModuleExports[key];
    throw e;
  }
  __sbModuleExports[key] = bundleModule.exports;
  return bundleModule.exports;
}

function require(name) {
  var raw = String(name);
  var key = raw.slice(0, 5) === "node:" ? raw.slice(5) : raw;
  if (key.charAt(0) === "." || key.charAt(0) === "/") {
    var base = __sbProviderHref();
    var abs = base ? __sbResolveUrl(base, key) : key;
    if (__sbHasModule(abs)) { return __sbLoadModule(abs, abs); }
    if (__sbHasModule(raw)) { return __sbLoadModule(raw, abs); }
    var relErr = new Error(
      "Cannot find module '" + raw + "': it was not found next to the provider " +
      "(MODULE_NOT_FOUND)");
    relErr.code = "MODULE_NOT_FOUND";
    throw relErr;
  }
  if (__sbHasModule(key)) { return __sbLoadModule(key, key); }
  var err = new Error(
    "Cannot find module '" + raw + "': require('" + raw + "') is not available in the " +
    "StreamBridge provider sandbox (MODULE_NOT_FOUND)");
  err.code = "MODULE_NOT_FOUND";
  throw err;
}

// -----------------------------------------------------------------------------
// Timers — real delays through the host clock
// -----------------------------------------------------------------------------

var __sbTimers = { seq: 0, pending: Object.create(null), count: 0 };
var __sbTimerCompletions = Object.create(null);
var __SB_MAX_TIMER_MS = 30000;
var __SB_MAX_PENDING_TIMERS = 200;

function __sbIsPendingTimer(id) {
  return id != null &&
    Object.prototype.hasOwnProperty.call(__sbTimers.pending, id);
}

function __sbHasPendingTimers() {
  for (var k in __sbTimerCompletions) {
    if (Object.prototype.hasOwnProperty.call(__sbTimerCompletions, k)) {
      return true;
    }
  }
  return false;
}

function setTimeout(fn, delay) {
  if (typeof fn !== "function") {
    throw new TypeError("setTimeout(fn): fn must be a function");
  }
  var ms = Number(delay);
  if (!isFinite(ms) || ms < 0) { ms = 0; }
  if (ms > __SB_MAX_TIMER_MS) { ms = __SB_MAX_TIMER_MS; }
  if (__sbTimers.count >= __SB_MAX_PENDING_TIMERS) {
    throw new Error("Too many pending timers in the provider sandbox");
  }
  var id = ++__sbTimers.seq;
  var args = Array.prototype.slice.call(arguments, 2);
  __sbTimers.pending[id] = true;
  __sbTimers.count++;
  var completion = __sbSetTimeout(id, ms).then(function() {
    if (!__sbIsPendingTimer(id)) { return; }
    delete __sbTimers.pending[id];
    __sbTimers.count--;
    fn.apply(undefined, args);
  });
  __sbTimerCompletions[id] = completion;
  // The completion must leave the idle-map once settled, or the idle
  // promise would spin on resolved entries forever.
  completion.then(function() { delete __sbTimerCompletions[id]; });
  return id;
}

function clearTimeout(id) {
  if (__sbIsPendingTimer(id)) {
    delete __sbTimers.pending[id];
    __sbTimers.count--;
  }
}

function setInterval(fn, delay) {
  if (typeof fn !== "function") {
    throw new TypeError("setInterval(fn): fn must be a function");
  }
  var ms = Number(delay);
  if (!isFinite(ms) || ms < 0) { ms = 0; }
  if (ms > __SB_MAX_TIMER_MS) { ms = __SB_MAX_TIMER_MS; }
  if (__sbTimers.count >= __SB_MAX_PENDING_TIMERS) {
    throw new Error("Too many pending timers in the provider sandbox");
  }
  var id = ++__sbTimers.seq;
  var args = Array.prototype.slice.call(arguments, 2);
  __sbTimers.pending[id] = "interval";
  __sbTimers.count++;
  var completion = (function tick() {
    return __sbSetTimeout(id, ms).then(function() {
      if (__sbTimers.pending[id] !== "interval") { return; }
      fn.apply(undefined, args);
      if (__sbTimers.pending[id] !== "interval") { return; }
      return tick();
    });
  })();
  __sbTimerCompletions[id] = completion;
  completion.then(function() { delete __sbTimerCompletions[id]; });
  return id;
}

function clearInterval(id) { clearTimeout(id); }

globalThis.setTimeout = setTimeout;
globalThis.clearTimeout = clearTimeout;
globalThis.setInterval = setInterval;
globalThis.clearInterval = clearInterval;

/**
 * Resolves when every pending timer has fired or been cleared — and
 * keeps waiting through timers that schedule more timers. The result
 * wrapper races this against a short grace period: fire-and-forget
 * work gets a chance to land, but a resolved provider call is never
 * stalled by an abandoned timer or an endless interval.
 */
function __sbTimerIdle() {
  var ids = [];
  for (var k in __sbTimerCompletions) {
    if (Object.prototype.hasOwnProperty.call(__sbTimerCompletions, k)) {
      ids.push(__sbTimerCompletions[k]);
    }
  }
  if (ids.length === 0) { return Promise.resolve(); }
  return Promise.all(ids).then(function() { return __sbTimerIdle(); });
}

// -----------------------------------------------------------------------------
// Microtasks
// -----------------------------------------------------------------------------

globalThis.setImmediate = function(fn) {
  var args = Array.prototype.slice.call(arguments, 1);
  return setTimeout.apply(null, [fn, 0].concat(args));
};
globalThis.clearImmediate = clearTimeout;
globalThis.queueMicrotask = function(fn) {
  Promise.resolve().then(fn).catch(function(e) {
    __sbLog("error", "unhandled microtask error: " + String(e && e.message ? e.message : e));
  });
};

// -----------------------------------------------------------------------------
// process
// -----------------------------------------------------------------------------

var __sbProcessStart = Date.now();

var process = {
  // The host's real environment is none of the provider's business;
  // env starts empty and is writable within the call.
  env: Object.create(null),
  // Deliberately NOT claiming to be Node (no versions.node): the
  // sandbox is a browser-like environment with Node API adapters, and
  // third-party feature detection must take the pure-JS/browser paths —
  // claiming Node steers libraries into fs/worker paths that cannot
  // exist here.
  version: "",
  versions: {},
  platform: "linux",
  arch: "arm64",
  pid: 0,
  argv: ["provider"],
  title: "streambridge-provider-sandbox",
  nextTick: function(fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    Promise.resolve().then(function() { fn.apply(null, args); }).catch(function(e) {
      __sbLog("error", "process.nextTick error: " + String(e && e.message ? e.message : e));
    });
  },
  cwd: function() { return "/"; },
  hrtime: {
    bigint: function() { return BigInt(Date.now() - __sbProcessStart) * 1000000n; }
  },
  uptime: function() { return (Date.now() - __sbProcessStart) / 1000; },
  on: function(event, listener) {
    // There is nothing to clean up in the sandbox (no descriptors, no
    // signals), so lifecycle hooks are accepted but never fire.
    __sbLog("debug", "process.on('" + event + "') ignored in the provider sandbox");
    return process;
  },
  off: function() { return process; },
  exit: function() {
    throw new Error(
      "process.exit() is not available in the provider sandbox: the call ends " +
      "when getStreams() settles");
  },
  kill: function() {
    throw new Error("process.kill() is not available in the provider sandbox");
  }
};
globalThis.process = process;

// -----------------------------------------------------------------------------
// events
// -----------------------------------------------------------------------------

function EventEmitter() {
  if (!(this instanceof EventEmitter)) { return new EventEmitter(); }
  this._events = Object.create(null);
}
EventEmitter.prototype.setMaxListeners = function(n) { this._maxListeners = n; return this; };
EventEmitter.prototype.getMaxListeners = function() {
  return this._maxListeners || EventEmitter.defaultMaxListeners;
};
EventEmitter.prototype._listenersFor = function(event) {
  var key = String(event);
  if (!Object.prototype.hasOwnProperty.call(this._events, key)) {
    this._events[key] = [];
  }
  return this._events[key];
};
EventEmitter.prototype.on = function(event, listener) {
  if (typeof listener !== "function") { throw new TypeError("listener must be a function"); }
  this._listenersFor(event).push(listener);
  return this;
};
EventEmitter.prototype.addListener = EventEmitter.prototype.on;
EventEmitter.prototype.prependListener = function(event, listener) {
  if (typeof listener !== "function") { throw new TypeError("listener must be a function"); }
  this._listenersFor(event).unshift(listener);
  return this;
};
EventEmitter.prototype.once = function(event, listener) {
  if (typeof listener !== "function") { throw new TypeError("listener must be a function"); }
  var self = this;
  function wrapped() {
    self.removeListener(event, wrapped);
    listener.apply(self, arguments);
  }
  wrapped.listener = listener;
  return this.on(event, wrapped);
};
EventEmitter.prototype.prependOnceListener = function(event, listener) {
  if (typeof listener !== "function") { throw new TypeError("listener must be a function"); }
  var self = this;
  function wrapped() {
    self.removeListener(event, wrapped);
    listener.apply(self, arguments);
  }
  wrapped.listener = listener;
  return this.prependListener(event, wrapped);
};
EventEmitter.prototype.removeListener = function(event, listener) {
  var list = this._events[String(event)];
  if (!list) { return this; }
  for (var i = 0; i < list.length; i++) {
    if (list[i] === listener || list[i].listener === listener) {
      list.splice(i, 1);
      break;
    }
  }
  if (list.length === 0) { delete this._events[String(event)]; }
  return this;
};
EventEmitter.prototype.off = EventEmitter.prototype.removeListener;
EventEmitter.prototype.removeAllListeners = function(event) {
  if (event == null) { this._events = Object.create(null); }
  else { delete this._events[String(event)]; }
  return this;
};
EventEmitter.prototype.emit = function(event) {
  var key = String(event);
  var list = this._events[key];
  if (!list || list.length === 0) {
    if (key === "error") {
      var arg = arguments[1];
      throw (arg instanceof Error) ? arg : new Error("Uncaught 'error' event: " + String(arg));
    }
    return false;
  }
  var args = Array.prototype.slice.call(arguments, 1);
  var snapshot = list.slice();
  for (var i = 0; i < snapshot.length; i++) {
    snapshot[i].apply(this, args);
  }
  return true;
};
EventEmitter.prototype.eventNames = function() {
  return Object.keys(this._events);
};
EventEmitter.prototype.listeners = function(event) {
  var list = this._events[String(event)] || [];
  return list.map(function(l) { return l.listener || l; });
};
EventEmitter.prototype.listenerCount = function(event) {
  var list = this._events[String(event)];
  return list ? list.length : 0;
};
EventEmitter.defaultMaxListeners = 10;
EventEmitter.listenerCount = function(emitter, event) {
  return emitter.listenerCount(event);
};

globalThis.EventEmitter = EventEmitter;

// -----------------------------------------------------------------------------
// path (POSIX semantics — the sandbox is a Linux-like host)
// -----------------------------------------------------------------------------

var path = {};

path.sep = "/";
path.delimiter = ":";

path.isAbsolute = function(p) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  return p.length > 0 && p.charAt(0) === "/";
};

path.normalize = function(p) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  if (p === "") { return "."; }
  var isAbs = p.charAt(0) === "/";
  var trailingSlash = p.charAt(p.length - 1) === "/";
  var parts = p.split("/");
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var s = parts[i];
    if (s === "" || s === ".") { continue; }
    if (s === "..") {
      if (out.length > 0 && out[out.length - 1] !== "..") {
        out.pop();
      } else if (!isAbs) {
        out.push("..");
      }
      continue;
    }
    out.push(s);
  }
  var result = (isAbs ? "/" : "") + out.join("/");
  if (result === "") { result = isAbs ? "/" : "."; }
  if (trailingSlash && result.charAt(result.length - 1) !== "/" && result !== "/") {
    result += "/";
  }
  return result;
};

path.join = function() {
  var parts = [];
  for (var i = 0; i < arguments.length; i++) {
    var arg = arguments[i];
    if (typeof arg !== "string") { throw new TypeError("Path must be a string"); }
    if (arg !== "") { parts.push(arg); }
  }
  if (parts.length === 0) { return "."; }
  var joined = parts.join("/");
  if (joined === "") { return "."; }
  return path.normalize(joined);
};

path.resolve = function() {
  var resolvedPath = "";
  var resolvedAbsolute = false;
  for (var i = arguments.length - 1; i >= -1 && !resolvedAbsolute; i--) {
    var p = i >= 0 ? arguments[i] : "/";
    if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
    if (p === "") { continue; }
    resolvedPath = p + "/" + resolvedPath;
    resolvedAbsolute = p.charAt(0) === "/";
  }
  if (resolvedPath === "") { return "/"; }
  resolvedPath = path.normalize(resolvedPath);
  if (resolvedAbsolute && resolvedPath.charAt(0) !== "/") { resolvedPath = "/" + resolvedPath; }
  // resolve() never ends in a slash (except the root itself).
  while (resolvedPath.length > 1 && resolvedPath.charAt(resolvedPath.length - 1) === "/") {
    resolvedPath = resolvedPath.slice(0, -1);
  }
  return resolvedPath;
};

path.relative = function(from, to) {
  if (typeof from !== "string" || typeof to !== "string") {
    throw new TypeError("Path must be a string");
  }
  var fromResolved = path.resolve(from);
  var toResolved = path.resolve(to);
  if (fromResolved === toResolved) { return ""; }
  var fromParts = fromResolved.split("/");
  var toParts = toResolved.split("/");
  var common = 0;
  while (common < fromParts.length && common < toParts.length &&
         fromParts[common] === toParts[common]) {
    common++;
  }
  var upCount = fromParts.length - common;
  var segments = [];
  for (var i = 0; i < upCount; i++) { segments.push(".."); }
  for (var j = common; j < toParts.length; j++) {
    if (toParts[j] !== "") { segments.push(toParts[j]); }
  }
  if (segments.length === 0) { return "."; }
  return segments.join("/");
};

path.dirname = function(p) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  if (p === "") { return "."; }
  var hasRoot = p.charAt(0) === "/";
  var end = -1;
  var matchedSlash = true;
  for (var i = p.length - 1; i >= 1; i--) {
    if (p.charAt(i) === "/") {
      if (!matchedSlash) { end = i; break; }
    } else {
      matchedSlash = false;
    }
  }
  if (end === -1) { return hasRoot ? "/" : "."; }
  // POSIX: preserve exactly two leading slashes ('//a' -> '//').
  if (hasRoot && end === 1) { return "//"; }
  return p.slice(0, end);
};

path.basename = function(p, ext) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  // A faithful port of Node's POSIX basename scan.
  var start = 0;
  var end = -1;
  var matchedSlash = true;
  for (var i = p.length - 1; i >= 0; i--) {
    var code = p.charAt(i);
    if (code === "/") {
      if (!matchedSlash) {
        start = i + 1;
        break;
      }
    } else if (end === -1) {
      // The first non-slash character from the right.
      matchedSlash = false;
      end = i + 1;
    }
  }
  if (end === -1) { return ""; }
  if (typeof ext === "string" && ext.length > 0 && ext.length <= end - start) {
    if (ext === p.slice(end - ext.length, end)) {
      end -= ext.length;
    }
  }
  return p.slice(start, end);
};

path.extname = function(p) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  var slash = p.lastIndexOf("/");
  var base = p.slice(slash + 1);
  var dot = base.lastIndexOf(".");
  // A leading dot marks a hidden file ('.bashrc'), and '..' is all dots.
  if (dot <= 0 || base === "..") { return ""; }
  return base.slice(dot);
};

path.parse = function(p) {
  if (typeof p !== "string") { throw new TypeError("Path must be a string"); }
  var isAbs = path.isAbsolute(p);
  var root = isAbs ? "/" : "";
  var dir = path.dirname(p);
  var base = path.basename(p);
  var ext = path.extname(p);
  var name = base.slice(0, base.length - ext.length);
  return { root: root, dir: dir, base: base, name: name, ext: ext };
};

path.format = function(obj) {
  if (obj === null || typeof obj !== "object") {
    throw new TypeError("path.format(): object required");
  }
  // Node's exact rule: dir === root concatenates without a separator.
  var dir = obj.dir || obj.root || "";
  var ext = obj.ext || "";
  if (ext !== "" && ext.charAt(0) !== ".") { ext = "." + ext; }
  var base = obj.base || ((obj.name || "") + ext);
  if (dir === "") { return base; }
  return dir === obj.root ? dir + base : dir + "/" + base;
};

path.posix = path;
// The sandbox never runs Windows providers; win32 mirrors posix so that
// code probing path.win32 does not crash (behavior documented as posix).
path.win32 = path;

// -----------------------------------------------------------------------------
// querystring
// -----------------------------------------------------------------------------

var querystring = {};

querystring.escape = function(str) {
  return encodeURIComponent(String(str));
};
querystring.encode = querystring.escape;
querystring.unescape = function(str) {
  try {
    return decodeURIComponent(String(str).replace(/\+/g, " "));
  } catch (e) {
    return String(str);
  }
};
querystring.decode = querystring.unescape;

querystring.parse = function(qs, sep, eq, options) {
  var obj = Object.create(null);
  if (typeof qs !== "string" || qs.length === 0) { return obj; }
  var sepStr = sep || "&";
  var eqStr = eq || "=";
  var maxKeys = options && typeof options.maxKeys === "number" ?
    options.maxKeys : 1000;
  var parts = qs.split(sepStr);
  var count = 0;
  for (var i = 0; i < parts.length; i++) {
    if (maxKeys > 0 && count >= maxKeys) { break; }
    var pair = parts[i];
    if (pair === "") { continue; }
    var eqIndex = pair.indexOf(eqStr);
    var key, value;
    if (eqIndex === -1) {
      key = querystring.unescape(pair);
      value = "";
    } else {
      key = querystring.unescape(pair.slice(0, eqIndex));
      value = querystring.unescape(pair.slice(eqIndex + eqStr.length));
    }
    if (Object.prototype.hasOwnProperty.call(obj, key)) {
      if (!Array.isArray(obj[key])) { obj[key] = [obj[key]]; }
      obj[key].push(value);
    } else {
      obj[key] = value;
    }
    count++;
  }
  return obj;
};

querystring.stringify = function(obj, sep, eq) {
  if (obj === null || typeof obj !== "object") { return ""; }
  var sepStr = sep || "&";
  var eqStr = eq || "=";
  var parts = [];
  for (var key in obj) {
    if (!Object.prototype.hasOwnProperty.call(obj, key)) { continue; }
    var value = obj[key];
    var encodedKey = querystring.escape(key);
    if (Array.isArray(value)) {
      for (var i = 0; i < value.length; i++) {
        parts.push(encodedKey + eqStr + querystring.escape(value[i]));
      }
    } else {
      parts.push(encodedKey + eqStr + querystring.escape(value));
    }
  }
  return parts.join(sepStr);
};

// -----------------------------------------------------------------------------
// url (legacy Node interface on top of the prelude's URL machinery)
// -----------------------------------------------------------------------------

var url = {};

url.parse = function(urlStr, parseQueryString, slashesDenoteHost) {
  if (typeof urlStr !== "string") { throw new TypeError("Parameter 'url' must be a string"); }
  var href = String(urlStr);
  var out = {
    href: href, protocol: null, slashes: null, auth: null,
    host: null, port: null, hostname: null, hash: null,
    search: null, query: null, pathname: null, path: null
  };
  var rest = href;
  var hashIndex = rest.indexOf("#");
  if (hashIndex !== -1) {
    out.hash = rest.slice(hashIndex);
    rest = rest.slice(0, hashIndex);
  }
  var searchIndex = rest.indexOf("?");
  if (searchIndex !== -1) {
    out.search = rest.slice(searchIndex);
    rest = rest.slice(0, searchIndex);
  }
  var protoMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(rest);
  if (protoMatch) {
    out.protocol = protoMatch[1].toLowerCase() + ":";
    rest = rest.slice(protoMatch[0].length);
    out.slashes = rest.slice(0, 2) === "//";
  } else if (slashesDenoteHost && rest.slice(0, 2) === "//") {
    out.slashes = true;
    // 'protocol-less //host/path' inherits http semantics for parsing.
    rest = "http:" + rest;
    var parsed = url.parse(rest, false, false);
    out.protocol = null;
    out.slashes = true;
    out.auth = parsed.auth;
    out.host = parsed.host;
    out.port = parsed.port;
    out.hostname = parsed.hostname;
    out.pathname = parsed.pathname;
    out.path = parsed.path;
    out.search = parsed.search;
    out.query = parseQueryString ? querystring.parse((out.search || "").replace(/^\?/, "")) : (out.search ? out.search.slice(1) : null);
    out.hash = parsed.hash != null ? parsed.hash : out.hash;
    return out;
  }
  if (out.slashes || /^\/\//.test(href) === false && out.protocol === null) {
    // No protocol: fall through to the path-only branch below.
  }
  if (out.protocol != null && rest.slice(0, 2) === "//") {
    rest = rest.slice(2);
    var slashIndex = rest.indexOf("/");
    var authority = slashIndex === -1 ? rest : rest.slice(0, slashIndex);
    var pathPart = slashIndex === -1 ? "/" : rest.slice(slashIndex);
    var atIndex = authority.lastIndexOf("@");
    if (atIndex !== -1) {
      out.auth = authority.slice(0, atIndex);
      authority = authority.slice(atIndex + 1);
    }
    var colonIndex = authority.lastIndexOf(":");
    if (colonIndex !== -1 && authority.indexOf("]") === -1) {
      out.port = authority.slice(colonIndex + 1);
      out.hostname = authority.slice(0, colonIndex);
    } else {
      out.hostname = authority;
    }
    out.host = out.port ? out.hostname + ":" + out.port : out.hostname;
    var qIdx = pathPart.indexOf("?");
    if (qIdx !== -1) {
      out.search = pathPart.slice(qIdx);
      pathPart = pathPart.slice(0, qIdx);
    }
    out.pathname = pathPart === "" ? "/" : pathPart;
  } else {
    out.pathname = rest;
  }
  out.path = out.pathname + (out.search || "");
  if (parseQueryString) {
    out.query = querystring.parse((out.search || "").replace(/^\?/, ""));
  } else {
    out.query = out.search ? out.search.slice(1) : null;
  }
  return out;
};

url.format = function(obj) {
  if (typeof obj === "string") { return obj; }
  if (obj === null || typeof obj !== "object") {
    throw new TypeError("Parameter 'urlObject' must be an object or a string");
  }
  var protocol = obj.protocol || "";
  if (protocol && protocol.charAt(protocol.length - 1) !== ":") { protocol += ":"; }
  var auth = obj.auth || "";
  var host = obj.host || ((obj.hostname || "") + (obj.port ? ":" + obj.port : ""));
  var pathname = obj.pathname || "";
  var search = obj.search || (obj.query != null && obj.query !== "" ?
    ("?" + (typeof obj.query === "string" ? obj.query :
      querystring.stringify(obj.query))) : "");
  var hash = obj.hash || "";
  var slashes = obj.slashes != null ? obj.slashes :
    (protocol === "http:" || protocol === "https:" || protocol === "ftp:");
  var result = protocol + (slashes ? "//" : "") +
    (auth ? auth + "@" : "") + host + pathname + search + hash;
  return result;
};

url.resolve = function(from, to) {
  return __sbResolveUrl(String(from), String(to));
};

url.Url = function Url() {};
url.URL = URL;
url.URLSearchParams = URLSearchParams;

// -----------------------------------------------------------------------------
// util
// -----------------------------------------------------------------------------

var util = {};

util.inspect = function inspect(value, opts) {
  var depth = opts && typeof opts.depth === "number" ? opts.depth : 2;
  var seen = (typeof WeakSet === "function") ? new WeakSet() : null;
  function fmt(v, d) {
    if (v === null) { return "null"; }
    if (v === undefined) { return "undefined"; }
    if (typeof v === "string") { return d === 0 ? "'" + v + "'" : JSON.stringify(v); }
    if (typeof v === "number" || typeof v === "boolean" || typeof v === "bigint") {
      return String(v);
    }
    if (typeof v === "function") {
      var name = v.name ? ": " + v.name : "";
      return "[Function" + name + "]";
    }
    if (typeof v === "symbol") { return v.toString(); }
    if (v instanceof Error) {
      return v.stack || (v.name + ": " + v.message);
    }
    if (v instanceof Date) { return v.toISOString(); }
    if (v instanceof RegExp) { return v.toString(); }
    if (Buffer.isBuffer(v)) { return v.inspect(); }
    if (seen && (typeof v === "object")) {
      if (seen.has(v)) { return "[Circular]"; }
      seen.add(v);
    }
    if (Array.isArray(v)) {
      if (d < 0) { return "[Array]"; }
      var items = [];
      for (var i = 0; i < Math.min(v.length, 40); i++) {
        items.push(fmt(v[i], d - 1));
      }
      if (v.length > 40) { items.push("... " + (v.length - 40) + " more"); }
      return items.length === 0 ? "[]" : "[ " + items.join(", ") + " ]";
    }
    if (typeof Map === "function" && v instanceof Map) {
      if (d < 0) { return "[Map]"; }
      var m = [];
      v.forEach(function(val, k) { m.push(fmt(k, d - 1) + " => " + fmt(val, d - 1)); });
      return "Map(" + v.size + ") { " + m.join(", ") + " }";
    }
    if (typeof Set === "function" && v instanceof Set) {
      if (d < 0) { return "[Set]"; }
      var s = [];
      v.forEach(function(val) { s.push(fmt(val, d - 1)); });
      return "Set(" + v.size + ") { " + s.join(", ") + " }";
    }
    if (typeof v === "object") {
      if (d < 0) { return "[Object]"; }
      var keys = Object.keys(v);
      var pairs = [];
      for (var k = 0; k < Math.min(keys.length, 40); k++) {
        var key = keys[k];
        pairs.push((/^[a-zA-Z_$][a-zA-Z0-9_$]*$/.test(key) ? key : JSON.stringify(key)) +
          ": " + fmt(v[key], d - 1));
      }
      if (keys.length > 40) { pairs.push("... " + (keys.length - 40) + " more"); }
      var ctor = v.constructor && v.constructor.name;
      var prefix = ctor && ctor !== "Object" ? ctor + " " : "";
      return pairs.length === 0 ? prefix + "{}" : prefix + "{ " + pairs.join(", ") + " }";
    }
    return String(v);
  }
  return fmt(value, depth);
};

util.format = function(f) {
  function display(v) {
    return (v === null || (typeof v === "object")) ? util.inspect(v) : String(v);
  }
  if (typeof f !== "string") {
    var out = [];
    for (var i = 0; i < arguments.length; i++) { out.push(display(arguments[i])); }
    return out.join(" ");
  }
  var args = arguments;
  var argIndex = 1;
  var str = f.replace(/%[sdjifoO%]/g, function(m) {
    if (m === "%%") { return "%"; }
    if (argIndex >= args.length) { return m; }
    var arg = args[argIndex++];
    switch (m) {
      case "%s": return display(arg);
      case "%d": return String(Number(arg));
      case "%i": return String(parseInt(arg, 10));
      case "%f": return String(parseFloat(arg));
      case "%j":
        try { return JSON.stringify(arg); }
        catch (e) { return "[Circular]"; }
      case "%o":
      case "%O": return util.inspect(arg);
    }
    return m;
  });
  if (argIndex < args.length) {
    var rest = [];
    for (var j = argIndex; j < args.length; j++) { rest.push(display(args[j])); }
    str += " " + rest.join(" ");
  }
  return str;
};

util.promisify = function(original) {
  if (typeof original !== "function") {
    throw new TypeError("The 'original' argument must be of type Function");
  }
  if (original[util.promisify.custom]) { return original[util.promisify.custom]; }
  function fn() {
    var args = Array.prototype.slice.call(arguments);
    var self = this;
    return new Promise(function(resolve, reject) {
      args.push(function(err, value) {
        if (err) { reject(err); }
        else if (arguments.length > 2) {
          var values = Array.prototype.slice.call(arguments, 1);
          resolve(values);
        } else {
          resolve(value);
        }
      });
      original.apply(self, args);
    });
  }
  Object.setPrototypeOf(fn, Object.getPrototypeOf(original));
  return fn;
};
util.promisify.custom = Symbol.for("util.promisify.custom");

util.callbackify = function(original) {
  if (typeof original !== "function") {
    throw new TypeError("The 'original' argument must be of type Function");
  }
  function callbackified() {
    var args = Array.prototype.slice.call(arguments);
    var maybeCb = args.pop();
    if (typeof maybeCb !== "function") {
      throw new TypeError("The last argument must be of type Function");
    }
    var cb = maybeCb;
    original.apply(this, args).then(
      function(ret) { cb(null, ret); },
      function(err) { cb(err); });
  }
  Object.setPrototypeOf(callbackified, Object.getPrototypeOf(original));
  return callbackified;
};

util.inherits = function(ctor, superCtor) {
  if (ctor === undefined || ctor === null) {
    throw new TypeError("The constructor to 'inherits' must not be null or undefined");
  }
  if (superCtor === undefined || superCtor === null) {
    throw new TypeError("The super constructor to 'inherits' must not be null or undefined");
  }
  if (superCtor.prototype === undefined) {
    throw new TypeError("The super constructor to 'inherits' must have a prototype");
  }
  ctor.super_ = superCtor;
  Object.setPrototypeOf(ctor.prototype, superCtor.prototype);
};

util.deprecate = function(fn, msg) {
  if (typeof fn !== "function") {
    throw new TypeError("The 'fn' argument must be of type Function");
  }
  var warned = false;
  function deprecated() {
    if (!warned) {
      warned = true;
      console.error("(deprecated) " + String(msg));
    }
    return fn.apply(this, arguments);
  }
  return deprecated;
};

util.isArray = Array.isArray;
util.isBoolean = function(arg) { return typeof arg === "boolean"; };
util.isNull = function(arg) { return arg === null; };
util.isNullOrUndefined = function(arg) { return arg == null; };
util.isNumber = function(arg) { return typeof arg === "number"; };
util.isString = function(arg) { return typeof arg === "string"; };
util.isSymbol = function(arg) { return typeof arg === "symbol"; };
util.isUndefined = function(arg) { return arg === undefined; };
util.isFunction = function(arg) { return typeof arg === "function"; };
util.isPrimitive = function(arg) {
  return arg === null || (typeof arg !== "object" && typeof arg !== "function");
};
util.isObject = function(arg) { return arg !== null && typeof arg === "object"; };
util.isError = function(arg) { return arg instanceof Error; };
util.isDate = function(arg) { return arg instanceof Date; };
util.isRegExp = function(arg) { return arg instanceof RegExp; };
util.isBuffer = function(arg) { return Buffer.isBuffer(arg); };

util.types = {
  isDate: util.isDate,
  isRegExp: util.isRegExp,
  isPromise: function(v) {
    return v != null && typeof v.then === "function";
  },
  isNativeError: util.isError
};

util.isDeepStrictEqual = function(a, b) {
  return __sbDeepStrictEqual(a, b, []);
};

function __sbDeepStrictEqual(a, b, stack) {
  if (a === b) { return true; }
  if (typeof a !== typeof b) { return false; }
  if (a === null || b === null || typeof a !== "object") {
    // NaN deep-equals NaN under strict semantics.
    return typeof a === "number" && typeof b === "number" &&
      isNaN(a) && isNaN(b);
  }
  for (var s = 0; s < stack.length; s += 2) {
    if (stack[s] === a && stack[s + 1] === b) { return true; }
  }
  stack.push(a, b);
  try {
    if (Array.isArray(a) || Array.isArray(b)) {
      if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) { return false; }
      for (var i = 0; i < a.length; i++) {
        if (!__sbDeepStrictEqual(a[i], b[i], stack)) { return false; }
      }
      return true;
    }
    if (a instanceof Date || b instanceof Date) {
      return a instanceof Date && b instanceof Date &&
        a.getTime() === b.getTime();
    }
    if (Buffer.isBuffer(a) || Buffer.isBuffer(b)) {
      if (!Buffer.isBuffer(a) || !Buffer.isBuffer(b) || a.length !== b.length) { return false; }
      for (var k = 0; k < a.length; k++) { if (a[k] !== b[k]) { return false; } }
      return true;
    }
    if (typeof Map === "function" && (a instanceof Map || b instanceof Map)) {
      if (!(a instanceof Map) || !(b instanceof Map) || a.size !== b.size) { return false; }
      var ok = true;
      a.forEach(function(v, key) {
        if (!b.has(key) || !__sbDeepStrictEqual(v, b.get(key), stack)) { ok = false; }
      });
      return ok;
    }
    var ka = Object.keys(a);
    var kb = Object.keys(b);
    if (ka.length !== kb.length) { return false; }
    for (var j = 0; j < ka.length; j++) {
      if (!Object.prototype.hasOwnProperty.call(b, ka[j])) { return false; }
      if (!__sbDeepStrictEqual(a[ka[j]], b[ka[j]], stack)) { return false; }
    }
    var pa = Object.getPrototypeOf(a);
    var pb = Object.getPrototypeOf(b);
    if (pa !== pb) { return false; }
    return true;
  } finally {
    stack.pop();
    stack.pop();
  }
}

// -----------------------------------------------------------------------------
// assert
// -----------------------------------------------------------------------------

function AssertionError(options) {
  if (!(this instanceof AssertionError)) { return new AssertionError(options); }
  var opts = options || {};
  this.name = "AssertionError";
  this.message = opts.message || "";
  this.actual = opts.actual;
  this.expected = opts.expected;
  this.operator = opts.operator;
  this.generatedMessage = !opts.message;
  Error.captureStackTrace = null;
  var err = new Error(this.message);
  err.name = this.name;
  this.stack = err.stack;
}
AssertionError.prototype = Object.create(Error.prototype);
AssertionError.prototype.constructor = AssertionError;
AssertionError.prototype.toString = function() {
  return this.name + " [" + this.operator + "]: " + this.message;
};

var assert = function(value, message) {
  if (!value) { throw new AssertionError({ message: message || "The expression evaluated to a falsy value", operator: "==" }); }
};
assert.ok = assert;
assert.assert = assert;

function __sbFail(actual, expected, message, operator, stackStart) {
  throw new AssertionError({
    message: message || (__sbInspectLite(actual) + " " + operator + " " + __sbInspectLite(expected)),
    actual: actual, expected: expected, operator: operator
  });
}
function __sbInspectLite(v) {
  if (typeof v === "string") { return "'" + v + "'"; }
  return util.inspect(v, { depth: 0 });
}

assert.fail = function(actual, expected, message, operator) {
  throw new AssertionError({
    message: message, actual: actual, expected: expected,
    operator: operator || "fail"
  });
};
assert.equal = function(actual, expected, message) {
  if (actual == expected) { return; }
  __sbFail(actual, expected, message, "==");
};
assert.notEqual = function(actual, expected, message) {
  if (actual != expected) { return; }
  __sbFail(actual, expected, message, "!=");
};
assert.strictEqual = function(actual, expected, message) {
  if (actual === expected) { return; }
  __sbFail(actual, expected, message, "===");
};
assert.notStrictEqual = function(actual, expected, message) {
  if (actual !== expected) { return; }
  __sbFail(actual, expected, message, "!==");
};
assert.deepEqual = function(actual, expected, message) {
  if (__sbDeepEqualLoose(actual, expected)) { return; }
  __sbFail(actual, expected, message, "deepEqual");
};
assert.notDeepEqual = function(actual, expected, message) {
  if (!__sbDeepEqualLoose(actual, expected)) { return; }
  __sbFail(actual, expected, message, "notDeepEqual");
};
assert.deepStrictEqual = function(actual, expected, message) {
  if (__sbDeepStrictEqual(actual, expected, [])) { return; }
  __sbFail(actual, expected, message, "deepStrictEqual");
};
assert.notDeepStrictEqual = function(actual, expected, message) {
  if (!__sbDeepStrictEqual(actual, expected, [])) { return; }
  __sbFail(actual, expected, message, "notDeepStrictEqual");
};
assert.throws = function(fn, expected, message) {
  if (typeof fn !== "function") {
    throw new TypeError("The 'fn' argument must be of type Function");
  }
  var threw = false;
  var error = null;
  try { fn(); } catch (e) { threw = true; error = e; }
  if (!threw) {
    throw new AssertionError({ message: message || "Missing expected exception", operator: "throws" });
  }
  if (expected === undefined) { return; }
  if (typeof expected === "function") {
    if (expected.prototype instanceof Error || expected === Error) {
      if (!(error instanceof expected)) {
        throw new AssertionError({
          message: message || "The error is not an instance of the expected class",
          actual: error, expected: expected, operator: "throws"
        });
      }
      return;
    }
    if (!expected(error)) {
      throw new AssertionError({ message: message || "The error did not match the predicate", operator: "throws" });
    }
    return;
  }
  if (typeof expected === "RegExp") {
    if (!expected.test(String(error && error.message))) {
      throw new AssertionError({ message: message || "The error did not match the pattern", operator: "throws" });
    }
    return;
  }
};
assert.doesNotThrow = function(fn, message) {
  if (typeof fn !== "function") {
    throw new TypeError("The 'fn' argument must be of type Function");
  }
  try { fn(); }
  catch (e) {
    throw new AssertionError({ message: message || "Got unwanted exception: " + String(e), operator: "doesNotThrow" });
  }
};
assert.ifError = function(value) {
  if (value) { throw value; }
};
assert.rejects = function(promiseFnOrPromise, expected, message) {
  var promise = (typeof promiseFnOrPromise === "function") ?
    promiseFnOrPromise() : promiseFnOrPromise;
  if (!promise || typeof promise.then !== "function") {
    return Promise.reject(new TypeError("The 'promiseFn' argument must return a Promise"));
  }
  return promise.then(function() {
    throw new AssertionError({ message: message || "Missing expected rejection", operator: "rejects" });
  }, function(error) {
    if (expected === undefined) { return; }
    if (typeof expected === "function" && (expected.prototype instanceof Error || expected === Error)) {
      if (!(error instanceof expected)) {
        throw new AssertionError({ message: message || "The rejection is not an instance of the expected class", operator: "rejects" });
      }
    }
    return;
  });
};
assert.doesNotReject = function(promiseFnOrPromise) {
  var promise = (typeof promiseFnOrPromise === "function") ?
    promiseFnOrPromise() : promiseFnOrPromise;
  if (!promise || typeof promise.then !== "function") {
    return Promise.reject(new TypeError("The 'promiseFn' argument must return a Promise"));
  }
  return promise.then(null, function(error) {
    throw new AssertionError({ message: "Got unwanted rejection: " + String(error), operator: "doesNotReject" });
  });
};
assert.AssertionError = AssertionError;

function __sbDeepEqualLoose(a, b, stack) {
  if (a == b) { return true; }
  if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) {
    return a == b;
  }
  stack = stack || [];
  for (var s = 0; s < stack.length; s += 2) {
    if (stack[s] === a && stack[s + 1] === b) { return true; }
  }
  stack.push(a, b);
  try {
    var ka = Object.keys(a);
    var kb = Object.keys(b);
    if (ka.length !== kb.length) { return false; }
    for (var i = 0; i < ka.length; i++) {
      if (!Object.prototype.hasOwnProperty.call(b, ka[i])) { return false; }
      if (!__sbDeepEqualLoose(a[ka[i]], b[ka[i]], stack)) { return false; }
    }
    return true;
  } finally {
    stack.pop();
    stack.pop();
  }
}

// -----------------------------------------------------------------------------
// string_decoder
// -----------------------------------------------------------------------------

function StringDecoder(encoding) {
  this._encoding = __sbNormalizeEncoding(encoding);
  this._carry = new Uint8Array(0);
  this._utf8 = { pending: new Uint8Array(0), state: 0 };
}
StringDecoder.prototype.write = function(buffer) {
  var bytes = this._toBytes(buffer);
  if (this._encoding === "utf8") {
    // Incremental UTF-8 state machine: holds a partial code point across
    // chunk boundaries, exactly like Node's decoder.
    var st = this._utf8;
    var out = "";
    for (var i = 0; i < bytes.length; i++) {
      var b = bytes[i];
      if (st.state === 0) {
        if (b < 0x80) {
          out += String.fromCharCode(b);
        } else if ((b & 0xe0) === 0xc0) {
          st.pending = new Uint8Array([b]); st.state = 1;
        } else if ((b & 0xf0) === 0xe0) {
          st.pending = new Uint8Array([b]); st.state = 2;
        } else if ((b & 0xf8) === 0xf0) {
          st.pending = new Uint8Array([b]); st.state = 3;
        } else {
          out += "\ufffd";
        }
      } else {
        if ((b & 0xc0) === 0x80) {
          var grown = new Uint8Array(st.pending.length + 1);
          grown.set(st.pending);
          grown[st.pending.length] = b;
          st.pending = grown;
          st.state--;
          if (st.state === 0) {
            out += new TextDecoder("utf-8").decode(st.pending);
            st.pending = new Uint8Array(0);
          }
        } else {
          // Invalid continuation: emit a replacement character and
          // re-process this byte as a potential new lead byte.
          out += "\ufffd";
          st.state = 0;
          st.pending = new Uint8Array(0);
          i--;
        }
      }
    }
    return out;
  }
  if (this._encoding === "utf16le") {
    var combined = this._concat(this._carry, bytes);
    var keep = combined.length - (combined.length % 2);
    this._carry = combined.subarray(keep);
    var str = "";
    var whole = combined.subarray(0, keep);
    for (var j = 0; j < whole.length; j += 2) {
      str += String.fromCharCode(whole[j] | (whole[j + 1] << 8));
    }
    return str;
  }
  // latin1/ascii/base64/hex pass through whole-chunk decoding.
  return __sbDecodeBytes(bytes, this._encoding);
};
StringDecoder.prototype.end = function(buffer) {
  var bytes = buffer ? this._toBytes(buffer) : new Uint8Array(0);
  if (this._encoding === "utf8") {
    var str = this.write(bytes);
    if (this._utf8.state > 0) {
      str += "\ufffd";
      this._utf8.state = 0;
      this._utf8.pending = new Uint8Array(0);
    }
    return str;
  }
  var combined = this._concat(this._carry, bytes);
  this._carry = new Uint8Array(0);
  if (combined.length === 0) { return ""; }
  // Trailing partial sequences decode as replacement characters — the
  // same lossy behavior Node has.
  return __sbDecodeBytes(combined, this._encoding === "ascii" ? "latin1" : this._encoding === "base64" ? "base64" : this._encoding === "hex" ? "latin1" : this._encoding);
};
StringDecoder.prototype._toBytes = function(buffer) {
  if (Buffer.isBuffer(buffer) || buffer instanceof Uint8Array) {
    return buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.length);
  }
  if (typeof buffer === "string") { return __sbEncodeString(buffer, this._encoding); }
  if (buffer && typeof buffer.length === "number") {
    var out = new Uint8Array(buffer.length);
    for (var i = 0; i < buffer.length; i++) { out[i] = buffer[i] & 0xff; }
    return out;
  }
  throw new TypeError("StringDecoder.write(): buffer must be a Buffer or string");
};
StringDecoder.prototype._concat = function(a, b) {
  var out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};
// -----------------------------------------------------------------------------
// stream (minimal, functional)
// -----------------------------------------------------------------------------

function Stream() {
  EventEmitter.call(this);
}
util.inherits(Stream, EventEmitter);
Stream.prototype.pipe = function(dest) {
  var self = this;
  this.on("data", function(chunk) {
    if (dest.write(chunk) === false && self.pause) { self.pause(); }
  });
  this.on("end", function() {
    if (dest.end) { dest.end(); }
  });
  this.on("error", function(err) {
    if (dest.emit) { dest.emit("error", err); }
  });
  if (dest.resume) {
    dest.on("drain", function() { if (self.resume) { self.resume(); } });
  }
  return dest;
};

function Readable(options) {
  if (!(this instanceof Readable)) { return new Readable(options); }
  EventEmitter.call(this);
  var opts = options || {};
  this._readableState = {
    buffer: [], flowing: false, ended: false,
    encoding: opts.encoding || null
  };
}
util.inherits(Readable, Stream);
Readable.prototype.push = function(chunk) {
  if (chunk === null) {
    this._readableState.ended = true;
    this.emit("end");
    return false;
  }
  if (this._readableState.flowing) {
    this.emit("data", this._decodeChunk(chunk));
  } else {
    this._readableState.buffer.push(chunk);
  }
  return true;
};
Readable.prototype._decodeChunk = function(chunk) {
  if (this._readableState.encoding && typeof chunk !== "string") {
    return __sbDecodeBytes(chunk, this._readableState.encoding);
  }
  return chunk;
};
Readable.prototype.read = function() {
  var state = this._readableState;
  var chunk = state.buffer.shift();
  return chunk === undefined ? null : this._decodeChunk(chunk);
};
Readable.prototype.resume = function() {
  var state = this._readableState;
  state.flowing = true;
  var buffered = state.buffer.splice(0, state.buffer.length);
  for (var i = 0; i < buffered.length; i++) {
    this.emit("data", this._decodeChunk(buffered[i]));
  }
  if (state.ended && buffered.length === 0 && !state.endEmitted) {
    state.endEmitted = true;
    this.emit("end");
  }
  this.emit("resume");
  return this;
};
Readable.prototype.pause = function() {
  this._readableState.flowing = false;
  this.emit("pause");
  return this;
};
Readable.prototype.isPaused = function() {
  return this._readableState.flowing === false;
};
Readable.prototype._read = function() {};

function Writable(options) {
  if (!(this instanceof Writable)) { return new Writable(options); }
  EventEmitter.call(this);
  var opts = options || {};
  this._writableState = { ended: false, decodeStrings: opts.decodeStrings !== false };
  this._writeImpl = opts.write || null;
}
util.inherits(Writable, Stream);
Writable.prototype._write = null;
Writable.prototype.write = function(chunk, encoding, cb) {
  var enc = typeof encoding === "string" ? encoding :
    (typeof cb === "undefined" && typeof encoding === "function") ? null : encoding;
  var callback = typeof cb === "function" ? cb :
    (typeof encoding === "function" ? encoding : function() {});
  var data = (this._writableState.decodeStrings && typeof chunk === "string") ?
    __sbEncodeString(chunk, __sbNormalizeEncoding(enc)) : chunk;
  if (this._writableState.ended) {
    var err = new Error("write after end");
    if (callback) { callback(err); }
    return false;
  }
  if (typeof this._write === "function") {
    this._write(data, enc, callback);
  } else if (this._writeImpl) {
    this._writeImpl(data, enc, callback);
  } else if (callback) {
    callback();
  }
  return true;
};
Writable.prototype.end = function(chunk, cb) {
  var self = this;
  var callback = typeof chunk === "function" ? chunk : cb;
  if (chunk != null && typeof chunk !== "function") { this.write(chunk); }
  if (this._writableState.ended) {
    if (callback) { setImmediate(callback); }
    return this;
  }
  this._writableState.ended = true;
  var finish = function() { self.emit("finish"); if (callback) { callback(); } };
  if (setImmediate) { setImmediate(finish); } else { finish(); }
  return this;
};

function Duplex(options) {
  if (!(this instanceof Duplex)) { return new Duplex(options); }
  Readable.call(this, options);
  Writable.call(this, options);
}
util.inherits(Duplex, Readable);
Object.keys(Writable.prototype).forEach(function(key) {
  if (!Duplex.prototype[key]) {
    Duplex.prototype[key] = Writable.prototype[key];
  }
});

function Transform(options) {
  if (!(this instanceof Transform)) { return new Transform(options); }
  Duplex.call(this, options);
  var opts = options || {};
  this._transformImpl = opts.transform || null;
}
util.inherits(Transform, Duplex);
Transform.prototype._transform = function(chunk, enc, cb) {
  if (this._transformImpl) {
    var self = this;
    this._transformImpl(chunk, enc, function(err, out) {
      if (err) { return cb(err); }
      if (out != null) { self.push(out); }
      cb();
    });
  } else {
    this.push(chunk);
    cb();
  }
};
Transform.prototype._write = function(chunk, enc, cb) {
  this._transform(chunk, enc, cb);
};

// -----------------------------------------------------------------------------
// crypto — backed by the host crypto capability (javax.crypto)
// -----------------------------------------------------------------------------

function __sbToBytes(value, encoding) {
  if (Buffer.isBuffer(value) || value instanceof Uint8Array) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (typeof value === "string") {
    return __sbEncodeString(value, __sbNormalizeEncoding(encoding));
  }
  if (value && typeof value.length === "number") {
    var out = new Uint8Array(value.length);
    for (var i = 0; i < value.length; i++) { out[i] = value[i] & 0xff; }
    return out;
  }
  throw new TypeError("Expected string, Buffer or bytes");
}

function __sbBytesToB64(bytes) {
  return btoa(__sbBytesToBinaryString(bytes));
}

function __sbB64ToBytes(b64) {
  return __sbEncodeString(b64, "base64");
}

function __sbCryptoCall(op, args) {
  var result = JSON.parse(__sbCrypto(op, JSON.stringify(args)));
  if (result && result.error) {
    throw new Error(result.error);
  }
  return result;
}

function __sbDigestResult(result, encoding) {
  if (encoding === undefined || encoding === "buffer") {
    return new Buffer(__sbB64ToBytes(result.b64));
  }
  if (encoding === "hex") { return result.hex; }
  if (encoding === "base64") { return result.b64; }
  if (encoding === "latin1" || encoding === "binary" || encoding === "ascii") {
    return __sbDecodeBytes(__sbB64ToBytes(result.b64), "latin1");
  }
  throw new Error("Unsupported digest encoding: " + encoding);
}

// NOTE: built under a dedicated name. In QuickJS, a script-level
// `var crypto` IS the globalThis.crypto binding — a plain `var crypto`
// here would be silently replaced by the webcrypto surface below, and
// the lazily-evaluated module init would export the wrong object.
var __sbCryptoModule = {};

__sbCryptoModule.createHash = function(algorithm) {
  var alg = String(algorithm || "").toLowerCase().replace("-", "");
  var chunks = [];
  return {
    update: function(data, encoding) {
      chunks.push(__sbToBytes(data, encoding));
      return this;
    },
    digest: function(encoding) {
      var total = 0;
      for (var i = 0; i < chunks.length; i++) { total += chunks[i].length; }
      var all = new Uint8Array(total);
      var offset = 0;
      for (var j = 0; j < chunks.length; j++) {
        all.set(chunks[j], offset);
        offset += chunks[j].length;
      }
      var result = __sbCryptoCall("hash", { alg: alg, data: __sbBytesToB64(all) });
      return __sbDigestResult(result, encoding);
    }
  };
};

__sbCryptoModule.createHmac = function(algorithm, key) {
  var alg = String(algorithm || "").toLowerCase().replace("-", "");
  var keyBytes = __sbToBytes(key, "utf8");
  var chunks = [];
  return {
    update: function(data, encoding) {
      chunks.push(__sbToBytes(data, encoding));
      return this;
    },
    digest: function(encoding) {
      var total = 0;
      for (var i = 0; i < chunks.length; i++) { total += chunks[i].length; }
      var all = new Uint8Array(total);
      var offset = 0;
      for (var j = 0; j < chunks.length; j++) {
        all.set(chunks[j], offset);
        offset += chunks[j].length;
      }
      var result = __sbCryptoCall("hmac", {
        alg: alg, key: __sbBytesToB64(keyBytes), data: __sbBytesToB64(all)
      });
      return __sbDigestResult(result, encoding);
    }
  };
};

__sbCryptoModule.randomBytes = function(size, callback) {
  var n = Number(size);
  if (!isFinite(n) || n < 0 || n > (1 << 20) || Math.floor(n) !== n) {
    throw new Error("randomBytes(): invalid size");
  }
  if (callback) {
    var self = this;
    setImmediate(function() {
      var result = __sbCryptoCall("randomBytes", { len: n });
      callback(null, new Buffer(__sbB64ToBytes(result.b64)));
    });
    return undefined;
  }
  var result = __sbCryptoCall("randomBytes", { len: n });
  return new Buffer(__sbB64ToBytes(result.b64));
};

__sbCryptoModule.pbkdf2Sync = function(password, salt, iterations, keylen, digest) {
  var result = __sbCryptoCall("pbkdf2", {
    password: typeof password === "string" ? password :
      __sbDecodeBytes(__sbToBytes(password), "utf8"),
    salt: __sbBytesToB64(__sbToBytes(salt, "utf8")),
    iterations: iterations,
    keyLen: keylen,
    hash: String(digest || "sha1").toLowerCase().replace("-", "")
  });
  return new Buffer(__sbB64ToBytes(result.b64));
};

__sbCryptoModule.pbkdf2 = function(password, salt, iterations, keylen, digest, callback) {
  var cb = typeof digest === "function" ? digest : callback;
  if (typeof cb !== "function") {
    throw new Error("pbkdf2(): callback required");
  }
  setImmediate(function() {
    try {
      cb(null, __sbCryptoModule.pbkdf2Sync(password, salt, iterations, keylen,
        typeof digest === "function" ? undefined : digest));
    } catch (e) {
      cb(e);
    }
  });
};

function __sbCipherAlgorithms() {
  var algs = [];
  var sizes = [128, 192, 256];
  var modes = ["cbc", "ctr", "ecb", "gcm"];
  for (var s = 0; s < sizes.length; s++) {
    for (var m = 0; m < modes.length; m++) {
      algs.push("aes-" + sizes[s] + "-" + modes[m]);
    }
  }
  return algs;
}

__sbCryptoModule.getCiphers = function() { return __sbCipherAlgorithms().slice(); };

function __sbAssertCipherAlgorithm(algorithm) {
  var alg = String(algorithm || "").toLowerCase();
  if (__sbCipherAlgorithms().indexOf(alg) === -1) {
    throw new Error("Unsupported cipher algorithm: " + algorithm +
      " (supported: AES-128/192/256 in CBC/CTR/ECB/GCM)");
  }
  return alg;
}

function __sbCollectUpdates(algorithm, key, iv, isDecrypt) {
  var alg = __sbAssertCipherAlgorithm(algorithm);
  var keyBytes = __sbToBytes(key, "utf8");
  var ivBytes = iv == null || iv === "" ? null : __sbToBytes(iv, "utf8");
  var chunks = [];
  var aad = null;
  var authTag = null;
  var noPadding = false;
  return {
    update: function(data, inputEncoding) {
      chunks.push(__sbToBytes(data, inputEncoding));
      return new Buffer(0);
    },
    final: function() {
      var total = 0;
      for (var i = 0; i < chunks.length; i++) { total += chunks[i].length; }
      var all = new Uint8Array(total);
      var offset = 0;
      for (var j = 0; j < chunks.length; j++) {
        all.set(chunks[j], offset);
        offset += chunks[j].length;
      }
      var args = {
        alg: alg,
        key: __sbBytesToB64(keyBytes),
        iv: ivBytes ? __sbBytesToB64(ivBytes) : null,
        data: __sbBytesToB64(all)
      };
      if (aad) { args.aad = __sbBytesToB64(aad); }
      if (isDecrypt) {
        if (authTag) { args.tag = __sbBytesToB64(authTag); }
      }
      var op = isDecrypt ? "aesDecrypt" : "aesEncrypt";
      var result = __sbCryptoCall(op, args);
      var body = __sbB64ToBytes(result.b64);
      if (!isDecrypt && result.tagB64) {
        var cipherObj = this;
        cipherObj._gcmTag = new Buffer(__sbB64ToBytes(result.tagB64));
      }
      return new Buffer(body);
    },
    setAAD: function(buffer) {
      aad = __sbToBytes(buffer);
      return this;
    },
    setAutoPadding: function(autoPadding) {
      if (autoPadding === false && alg.slice(-3) !== "ctr" && alg.slice(-3) !== "gcm") {
        throw new Error("setAutoPadding(false) is not supported for " + alg +
          " in the provider sandbox");
      }
      return this;
    },
    getAuthTag: function() {
      if (this._gcmTag) { return this._gcmTag; }
      throw new Error("getAuthTag(): no authentication tag available");
    },
    setAuthTag: function(buffer) {
      authTag = __sbToBytes(buffer);
      return this;
    }
  };
}

__sbCryptoModule.createCipheriv = function(algorithm, key, iv) {
  if (algorithm == null || key == null) {
    throw new Error("createCipheriv(): algorithm and key are required");
  }
  return __sbCollectUpdates(algorithm, key, iv, false);
};

__sbCryptoModule.createDecipheriv = function(algorithm, key, iv) {
  if (algorithm == null || key == null) {
    throw new Error("createDecipheriv(): algorithm and key are required");
  }
  return __sbCollectUpdates(algorithm, key, iv, true);
};

// ---- WebCrypto-shaped surface -------------------------------------------

function __sbSubtleKey(type, algorithm, usages, extractable, material) {
  this.type = type;
  this.algorithm = algorithm;
  this.usages = usages;
  this.extractable = extractable;
  this._material = material;
}

var subtle = {};

subtle.digest = function(algorithm, data) {
  return new Promise(function(resolve, reject) {
    try {
      var name = typeof algorithm === "string" ? algorithm :
        (algorithm && algorithm.name) || "";
      var alg = String(name).toLowerCase().replace("-", "");
      var bytes = __sbToBytes(data);
      var result = __sbCryptoCall("hash", { alg: alg, data: __sbBytesToB64(bytes) });
      resolve(new Buffer(__sbB64ToBytes(result.b64)));
    } catch (e) { reject(e); }
  });
};

subtle.importKey = function(format, keyData, algorithm, extractable, usages) {
  return new Promise(function(resolve, reject) {
    try {
      if (format !== "raw") {
        throw new Error("importKey(): only 'raw' format is supported in the provider sandbox");
      }
      var name = String(algorithm && algorithm.name ? algorithm.name : "").toUpperCase();
      if (name !== "HMAC" && name !== "AES-GCM" && name !== "AES-CBC") {
        throw new Error("importKey(): unsupported algorithm " + name);
      }
      var alg = { name: name };
      if (name === "HMAC") {
        alg.hash = String(algorithm.hash && algorithm.hash.name ? algorithm.hash.name : "SHA-256").toUpperCase();
      }
      resolve(new __sbSubtleKey("secret", alg, usages || [], extractable, __sbToBytes(keyData)));
    } catch (e) { reject(e); }
  });
};

subtle.exportKey = function(format, key) {
  return new Promise(function(resolve, reject) {
    try {
      if (format !== "raw") {
        throw new Error("exportKey(): only 'raw' format is supported in the provider sandbox");
      }
      if (!(key instanceof __sbSubtleKey) || !key.extractable) {
        throw new Error("exportKey(): key is not exportable");
      }
      resolve(new Buffer(key._material));
    } catch (e) { reject(e); }
  });
};

subtle.sign = function(algorithm, key, data) {
  return new Promise(function(resolve, reject) {
    try {
      var hash = typeof algorithm === "string" ? "SHA-256" :
        (algorithm && algorithm.hash && algorithm.hash.name) || "SHA-256";
      var result = __sbCryptoCall("hmac", {
        alg: String(hash).toLowerCase().replace("-", ""),
        key: __sbBytesToB64(key._material),
        data: __sbBytesToB64(__sbToBytes(data))
      });
      resolve(new Buffer(__sbB64ToBytes(result.b64)));
    } catch (e) { reject(e); }
  });
};

subtle.verify = function(algorithm, key, signature, data) {
  return subtle.sign(algorithm, key, data).then(function(expected) {
    if (expected.length !== signature.length) { return false; }
    var diff = 0;
    for (var i = 0; i < expected.length; i++) {
      diff |= expected[i] ^ signature[i];
    }
    return diff === 0;
  });
};

subtle.encrypt = function(algorithm, key, data) {
  return new Promise(function(resolve, reject) {
    try {
      var name = String(algorithm && algorithm.name ? algorithm.name : "").toUpperCase();
      var algName = name === "AES-GCM" ? "aes-256-gcm" : "aes-256-cbc";
      var keyBytes = key._material;
      var alg = (keyBytes.length === 16 ? "aes-128-" : keyBytes.length === 24 ? "aes-192-" : "aes-256-") +
        (name === "AES-GCM" ? "gcm" : "cbc");
      var args = {
        alg: alg,
        key: __sbBytesToB64(keyBytes),
        iv: __sbBytesToB64(__sbToBytes(algorithm.iv)),
        data: __sbBytesToB64(__sbToBytes(data))
      };
      if (algorithm.additionalData) {
        args.aad = __sbBytesToB64(__sbToBytes(algorithm.additionalData));
      }
      var result = __sbCryptoCall("aesEncrypt", args);
      var body = __sbB64ToBytes(result.b64);
      if (result.tagB64) {
        // WebCrypto's AES-GCM ciphertext is body||tag.
        var tag = __sbB64ToBytes(result.tagB64);
        var combined = new Uint8Array(body.length + tag.length);
        combined.set(body, 0);
        combined.set(tag, body.length);
        resolve(new Buffer(combined));
      } else {
        resolve(new Buffer(body));
      }
    } catch (e) { reject(e); }
  });
};

subtle.decrypt = function(algorithm, key, data) {
  return new Promise(function(resolve, reject) {
    try {
      var name = String(algorithm && algorithm.name ? algorithm.name : "").toUpperCase();
      var keyBytes = key._material;
      var alg = (keyBytes.length === 16 ? "aes-128-" : keyBytes.length === 24 ? "aes-192-" : "aes-256-") +
        (name === "AES-GCM" ? "gcm" : "cbc");
      var bytes = __sbToBytes(data);
      var args = {
        alg: alg,
        key: __sbBytesToB64(keyBytes),
        iv: __sbBytesToB64(__sbToBytes(algorithm.iv)),
        data: __sbBytesToB64(name === "AES-GCM" ? bytes.subarray(0, Math.max(0, bytes.length - 16)) : bytes)
      };
      if (name === "AES-GCM") {
        args.tag = __sbBytesToB64(bytes.subarray(Math.max(0, bytes.length - 16)));
      }
      if (algorithm.additionalData) {
        args.aad = __sbBytesToB64(__sbToBytes(algorithm.additionalData));
      }
      var result = __sbCryptoCall("aesDecrypt", args);
      resolve(new Buffer(__sbB64ToBytes(result.b64)));
    } catch (e) { reject(e); }
  });
};

__sbCryptoModule.webcrypto = {
  subtle: subtle,
  getRandomValues: function(view) {
    // WebCrypto accepts any integer typed array (forge passes Uint32Array).
    if (view == null || typeof view !== "object" || !ArrayBuffer.isView(view) ||
        view.length === undefined) {
      throw new TypeError("getRandomValues(): typed array required");
    }
    if (view instanceof Float32Array || view instanceof Float64Array) {
      throw new TypeError("getRandomValues(): float arrays are not allowed");
    }
    if (view.byteLength > 65536) {
      throw new Error("getRandomValues(): length exceeds 65536 bytes");
    }
    var result = __sbCryptoCall("randomBytes", { len: view.byteLength });
    var bytes = __sbB64ToBytes(result.b64);
    new Uint8Array(view.buffer, view.byteOffset, view.byteLength).set(bytes);
    return view;
  },
  randomUUID: function() {
    var bytes = __sbB64ToBytes(__sbCryptoCall("randomBytes", { len: 16 }).b64);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = __sbDecodeBytes(bytes, "hex");
    return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) +
      "-" + hex.slice(16, 20) + "-" + hex.slice(20, 32);
  }
};
__sbCryptoModule.subtle = subtle;
globalThis.crypto = __sbCryptoModule.webcrypto;

// -----------------------------------------------------------------------------
// Storage (scoped to this provider execution, quota-enforced)
// -----------------------------------------------------------------------------

function __sbMakeStorage(kind) {
  var data = Object.create(null);
  var total = 0;
  var QUOTA = 256 * 1024;
  var KEY_QUOTA = 64 * 1024;
  function keyOf(key) {
    var k = String(key);
    if (k === "") { throw new Error("Storage key must not be empty"); }
    return k;
  }
  function byteLength(value) {
    return __sbEncodeString(value, "utf8").length;
  }
  var store = {
    getItem: function(key) {
      key = keyOf(key);
      return Object.prototype.hasOwnProperty.call(data, key) ? data[key] : null;
    },
    setItem: function(key, value) {
      key = keyOf(key);
      var val = String(value);
      var size = byteLength(key) + byteLength(val);
      if (size > KEY_QUOTA) {
        throw new Error(kind + " quota exceeded: entry is larger than " + KEY_QUOTA + " bytes");
      }
      var previous = Object.prototype.hasOwnProperty.call(data, key) ?
        byteLength(key) + byteLength(data[key]) : 0;
      if (total - previous + size > QUOTA) {
        throw new Error(kind + " quota exceeded (" + QUOTA + " bytes per provider call)");
      }
      total = total - previous + size;
      data[key] = val;
    },
    removeItem: function(key) {
      key = keyOf(key);
      if (Object.prototype.hasOwnProperty.call(data, key)) {
        total -= byteLength(key) + byteLength(data[key]);
        delete data[key];
      }
    },
    clear: function() {
      data = Object.create(null);
      total = 0;
    },
    key: function(index) {
      var keys = Object.keys(data);
      return index >= 0 && index < keys.length ? keys[index] : null;
    }
  };
  Object.defineProperty(store, "length", {
    get: function() { return Object.keys(data).length; }
  });
  return store;
}

// -----------------------------------------------------------------------------
// Browser globals
// -----------------------------------------------------------------------------

// window/self alias the global object, exactly like a browser.
Object.defineProperty(globalThis, "window", {
  value: globalThis, writable: false, configurable: false
});
Object.defineProperty(globalThis, "self", {
  value: globalThis, writable: false, configurable: false
});

// navigator: real, stable values; the UA matches what fetch() sends.
var navigator = Object.freeze({
  userAgent: (typeof globalThis.__sbUserAgent === "string" && globalThis.__sbUserAgent) ||
    "Mozilla/5.0 (Linux; Android 14) StreamBridge/1.0",
  appVersion: "5.0 (Linux; Android 14)",
  platform: "Linux armv8l",
  product: "Gecko",
  productSub: "20030107",
  vendor: "",
  language: "en-US",
  languages: ["en-US", "en"],
  cookieEnabled: true,
  onLine: true,
  hardwareConcurrency: 4,
  maxTouchPoints: 1,
  javaEnabled: function() { return false; }
});
Object.defineProperty(globalThis, "navigator", {
  value: navigator, writable: false, configurable: false
});

// location: derived from the provider's REAL code URL; navigation throws
// a controlled error.
if (typeof globalThis.__sbLocationHref === "string" && globalThis.__sbLocationHref) {
  (function() {
    var parsed;
    try { parsed = new URL(globalThis.__sbLocationHref); }
    catch (e) { parsed = null; }
    var loc = {
      toString: function() { return parsed ? parsed.href : globalThis.__sbLocationHref; }
    };
    var props = ["href", "protocol", "host", "hostname", "port", "pathname",
      "search", "hash", "origin"];
    for (var i = 0; i < props.length; i++) {
      (function(name) {
        Object.defineProperty(loc, name, {
          get: function() {
            return parsed ? parsed[name] : (name === "href" ? globalThis.__sbLocationHref : "");
          }
        });
      })(props[i]);
    }
    function navigationBlocked(method) {
      throw new Error("location." + method + " is not available in the provider sandbox: " +
        "the sandbox does not navigate");
    }
    loc.assign = function() { navigationBlocked("assign"); };
    loc.replace = function() { navigationBlocked("replace"); };
    loc.reload = function() { navigationBlocked("reload"); };
    loc.searchParams = parsed ? parsed.searchParams : null;
    Object.defineProperty(globalThis, "location", {
      get: function() { return loc; },
      set: function() {
        throw new Error("location assignment is not available in the provider sandbox: " +
          "the sandbox does not navigate");
      },
      configurable: false
    });
  })();
}

// document: cookie access backed by the HTTP engine's jar, and <a> URL
// parsing; everything else is a controlled error stating exactly that.
var document = {
  createElement: function(tagName) {
    var tag = String(tagName == null ? "" : tagName).toLowerCase();
    if (tag === "a") { return __sbMakeAnchor(); }
    throw new Error("document.createElement('" + tagName + "') is not available in the " +
      "provider sandbox: there is no DOM rendering (only 'a', for URL parsing)");
  },
  addEventListener: function(type) {
    throw new Error("document.addEventListener('" + type + "') is not available in the " +
      "provider sandbox: there are no DOM events");
  },
  removeEventListener: function() { },
  querySelector: function(selector) {
    throw new Error("document.querySelector('" + selector + "') is not available in the " +
      "provider sandbox: use require('cheerio') for HTML parsing");
  },
  querySelectorAll: function(selector) {
    throw new Error("document.querySelectorAll('" + selector + "') is not available in the " +
      "provider sandbox: use require('cheerio') for HTML parsing");
  },
  write: function() {
    throw new Error("document.write() is not available in the provider sandbox");
  },
  writeln: function() {
    throw new Error("document.writeln() is not available in the provider sandbox");
  }
};
Object.defineProperty(document, "cookie", {
  get: function() {
    // Scoped to the origin of the most recent response — the same
    // browsing-context model a browser page has.
    return __sbGetCookies();
  },
  set: function(value) {
    __sbSetCookie(String(value));
  }
});
Object.defineProperty(globalThis, "document", {
  value: document, writable: false, configurable: false
});

function __sbMakeAnchor() {
  var anchor = { _parsed: null };
  function refresh(href) {
    try { anchor._parsed = new URL(href); }
    catch (e) { anchor._parsed = null; }
  }
  Object.defineProperty(anchor, "href", {
    get: function() {
      return anchor._parsed ? anchor._parsed.href : "";
    },
    set: function(value) { refresh(String(value)); }
  });
  var props = ["protocol", "host", "hostname", "port", "pathname", "search", "hash", "origin"];
  for (var i = 0; i < props.length; i++) {
    (function(name) {
      Object.defineProperty(anchor, name, {
        get: function() { return anchor._parsed ? anchor._parsed[name] : ""; }
      });
    })(props[i]);
  }
  anchor.toString = function() { return anchor.href; };
  return anchor;
}

globalThis.matchMedia = function(query) {
  throw new Error("matchMedia('" + query + "') is not available in the provider sandbox");
};
globalThis.requestAnimationFrame = function(callback) {
  return setTimeout(callback, 16);
};
globalThis.cancelAnimationFrame = function(id) { clearTimeout(id); };
globalThis.addEventListener = function(type) {
  throw new Error("addEventListener('" + type + "') is not available in the provider sandbox");
};
globalThis.removeEventListener = function() { };

globalThis.localStorage = __sbMakeStorage("localStorage");
globalThis.sessionStorage = __sbMakeStorage("sessionStorage");

// DOMException: minimal real shape (fetch/abort paths reference it).
function DOMException(message, name) {
  this.message = String(message == null ? "" : message);
  this.name = String(name == null ? "Error" : name);
  this.stack = (new Error(this.message)).stack;
}
DOMException.prototype = Object.create(Error.prototype);
DOMException.prototype.constructor = DOMException;
DOMException.prototype.toString = function() {
  return this.name + ": " + this.message;
};
globalThis.DOMException = DOMException;

// -----------------------------------------------------------------------------
// TextEncoder
// -----------------------------------------------------------------------------

function TextEncoder() {
  this.encoding = "utf-8";
}
TextEncoder.prototype.encode = function(input) {
  return new Uint8Array(__sbUtf8Encode(String(input == null ? "" : input)));
};
TextEncoder.prototype.encodeInto = function(source, destination) {
  var bytes = new Uint8Array(__sbUtf8Encode(String(source)));
  var written = Math.min(bytes.length, destination.length);
  for (var i = 0; i < written; i++) { destination[i] = bytes[i]; }
  // Count complete source characters that fit.
  var read = 0;
  var byteIndex = 0;
  var str = String(source);
  while (byteIndex < written && read < str.length) {
    var code = str.codePointAt(read);
    var size = code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
    if (byteIndex + size <= written) {
      byteIndex += size;
      read += (code > 0xffff) ? 2 : 1;
    } else {
      break;
    }
  }
  return { written: written, read: read };
};
globalThis.TextEncoder = TextEncoder;

// -----------------------------------------------------------------------------
// AbortController / AbortSignal
// -----------------------------------------------------------------------------

function AbortSignal() {
  this.aborted = false;
  this.onabort = null;
  this.reason = undefined;
  this._listeners = [];
}
AbortSignal.prototype.addEventListener = function(type, listener) {
  if (type === "abort" && typeof listener === "function") {
    this._listeners.push(listener);
  }
};
AbortSignal.prototype.removeEventListener = function(type, listener) {
  if (type !== "abort") { return; }
  var index = this._listeners.indexOf(listener);
  if (index !== -1) { this._listeners.splice(index, 1); }
};
AbortSignal.prototype.throwIfAborted = function() {
  if (this.aborted) { throw this.reason; }
};

function AbortController() {
  this.signal = new AbortSignal();
}
AbortController.prototype.abort = function(reason) {
  var signal = this.signal;
  if (signal.aborted) { return; }
  signal.aborted = true;
  signal.reason = reason !== undefined ? reason :
    new DOMException("This operation was aborted", "AbortError");
  if (typeof signal.onabort === "function") {
    try { signal.onabort({ type: "abort", target: signal }); }
    catch (e) { __sbLog("error", "onabort handler failed: " + String(e && e.message ? e.message : e)); }
  }
  var listeners = signal._listeners.slice();
  for (var i = 0; i < listeners.length; i++) {
    try { listeners[i]({ type: "abort", target: signal }); }
    catch (e) { __sbLog("error", "abort listener failed: " + String(e && e.message ? e.message : e)); }
  }
};
globalThis.AbortSignal = AbortSignal;
globalThis.AbortController = AbortController;

// -----------------------------------------------------------------------------
// Global aliases and module registration
// -----------------------------------------------------------------------------

globalThis.Buffer = Buffer;
globalThis.global = globalThis;

__sbRegisterBuiltin("buffer", function(module) {
  module.exports.Buffer = Buffer;
  module.exports.SlowBuffer = Buffer;
  module.exports.constants = { MAX_LENGTH: 0x7fffffff, MAX_SAFE_INTEGER: 0x1fffffffffffff };
});

__sbRegisterBuiltin("events", function(module) {
  module.exports = EventEmitter;
  module.exports.EventEmitter = EventEmitter;
  module.exports.default = EventEmitter;
});

__sbRegisterBuiltin("path", function(module) { module.exports = path; });
__sbRegisterBuiltin("url", function(module) {
  module.exports = url;
  module.exports.URL = URL;
  module.exports.URLSearchParams = URLSearchParams;
});
__sbRegisterBuiltin("querystring", function(module) { module.exports = querystring; });
__sbRegisterBuiltin("util", function(module) { module.exports = util; });
__sbRegisterBuiltin("assert", function(module) { module.exports = assert; });
__sbRegisterBuiltin("string_decoder", function(module) {
  module.exports.StringDecoder = StringDecoder;
});
__sbRegisterBuiltin("stream", function(module) {
  module.exports = Stream;
  module.exports.Stream = Stream;
  module.exports.Readable = Readable;
  module.exports.Writable = Writable;
  module.exports.Duplex = Duplex;
  module.exports.Transform = Transform;
  module.exports.PassThrough = Transform;
});
__sbRegisterBuiltin("crypto", function(module) {
  module.exports = __sbCryptoModule;
});
__sbRegisterBuiltin("process", function(module) { module.exports = process; });
__sbRegisterBuiltin("timers", function(module) {
  module.exports.setTimeout = setTimeout;
  module.exports.clearTimeout = clearTimeout;
  module.exports.setInterval = setInterval;
  module.exports.clearInterval = clearInterval;
  module.exports.setImmediate = setImmediate;
  module.exports.clearImmediate = clearImmediate;
});
__sbRegisterBuiltin("util-deprecate", function(module) {
  module.exports = util.deprecate;
});
__sbRegisterBuiltin("inherits", function(module) {
  module.exports = util.inherits;
});

    """.trimIndent()
}
