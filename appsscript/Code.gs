/**
 * Marcar Pro · «A mi Drive» con QR.
 *
 * El mismo proyecto se publica DOS veces (Implementar > Nueva implementación > Aplicación web):
 *  - «docentes»: Ejecutar como = «Usuario que accede a la aplicación web»,
 *                Quién tiene acceso = «Cualquier usuario de <tu dominio>».
 *    Es la página que abre el móvil del docente al escanear el QR.
 *  - «panel»:    Ejecutar como = «Yo», Quién tiene acceso = «Cualquier usuario».
 *    Es a quien pregunta el panel, que no tiene sesión de Google.
 * Las dos comparten la caché del script.
 *
 * El PDF NO pasa por aquí: al pulsar «Guardar», el Drive del docente (con su cuenta) abre una
 * subida de un solo archivo y este script solo le pasa al panel esa dirección. El panel sube el
 * PDF directamente al Drive del docente. Aquí solo hay avisos de unas pocas letras, así que la
 * cuenta que lo publica no hace de cuello de botella.
 */

/** Dominios de correo que pueden guardar ([] = cualquiera). */
var DOMINIOS = ['g.educaand.es'];
/** Segundos que se recuerda un envío (el QR del panel dura menos). */
var VIDA = 900;
var CARPETA = 'Marcar Pro';
var DRIVE = 'https://www.googleapis.com/drive/v3/files';
var SUBIDA = 'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id';

function doGet(e) {
  var p = (e && e.parameter) || {};
  if (p.a === 'estado') return estadoPanel_(p.k);
  if (p.a === 'hecho') return hecho_(p.k);
  if (p.s) return pagina_(p.s, p.n);
  return HtmlService.createHtmlOutput('<p style="font-family:sans-serif">Marcar Pro: escanea el QR que aparece en el panel.</p>')
    .setTitle('Marcar Pro');
}

/** La página del móvil. */
function pagina_(id, nombre) {
  var t = HtmlService.createTemplateFromFile('Guardar');
  t.id = valido_(id) ? id : '';
  t.nombre = limpio_(nombre);
  t.email = correoActual_();
  t.permitido = permitido_(t.email);
  return t.evaluate()
    .setTitle('Guardar en tu Drive · Marcar Pro')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/**
 * Desde el móvil (google.script.run), al pulsar «Guardar»: con la cuenta del docente, abre en su
 * Drive la subida de UN archivo en la carpeta «Marcar Pro» y deja la dirección para el panel.
 */
function preparar(id, nombre) {
  comprobar_(id);
  var cache = CacheService.getScriptCache();
  var lock = LockService.getScriptLock();
  lock.waitLock(5000);
  try {
    if (cache.get('m_' + id)) throw new Error('Este código ya se ha usado. Vuelve a pulsar «A mi Drive» en el panel.');
    cache.put('m_' + id, JSON.stringify({ estado: 'preparando' }), VIDA);
  } finally {
    lock.releaseLock();
  }
  var r = pedir_(SUBIDA, 'post', { name: limpio_(nombre), mimeType: 'application/pdf', parents: [carpeta_()] }, { 'X-Upload-Content-Type': 'application/pdf' });
  var subida = r.getHeaders()['Location'] || r.getHeaders()['location'];
  if (!subida) throw new Error('Tu Drive no ha aceptado el archivo. Inténtalo de nuevo.');
  cache.put('m_' + id, JSON.stringify({ estado: 'listo', subida: subida }), VIDA);
  return true;
}

/** Desde el móvil, para saber cuándo ha terminado el panel. */
function estado(id) {
  if (!valido_(id)) return 'nada';
  var m = CacheService.getScriptCache().get('m_' + id);
  return m ? JSON.parse(m).estado : 'nada';
}

/** El panel pregunta con su secreto. La dirección de subida solo se entrega una vez. */
function estadoPanel_(clave) {
  if (!valido_(clave)) return json_({ estado: 'nada' });
  var id = idDeSesion_(clave);
  var cache = CacheService.getScriptCache();
  var m = cache.get('m_' + id);
  if (!m) return json_({ estado: 'esperando' });
  var d = JSON.parse(m);
  if (d.estado !== 'listo') return json_({ estado: d.estado });
  cache.put('m_' + id, JSON.stringify({ estado: 'enviando' }), VIDA);
  return json_({ estado: 'listo', subida: d.subida });
}

/** El panel avisa de que el PDF ya está en el Drive del docente. */
function hecho_(clave) {
  if (!valido_(clave)) return json_({ ok: false });
  CacheService.getScriptCache().put('m_' + idDeSesion_(clave), JSON.stringify({ estado: 'guardado' }), VIDA);
  return json_({ ok: true });
}

/** La carpeta «Marcar Pro» del docente (la crea la primera vez). Solo ve lo que crea esta app. */
function carpeta_() {
  var q = encodeURIComponent("name = '" + CARPETA + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false");
  var r = JSON.parse(pedir_(DRIVE + '?q=' + q + '&spaces=drive&fields=files(id)&pageSize=1', 'get').getContentText());
  if (r.files && r.files.length) return r.files[0].id;
  return JSON.parse(pedir_(DRIVE + '?fields=id', 'post', { name: CARPETA, mimeType: 'application/vnd.google-apps.folder' }).getContentText()).id;
}

/** Una petición a Drive con la cuenta del docente (solo puede tocar lo que crea esta app). */
function pedir_(url, metodo, cuerpo, cabeceras) {
  var h = cabeceras || {};
  h.Authorization = 'Bearer ' + ScriptApp.getOAuthToken();
  var o = { method: metodo, headers: h, muteHttpExceptions: true };
  if (cuerpo) {
    o.contentType = 'application/json; charset=UTF-8';
    o.payload = JSON.stringify(cuerpo);
  }
  var r = UrlFetchApp.fetch(url, o);
  if (r.getResponseCode() >= 300) throw new Error('Tu Drive no ha respondido (' + r.getResponseCode() + '). Inténtalo de nuevo.');
  return r;
}

function comprobar_(id) {
  if (!valido_(id)) throw new Error('Código QR no válido. Vuelve a escanearlo.');
  if (!permitido_(correoActual_())) throw new Error('Entra con tu cuenta del centro para guardar.');
}

/** SHA-256 en base64url sin relleno: el panel guarda el secreto, el QR solo lleva esto. */
function idDeSesion_(clave) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, clave, Utilities.Charset.UTF_8);
  return Utilities.base64EncodeWebSafe(bytes).replace(/=+$/, '');
}

function valido_(s) {
  return /^[A-Za-z0-9_-]{43}$/.test(String(s || ''));
}

function limpio_(nombre) {
  var n = String(nombre || '').replace(/[^\p{L}\p{N} ._-]/gu, '').slice(0, 120).trim();
  if (!n) n = 'Marcar Pro';
  return /\.pdf$/i.test(n) ? n : n + '.pdf';
}

function correoActual_() {
  try {
    return String(Session.getActiveUser().getEmail() || '').toLowerCase();
  } catch (e) {
    return '';
  }
}

function permitido_(email) {
  if (!email) return false;
  if (!DOMINIOS.length) return true;
  return DOMINIOS.some(function (d) { return email.slice(-(d.length + 1)) === '@' + d.toLowerCase(); });
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
