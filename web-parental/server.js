/* =====================================================================
 *  PARENTAL - Web Parental Control Server (Node.js + Express + Socket.IO)
 *  Version : 1.0.0
 *  LPORT  : 8000
 *  LHOST  : https://friday-succulent-marigold.ngrok-free.dev  (ngrok tunnel)
 *
 *  Alur:
 *  1. Orang tua membuka website -> memasukkan 6 digit pairing code
 *     yang tampil di APK children pada HP anak.
 *  2. Server mencocokkan code dengan perangkat anak yang sedang
 *     online (socket 'child') -> sesi ditautkan (pairing).
 *  3. Panel menampilkan kotak INFO PERANGKAT dan kotak CONTROL
 *     (kunci layar + password, blokir aplikasi + daftar package).
 *  4. Setiap perubahan control dikirim realtime ke HP anak via
 *     socket.io event 'parent:control'.
 * ===================================================================== */

const path = require('path');
const fs = require('fs');
const os = require('os');
const express = require('express');
const http = require('http');
const { Server } = require('socket.io');

const PORT = process.env.PORT || 8000; // LPORT = 8000
const NGROK_URL = 'https://friday-succulent-marigold.ngrok-free.dev'; // LHOST
const DATA_FILE = path.join(__dirname, 'data', 'sessions.json');

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.urlencoded({ extended: true }));
app.use(express.json());

/* ------------------------- Penyimpanan Sesi ------------------------- */
// state = {
//   pairings: { "<code>": { deviceId, deviceModel, pairedAt } },
//   controls: { "<deviceId>": { screenLock:{active,password}, blockedApps:[...] , lockAll } }
// }
let state = { pairings: {}, controls: {} };

function loadData() {
  try {
    if (fs.existsSync(DATA_FILE)) {
      state = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
      if (!state.pairings) state.pairings = {};
      if (!state.controls) state.controls = {};
    }
  } catch (e) { console.error('loadData error:', e.message); }
}
function saveData() {
  try {
    fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
    fs.writeFileSync(DATA_FILE, JSON.stringify(state, null, 2));
  } catch (e) { console.error('saveData error:', e.message); }
}
loadData();

function randomCode() {
  let c;
  do { c = String(Math.floor(100000 + Math.random() * 900000)); }
  while (state.pairings[c]);
  return c;
}

/* Mapping deviceId -> socket id anak / orang tua yang sedang online */
const childSockets = new Map();   // deviceId -> socketId
const parentSockets = new Map();  // deviceId -> socketId

function defaultControl(deviceId) {
  return {
    deviceId,
    screenLock: { active: false, password: '' },
    blockedApps: [],
    lockAll: false
  };
}
if (!state.controls) state.controls = {};

/* ------------------------------ Halaman ----------------------------- */
app.get('/', (req, res) => {
  res.render('index', { error: null, ngrok: NGROK_URL });
});

// Submit pairing code dari form web
app.post('/pair', (req, res) => {
  const code = String(req.body.code || '').trim();
  if (!/^\d{6}$/.test(code)) {
    return res.render('index', { error: 'Kode harus 6 digit angka.', ngrok: NGROK_URL });
  }
  const pairing = state.pairings[code];
  if (!pairing) {
    return res.render('index', { error: 'Kode tidak ditemukan. Buka aplikasi "children" di HP anak dan masukkan kode yang tampil.', ngrok: NGROK_URL });
  }
  req.session && (req.session.deviceId = pairing.deviceId); // (fallback, pakai cookie di bawah)
  // simpan pilihan orang tua di cookie agar refresh tetap masuk panel
  res.setHeader('Set-Cookie', `parent_device=${pairing.deviceId}; Path=/; Max-Age=86400`);
  return res.redirect(`/panel?d=${encodeURIComponent(pairing.deviceId)}`);
});

app.get('/panel', (req, res) => {
  const deviceId = req.query.d || getCookieDevice(req);
  if (!deviceId || !findPairByDevice(deviceId)) {
    return res.redirect('/');
  }
  res.render('panel', { deviceId, ngrok: NGROK_URL });
});

function getCookieDevice(req) {
  const cookies = (req.headers.cookie || '').split(';').map(c => c.trim());
  for (const c of cookies) {
    if (c.startsWith('parent_device=')) return c.split('=')[1];
  }
  return null;
}
function findPairByDevice(deviceId) {
  for (const code in state.pairings) {
    if (state.pairings[code].deviceId === deviceId) return { code, ...state.pairings[code] };
  }
  return null;
}

// REST: info perangkat + status (dipakai halaman panel & bisa dipakai debugging)
app.get('/api/device/:deviceId', (req, res) => {
  const deviceId = req.params.deviceId;
  const pair = findPairByDevice(deviceId);
  if (!pair) return res.status(404).json({ error: 'device not paired' });
  const ctrl = state.controls[deviceId] || defaultControl(deviceId);
  const childSocketId = childSockets.get(deviceId);
  res.json({
    deviceId,
    pairingCode: pair.code,
    pairedAt: pair.pairedAt,
    online: !!childSocketId,
    lastSeen: pair.lastSeen || null,
    battery: pair.battery || null,
    pairing: pair,
    control: ctrl
  });
});

/* --------------------------- Socket.IO ------------------------------ */
io.on('connection', (socket) => {

  /* ====== PERANGKAT ANAK (APK com.company.children) ====== */
  socket.on('child:hello', (payload, cb) => {
    // payload: { deviceId, model, androidVersion, battery }
    const deviceId = String(payload.deviceId || socket.id);
    socket.data.role = 'child';
    socket.data.deviceId = deviceId;
    childSockets.set(deviceId, socket.id);

    // buat / ambil pairing code aktif untuk perangkat ini
    let code = null;
    for (const c in state.pairings) {
      if (state.pairings[c].deviceId === deviceId) { code = c; break; }
    }
    if (!code) { code = randomCode(); }
    state.pairings[code] = {
      deviceId,
      deviceModel: payload.model || 'unknown',
      androidVersion: payload.androidVersion || '',
      pairedAt: (state.pairings[code] && state.pairings[code].pairedAt) || new Date().toISOString(),
      lastSeen: new Date().toISOString(),
      battery: payload.battery != null ? payload.battery : null
    };
    if (!state.controls[deviceId]) state.controls[deviceId] = defaultControl(deviceId);
    saveData();

    const ackData = { code, control: state.controls[deviceId] };
    if (typeof cb === 'function') cb(ackData);
    console.log(`[child] ${deviceId} (${payload.model}) online -> code ${code}`);
    broadcastStatus(deviceId, true);

    // beri tahu HP anak bila web sudah terlanjur menautkan kode ini sebelumnya
    if (parentSockets.has(deviceId)) {
      io.to(socket.id).emit('parent:paired', { code });
    }
  });

  socket.on('child:status', (payload) => {
    const deviceId = socket.data.deviceId;
    if (!deviceId) return;
    for (const c in state.pairings) {
      if (state.pairings[c].deviceId === deviceId) {
        state.pairings[c].lastSeen = new Date().toISOString();
        state.pairings[c].battery = payload.battery != null ? payload.battery : state.pairings[c].battery;
        state.pairings[c].currentApp = payload.currentApp || null;
        saveData();
      }
    }
    broadcastStatus(deviceId, true, payload);
  });

  socket.on('disconnect', () => {
    if (socket.data.role === 'child') {
      const deviceId = socket.data.deviceId;
      if (childSockets.get(deviceId) === socket.id) childSockets.delete(deviceId);
      console.log(`[child] ${deviceId} offline`);
      broadcastStatus(deviceId, false);
    }
    if (socket.data.role === 'parent') {
      const deviceId = socket.data.deviceId;
      if (parentSockets.get(deviceId) === socket.id) parentSockets.delete(deviceId);
    }
  });

  /* ====== ORANG TUA (web panel) ====== */
  socket.on('parent:join', (payload, cb) => {
    const deviceId = String(payload.deviceId);
    if (!findPairByDevice(deviceId)) {
      if (typeof cb === 'function') cb({ ok: false, error: 'Perangkat belum ditautkan.' });
      return;
    }
    socket.data.role = 'parent';
    socket.data.deviceId = deviceId;
    parentSockets.set(deviceId, socket.id);
    const ctrl = state.controls[deviceId] || defaultControl(deviceId);
    if (typeof cb === 'function') cb({ ok: true, control: ctrl, online: childSockets.has(deviceId) });

    // Kabari HP anak bahwa orang tua sudah masuk panel => sesi ditautkan (pairing selesai)
    const childSocketId = childSockets.get(deviceId);
    if (childSocketId) {
      const codeEntry = findPairByDevice(deviceId);
      io.to(childSocketId).emit('parent:paired', { code: codeEntry ? codeEntry.code : '' });
    }
  });

  // Kirim control (kunci layar / blokir aplikasi) ke HP anak
  socket.on('parent:control', (ctrl) => {
    const deviceId = socket.data.deviceId;
    if (!deviceId) return;
    const next = {
      deviceId,
      screenLock: {
        active: !!ctrl.screenLock.active,
        password: String(ctrl.screenLock.password || '')
      },
      blockedApps: Array.isArray(ctrl.blockedApps)
        ? ctrl.blockedApps.map(p => String(p).trim()).filter(Boolean) : [],
      lockAll: !!ctrl.lockAll
    };
    state.controls[deviceId] = next;
    saveData();

    const childSocketId = childSockets.get(deviceId);
    if (childSocketId) {
      io.to(childSocketId).emit('parent:control', next);
      console.log(`[parent->child] ${deviceId} control applied:`, JSON.stringify(next));
    }
    // sinkron ke tab parent lain
    io.to(`watch_${deviceId}`).emit('control:update', next);
  });

  socket.on('parent:watch', (payload) => {
    socket.join(`watch_${payload.deviceId}`);
  });

  socket.on('parent:ping-child', (payload, cb) => {
    const childSocketId = childSockets.get(payload.deviceId);
    if (childSocketId) {
      io.to(childSocketId).timeout(5000).emit('parent:ping', {}, (err, resp) => {
        if (typeof cb === 'function') cb({ ok: !err, resp: resp || null });
      });
    } else if (typeof cb === 'function') cb({ ok: false });
  });
});

function broadcastStatus(deviceId, online, extra = {}) {
  io.to(`watch_${deviceId}`).emit('child:status', { deviceId, online, ...extra });
}

/* ------------------------------ Mulai ------------------------------- */
server.listen(PORT, () => {
  console.log('==========================================================');
  console.log('  PARENTAL server (web parental control) berjalan.');
  console.log(`  Lokal  : http://localhost:${PORT}`);
  console.log(`  Jaringan: http://${getIP()}:${PORT}`);
  console.log(`  LHOST (ngrok): ${NGROK_URL}`);
  console.log('  Jalankan tunnel:  ngrok http 8000');
  console.log('  Lalu pasang URL ngrok hasil tunnel ke BuildConfig.SERVER_URL di APK.');
  console.log('==========================================================');
});

function getIP() {
  const ifs = os.networkInterfaces();
  for (const name of Object.keys(ifs)) {
    for (const i of ifs[name] || []) {
      if (i.family === 'IPv4' && !i.internal) return i.address;
    }
  }
  return '127.0.0.1';
}
