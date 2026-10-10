/* SwiftSSTP frontend — buildless vanilla JS driving the single Go binding
 * (window.go.main.App.Call) plus two push events: "status" and "log". */

"use strict";

/* ---------------- persistence keys ---------------- */
/* Settings live under "swiftsstp.*". Pre-rename SSTPflash builds used
 * "sstpflash.*"; those values are copied forward once on init so upgrades
 * keep the selected profile and tunnel mode. */
function migratedKey(newKey, legacyKey) {
  const v = localStorage.getItem(newKey);
  if (v !== null) return v;
  const legacy = localStorage.getItem(legacyKey);
  if (legacy !== null) localStorage.setItem(newKey, legacy);
  return legacy;
}

/* ---------------- state ---------------- */
const state = {
  profiles: [],
  status: { state: "disconnected" },
  mode: migratedKey("swiftsstp.mode", "sstpflash.mode") || "proxy",
  selectedProfile: migratedKey("swiftsstp.selectedProfile", "sstpflash.selectedProfile") || "",
  editing: null, // profile object being edited
  geoip: null,   // last {ip,country,colo} from the geoip action (cached)
};

/* ---------------- helpers ---------------- */
async function call(action, args) {
  const raw = await window.go.main.App.Call(action, JSON.stringify(args || {}));
  const res = JSON.parse(raw);
  if (!res.ok) throw new Error(res.error || "unknown error");
  return res.data;
}

function $(id) { return document.getElementById(id); }

function toast(msg, isError) {
  const el = $("toast");
  el.textContent = msg;
  el.classList.toggle("error", !!isError);
  el.classList.remove("hidden");
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.add("hidden"), 3500);
}

function fmtBytes(n) {
  if (!n) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)) + " " + units[i];
}

function fmtRate(n) { return n ? fmtBytes(n) + "/s" : "—"; }

/* Parallel tunnels: schema allows 1..8; anything else is coerced. */
function clampTunnels(n) {
  n = parseInt(n, 10);
  if (isNaN(n) || n < 1) return 1;
  if (n > 8) return 8;
  return n;
}

/* Proxy fields are only meaningful when a type is selected. */
function toggleProxyFields() {
  const on = $("f-proxy-type").value !== "";
  $("proxy-wrap").classList.toggle("hidden", !on);
}

function fmtTime(rfc3339) {
  if (!rfc3339) return "—";
  try {
    const d = new Date(rfc3339);
    return d.toLocaleString();
  } catch { return rfc3339; }
}

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

let toastOKTimer = null;
function toastOK(msg) { toast(msg, false); if (toastOKTimer) clearTimeout(toastOKTimer); }

/* ---------------- routing ---------------- */
function route() {
  const hash = location.hash || "#/";
  const isEditor = hash.startsWith("#/profile/");
  const isProfiles = hash === "#/profiles";
  const isStats = hash === "#/stats";
  const isLog = hash === "#/log";
  const isHome = hash === "#/" || hash === "";

  $("view-home").classList.toggle("hidden", !isHome);
  $("view-profiles").classList.toggle("hidden", !isProfiles);
  $("view-stats").classList.toggle("hidden", !isStats);
  $("view-log").classList.toggle("hidden", !isLog);
  $("view-editor").classList.toggle("hidden", !isEditor);

  $("nav-home").classList.toggle("active", isHome);
  $("nav-profiles").classList.toggle("active", isProfiles || isEditor);
  $("nav-stats").classList.toggle("active", isStats);
  $("nav-log").classList.toggle("active", isLog);

  // The segmented thumb measures the active button; re-measure when the
  // home view becomes visible again (offsetWidth is 0 while hidden).
  if (isHome) {
    requestAnimationFrame(positionThumb);
    refreshGeo(); // Home shown → refresh the egress IP card
  }

  if (isProfiles) renderProfiles();
  if (isEditor) {
    const id = decodeURIComponent(hash.slice("#/profile/".length));
    openEditor(id);
  }
}

function homeVisible() {
  const v = $("view-home");
  return !!v && !v.classList.contains("hidden");
}

/* ---------------- connection screen ---------------- */
function renderProfileSelect() {
  const sel = $("conn-profile");
  const cur = state.selectedProfile;
  sel.innerHTML = "";
  if (state.profiles.length === 0) {
    const o = document.createElement("option");
    o.value = "";
    o.textContent = "— no profiles, create one —";
    sel.appendChild(o);
    return;
  }
  for (const p of state.profiles) {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = `${p.name || "unnamed"} (${p.host}:${p.port || 443})`;
    sel.appendChild(o);
  }
  if (state.profiles.some(p => p.id === cur)) sel.value = cur;
  else state.selectedProfile = sel.value;
  localStorage.setItem("swiftsstp.selectedProfile", state.selectedProfile);
}

function setMode(mode) {
  state.mode = mode;
  localStorage.setItem("swiftsstp.mode", mode);
  $("mode-toggle").dataset.mode = mode;
  document.querySelectorAll("#mode-toggle .seg").forEach(b => {
    b.classList.toggle("active", b.dataset.mode === mode);
  });
  positionThumb();
  renderStatus();
}

function positionThumb() {
  const seg = $("mode-toggle");
  const active = seg.querySelector(".seg.active");
  const thumb = seg.querySelector(".seg-thumb");
  if (!active || !thumb) return;
  thumb.style.width = active.offsetWidth + "px";
  thumb.style.transform = `translateX(${active.offsetLeft - 4}px)`;
}

const CONNECTED_STATES = ["connected"];
const BUSY_STATES = ["connecting", "reconnecting"];

function renderStatus() {
  const s = state.status || {};
  const st = s.state || "disconnected";

  const pill = $("status-pill");
  pill.dataset.state = st;
  pill.textContent = st.replace("_", " ");

  const big = $("state-big");
  const sub = $("state-sub");
  const btn = $("btn-connect");

  const prof = state.profiles.find(p => p.id === s.profile_id);
  if (prof) state.selectedProfile = prof.id;

  // The circular Home control carries no label text (icon only): state is
  // driven by data-state (CSS colours/pulses) + aria-label/title.
  function orb(label, busy) {
    btn.dataset.state = st;
    btn.setAttribute("aria-label", label);
    btn.title = label;
    if (busy) btn.classList.add("busy");
    else btn.classList.remove("busy");
  }

  switch (st) {
    case "connected":
      big.textContent = "Connected";
      big.style.color = "var(--green)";
      sub.textContent = `${s.mode === "tunnel" ? "whole-device tunnel" : "local proxy"} · ${s.profile_name || ""}`;
      orb("Disconnect", false);
      break;
    case "connecting":
      big.textContent = "Connecting…";
      big.style.color = "var(--amber)";
      sub.textContent = `${s.profile_name || ""} · ${s.mode || ""} mode`;
      orb("Stop", true);
      break;
    case "reconnecting":
      big.textContent = "Reconnecting…";
      big.style.color = "var(--amber)";
      sub.textContent = `attempt ${s.reconnect_attempts || 0} · ${s.last_error || ""}`;
      orb("Stop", true);
      break;
    case "auth_error":
      big.textContent = "Auth rejected";
      big.style.color = "var(--red)";
      sub.textContent = s.last_error || "credentials rejected by server";
      orb("Connect", false);
      break;
    case "error":
      big.textContent = "Error";
      big.style.color = "var(--red)";
      sub.textContent = s.last_error || "connection failed";
      orb("Connect", false);
      break;
    default:
      big.textContent = "Idle";
      big.style.color = "var(--text)";
      sub.textContent = state.profiles.length ? "ready" : "create a profile first";
      orb("Connect", false);
  }

  $("s-up").textContent = s.up ? fmtBytes(s.up) : "—";
  $("s-down").textContent = s.down ? fmtBytes(s.down) : "—";
  $("s-rate-up").textContent = fmtRate(s.rate_up);
  $("s-rate-down").textContent = fmtRate(s.rate_down);
  $("s-since").textContent = st === "connected" ? fmtTime(s.since) : "—";
  $("s-total-up").textContent = s.total_up ? fmtBytes(s.total_up) : "—";
  $("s-total-down").textContent = s.total_down ? fmtBytes(s.total_down) : "—";
  $("s-sessions").textContent = s.sessions ?? "—";
  $("s-attempts").textContent = s.reconnect_attempts ?? "—";
  $("s-profile-name").textContent = s.profile_name ? `· ${s.profile_name}` : "";
  $("s-local").textContent = s.local_ip || "—";
  $("s-peer").textContent = s.peer_ip || "—";
  $("s-mtu").textContent = s.mtu || "—";
  $("s-dns").textContent = (s.dns && s.dns.length) ? s.dns.join(", ") : "—";
  $("s-cert").textContent = s.cert_fingerprint || "—";
  $("s-error").textContent = s.last_error || "—";

  const socks = s.socks_port || 10808;
  const http = s.http_port || 10809;
  $("p-socks").textContent = `127.0.0.1:${socks}`;
  $("p-http").textContent = `127.0.0.1:${http}`;
  $("p-sysproxy").textContent = s.system_proxy_on ? "ON" : "off";
  $("proxy-card").style.opacity = (st === "connected" || BUSY_STATES.includes(st)) ? "1" : ".6";

  renderTunnels(s);
}

/* per-session chips + upstream proxy indicator */
function renderTunnels(s) {
  const list = s.tunnel_sessions || [];
  const readyN = list.filter(t => t.ready).length;
  $("t-tunnels").textContent = s.tunnels ? `${s.tunnels} max` : "—";
  $("t-ready").textContent = list.length ? `${readyN} / ${list.length}` : "—";
  const px = $("t-proxy");
  px.textContent = s.proxy || "direct";
  px.classList.toggle("proxy-on", !!s.proxy);

  const box = $("session-chips");
  box.innerHTML = "";
  if (!list.length) {
    const span = document.createElement("span");
    span.className = "chip idle";
    span.textContent = (s.state === "connected") ? "starting sessions…" : "no sessions";
    box.appendChild(span);
    return;
  }
  for (const t of list) {
    const c = document.createElement("span");
    c.className = "chip " + (t.ready ? "ready" : "down");
    c.innerHTML = `<b>#${(t.index ?? 0) + 1}</b><span>${esc(t.local_ip || "…")}</span>` +
      `<span>↑${fmtBytes(t.up)} ↓${fmtBytes(t.down)}</span>` +
      `<i>${t.ready ? "ready" : "down"}</i>`;
    box.appendChild(c);
  }
}

/* ---------------- egress IP card (geoip) ---------------- */
/* Home's IP card shows the public address Cloudflare sees. The Go binding
 * dials through the local SOCKS5 inbound in proxy mode so it is the
 * tunnelled egress; results are cached so the card never flashes empty. */
const GEO_INTERVAL_MS = 60000;   // while Home is visible
const GEO_DEBOUNCE_MS = 2000;    // after a connection-state change
let geoBusy = false;
let geoDebounce = null;
let geoLastKey = null;
let geoLabelTimer = null;

function loadGeoCache() {
  try {
    const raw = localStorage.getItem("swiftsstp.geoip");
    if (raw) state.geoip = JSON.parse(raw);
  } catch { /* corrupt cache — drop it */ }
}

function renderGeo() {
  const g = state.geoip;
  const val = $("ip-value");
  const flag = $("ip-flag");
  val.textContent = (g && g.ip) ? g.ip : "—";

  const cc = String((g && g.country) || "").trim().toUpperCase();
  if (!g || !g.ip || !/^[A-Z]{2}$/.test(cc)) { // blank country → no flag
    flag.dataset.cc = "";
    flag.innerHTML = "";
    flag.classList.add("hidden");
    return;
  }
  if (flag.dataset.cc === cc && flag.childNodes.length) {
    flag.classList.remove("hidden");
    return;
  }
  flag.dataset.cc = cc;
  flag.innerHTML = "";
  const img = document.createElement("img");
  // flagcdn serves lowercase codes; alt/badge use the uppercase country code.
  img.src = `https://flagcdn.com/w40/${cc.toLowerCase()}.png`;
  img.width = 28;
  img.height = 21;
  img.alt = cc;
  img.onerror = () => {           // no flag → 2-letter text badge
    if (flag.dataset.cc !== cc) return;
    flag.innerHTML = "";
    flag.textContent = cc;
  };
  flag.appendChild(img);
  flag.classList.remove("hidden");
}

async function refreshGeo() {
  if (geoBusy) return;
  geoBusy = true;
  try {
    const d = await call("geoip");
    if (d && d.ip) {
      state.geoip = d;
      try { localStorage.setItem("swiftsstp.geoip", JSON.stringify(d)); } catch { /* quota */ }
      renderGeo();
    }
  } catch { /* keep the last cached value */ }
  finally { geoBusy = false; }
}

/* "status" push (or poll) with a new connection state → refresh after 2s. */
function noteStatusForGeo(s) {
  const key = `${(s && s.state) || "disconnected"}|${(s && s.mode) || ""}`;
  if (key === geoLastKey) return;
  geoLastKey = key;
  clearTimeout(geoDebounce);
  geoDebounce = setTimeout(() => { if (homeVisible()) refreshGeo(); }, GEO_DEBOUNCE_MS);
}

async function copyIP() {
  const g = state.geoip;
  if (!g || !g.ip) return;
  try {
    await navigator.clipboard.writeText(g.ip);
  } catch {
    const ta = document.createElement("textarea");
    ta.value = g.ip; document.body.appendChild(ta); ta.select();
    document.execCommand("copy"); ta.remove();
  }
  const label = $("ip-label");
  label.textContent = "Copied";
  clearTimeout(geoLabelTimer);
  geoLabelTimer = setTimeout(() => { label.textContent = "IP address"; }, 1500);
}

/* ---------------- log ---------------- */
function appendLog(line) {
  const box = $("log");
  const div = document.createElement("div");
  if (/error|fail|reject|warning|WARNING/i.test(line)) div.className = "err";
  else if (/ up local=|READY|connected|saved/i.test(line)) div.className = "ok";
  div.textContent = line;
  box.appendChild(div);
  while (box.childNodes.length > 500) box.removeChild(box.firstChild);
  box.scrollTop = box.scrollHeight;
}

/* ---------------- profiles ---------------- */
function tlsTag(p) {
  const m = p.tls_mode || "verify";
  const label = m === "insecure" ? "insecure" : m === "custom_ca" ? "custom CA" : "verify";
  const cls = m === "insecure" ? "tag warn" : "tag ok";
  return `<span class="${cls}">TLS ${esc(label)}</span>`;
}

function renderProfiles() {
  const grid = $("profile-grid");
  const empty = $("profile-empty");
  grid.innerHTML = "";
  empty.classList.toggle("hidden", state.profiles.length > 0);
  for (const p of state.profiles) {
    const card = document.createElement("div");
    card.className = "card profile-card";
    const extras = p.extras || {};
    card.innerHTML = `
      <div class="p-name">${esc(p.name || "unnamed")}</div>
      <div class="p-host">${esc(p.host)}:${p.port || 443}${p.sni ? " · sni " + esc(p.sni) : ""}</div>
      <div class="p-meta">
        ${tlsTag(p)}
        <span class="tag">${esc(p.username || "—")}</span>
        ${p.auto_reconnect && p.auto_reconnect.enabled ? '<span class="tag">auto-reconnect</span>' : ""}
        ${extras.system_proxy ? '<span class="tag">system proxy</span>' : ""}
        ${p.proxy && p.proxy.type ? `<span class="tag warn">proxy ${esc(p.proxy.type)}</span>` : ""}
        ${(p.tunnels || 1) > 1 ? `<span class="tag ok">${esc(String(p.tunnels))} tunnels</span>` : ""}
      </div>
      <div class="p-actions">
        <button class="btn-ghost" data-act="edit">Edit</button>
        <button class="btn-ghost" data-act="dup">Duplicate</button>
        <button class="btn-ghost" data-act="del">Delete</button>
        <button class="btn-ghost" data-act="use">Use</button>
      </div>`;
    card.querySelectorAll("button").forEach(b => {
      b.addEventListener("click", async () => {
        const act = b.dataset.act;
        try {
          if (act === "edit") { location.hash = `#/profile/${encodeURIComponent(p.id)}`; }
          else if (act === "use") {
            state.selectedProfile = p.id;
            localStorage.setItem("swiftsstp.selectedProfile", p.id);
            renderProfileSelect();
            location.hash = "#/";
            toast(`Selected “${p.name}”`);
          } else if (act === "dup") {
            const d = await call("profile_duplicate", { id: p.id });
            await loadProfiles();
            renderProfiles();
            toast(`Duplicated as “${d.profile.name}”`);
          } else if (act === "del") {
            if (b.dataset.armed !== "1") {
              b.dataset.armed = "1";
              b.textContent = "Confirm?";
              b.style.borderColor = "var(--red)";
              b.style.color = "var(--red)";
              setTimeout(() => {
                b.dataset.armed = "";
                b.textContent = "Delete";
                b.style.borderColor = "";
                b.style.color = "";
              }, 3000);
              return;
            }
            await call("profile_delete", { id: p.id });
            await loadProfiles();
            renderProfiles();
            toast("Profile deleted");
          }
        } catch (e) { toast(e.message, true); }
      });
    });
    grid.appendChild(card);
  }
}

/* ---------------- editor ---------------- */
async function openEditor(id) {
  try {
    let p;
    if (id === "new") {
      p = await call("profile_new");
      $("editor-title").textContent = "New profile";
    } else {
      await loadProfiles();
      p = state.profiles.find(x => x.id === id);
      if (!p) { location.hash = "#/profiles"; return; }
      // deep copy so unsaved edits don't mutate state
      p = JSON.parse(JSON.stringify(p));
      $("editor-title").textContent = `Edit · ${p.name || "profile"}`;
    }
    state.editing = p;
    fillForm(p);
  } catch (e) { toast(e.message, true); }
}

function fillForm(p) {
  const ar = p.auto_reconnect || {};
  const ex = p.extras || {};
  $("f-name").value = p.name || "";
  $("f-host").value = p.host || "";
  $("f-port").value = p.port || 443;
  $("f-sni").value = p.sni || "";
  $("f-user").value = p.username || "";
  $("f-pass").value = p.password || "";
  $("f-tls").value = p.tls_mode || "verify";
  $("f-ca").value = p.ca_path || "";
  $("f-mtu").value = p.mtu || 0;
  $("f-ipv4").checked = !!p.ipv4_enabled;
  $("f-ipv6").checked = !!p.ipv6_enabled;
  $("f-dns").value = (p.dns_override || []).join("\n");
  $("f-reconnect").checked = ar.enabled !== false;
  $("f-backoff-init").value = ar.initial_backoff_ms || 1000;
  $("f-backoff-max").value = ar.max_backoff_ms || 30000;
  $("f-socks").value = ex.socks_port || 10808;
  $("f-http").value = ex.http_port || 10809;
  $("f-sysproxy").checked = !!ex.system_proxy;
  const px = p.proxy || {};
  $("f-proxy-type").value = px.type || "";
  $("f-proxy-host").value = px.host || "";
  $("f-proxy-port").value = px.port || 3128;
  $("f-proxy-user").value = px.username || "";
  $("f-proxy-pass").value = px.password || "";
  toggleProxyFields();
  $("f-tunnels").value = clampTunnels(p.tunnels);
  $("ca-wrap").classList.toggle("hidden", $("f-tls").value !== "custom_ca");
}

function collectForm() {
  const p = state.editing || {};
  const dns = $("f-dns").value.split(/[\r\n,]+/).map(s => s.trim()).filter(Boolean);
  return {
    id: p.id || "",
    name: $("f-name").value.trim(),
    host: $("f-host").value.trim(),
    port: parseInt($("f-port").value, 10) || 443,
    sni: $("f-sni").value.trim(),
    username: $("f-user").value,
    password: $("f-pass").value,
    tls_mode: $("f-tls").value,
    ca_path: $("f-ca").value.trim(),
    mtu: parseInt($("f-mtu").value, 10) || 0,
    ipv4_enabled: $("f-ipv4").checked,
    ipv6_enabled: $("f-ipv6").checked,
    dns_override: dns,
    auto_reconnect: {
      enabled: $("f-reconnect").checked,
      initial_backoff_ms: parseInt($("f-backoff-init").value, 10) || 1000,
      max_backoff_ms: parseInt($("f-backoff-max").value, 10) || 30000,
    },
    proxy: {
      type: $("f-proxy-type").value,
      host: $("f-proxy-host").value.trim(),
      port: parseInt($("f-proxy-port").value, 10) || 0,
      username: $("f-proxy-user").value,
      password: $("f-proxy-pass").value,
    },
    tunnels: clampTunnels($("f-tunnels").value),
    extras: {
      socks_port: parseInt($("f-socks").value, 10) || 10808,
      http_port: parseInt($("f-http").value, 10) || 10809,
      system_proxy: $("f-sysproxy").checked,
    },
  };
}

async function saveProfile() {
  try {
    const p = collectForm();
    const res = await call("profile_save", { profile: p });
    state.editing = res.profile;
    await loadProfiles();
    state.selectedProfile = res.profile.id;
    localStorage.setItem("swiftsstp.selectedProfile", res.profile.id);
    renderProfileSelect();
    toast("Profile saved");
    location.hash = "#/profiles";
  } catch (e) { toast(e.message, true); }
}

/* ---------------- data loading ---------------- */
async function loadProfiles() {
  const d = await call("profiles_list");
  state.profiles = d.profiles || [];
  renderProfileSelect();
}

async function refreshStatus() {
  try {
    state.status = await call("status");
    renderStatus();
    noteStatusForGeo(state.status);
  } catch { /* backend not ready yet */ }
}

async function connectToggle() {
  const s = state.status || {};
  const busy = BUSY_STATES.includes(s.state) || s.state === "connected";
  if (busy) {
    $("btn-connect").disabled = true;
    try { await call("disconnect"); } catch (e) { toast(e.message, true); }
    setTimeout(() => { $("btn-connect").disabled = false; }, 400);
    return;
  }
  const id = $("conn-profile").value;
  if (!id) { toast("Create a profile first", true); location.hash = "#/profiles"; return; }
  try {
    await call("connect", { id, mode: state.mode });
    toast(`Connecting (${state.mode} mode)…`);
  } catch (e) { toast(e.message, true); }
}

/* ---------------- wiring ---------------- */
function wire() {
  window.addEventListener("hashchange", route);

  document.querySelectorAll("#mode-toggle .seg").forEach(b => {
    b.addEventListener("click", () => setMode(b.dataset.mode));
  });

  $("conn-profile").addEventListener("change", e => {
    state.selectedProfile = e.target.value;
    localStorage.setItem("swiftsstp.selectedProfile", state.selectedProfile);
  });

  $("btn-connect").addEventListener("click", connectToggle);

  // IP card: tap to copy (existing clipboard pattern).
  $("ip-card").addEventListener("click", copyIP);
  $("ip-card").addEventListener("keydown", e => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); copyIP(); }
  });

  $("btn-copy-cert").addEventListener("click", async () => {
    const fp = $("s-cert").textContent;
    if (!fp || fp === "—") return;
    try {
      await navigator.clipboard.writeText(fp);
      toast("Fingerprint copied");
    } catch {
      const ta = document.createElement("textarea");
      ta.value = fp; document.body.appendChild(ta); ta.select();
      document.execCommand("copy"); ta.remove();
      toast("Fingerprint copied");
    }
  });

  $("btn-clear-log").addEventListener("click", async () => {
    $("log").innerHTML = "";
    try { await call("logs_clear"); } catch {}
  });

  $("btn-new-profile").addEventListener("click", () => { location.hash = "#/profile/new"; });
  $("btn-cancel").addEventListener("click", () => { location.hash = "#/profiles"; });
  $("btn-save").addEventListener("click", saveProfile);
  $("f-tls").addEventListener("change", () => {
    $("ca-wrap").classList.toggle("hidden", $("f-tls").value !== "custom_ca");
  });

  // Upstream proxy + tunnels editor controls.
  $("f-proxy-type").addEventListener("change", toggleProxyFields);
  $("f-tunnels").addEventListener("change", () => {
    $("f-tunnels").value = clampTunnels($("f-tunnels").value);
  });
  $("tunnels-dec").addEventListener("click", () => {
    $("f-tunnels").value = clampTunnels(parseInt($("f-tunnels").value, 10) - 1);
  });
  $("tunnels-inc").addEventListener("click", () => {
    $("f-tunnels").value = clampTunnels(parseInt($("f-tunnels").value, 10) + 1);
  });
}

async function init() {
  wire();
  setMode(state.mode);
  loadGeoCache();
  renderGeo();
  route();

  // Wails injects window.go / window.runtime; retry briefly if not ready yet.
  for (let i = 0; i < 50 && !(window.go && window.go.main && window.go.main.App); i++) {
    await new Promise(r => setTimeout(r, 100));
  }
  if (!(window.go && window.go.main && window.go.main.App)) {
    appendLog("error: Wails bindings unavailable (running outside the app shell?)");
    return;
  }

  if (window.runtime) {
    window.runtime.EventsOn("status", s => {
      state.status = s;
      renderStatus();
      noteStatusForGeo(s);
    });
    window.runtime.EventsOn("log", line => appendLog(line));
  }

  try {
    const logs = await call("logs");
    (logs.lines || []).forEach(appendLog);
    await loadProfiles();
    await refreshStatus();
  } catch (e) { toast(e.message, true); }

  // Fallback poll in case an event is missed.
  setInterval(refreshStatus, 2500);
  // Egress IP: refresh every minute while Home is on screen (plus the
  // on-show and on-state-change triggers wired above).
  setInterval(() => { if (homeVisible()) refreshGeo(); }, GEO_INTERVAL_MS);
  if (homeVisible()) refreshGeo();
  window.addEventListener("resize", positionThumb);
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
