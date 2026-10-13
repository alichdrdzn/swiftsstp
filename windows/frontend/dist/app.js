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
  settings: null, // app-wide settings from settings_get / settings_save
  geoip: null,   // last {ip,country,colo} from the geoip action (cached)
  version: "",       // running build version from app_info ("dev" = unstamped)
  update: null,      // {version, size, …} when the stable channel has one
  updatePhase: "none", // none|ready|downloading|confirm|applying|error
  updateProgress: null,
  updatePath: "",    // verified download awaiting the user's confirm
  updateError: "",
  updateUnwritable: false, // install dir needs elevation → no retry, open the page
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
  const isSettings = hash === "#/settings";
  const isHome = hash === "#/" || hash === "";

  $("view-home").classList.toggle("hidden", !isHome);
  $("view-profiles").classList.toggle("hidden", !isProfiles);
  $("view-stats").classList.toggle("hidden", !isStats);
  $("view-log").classList.toggle("hidden", !isLog);
  $("view-editor").classList.toggle("hidden", !isEditor);
  $("view-settings").classList.toggle("hidden", !isSettings);

  // Home drops the top bar entirely: the hero wordmark is the branding there.
  $("topbar").classList.toggle("hidden", isHome);

  $("nav-home").classList.toggle("active", isHome);
  $("nav-profiles").classList.toggle("active", isProfiles || isEditor);
  $("nav-stats").classList.toggle("active", isStats);
  $("nav-log").classList.toggle("active", isLog);
  $("nav-settings").classList.toggle("active", isSettings);

  // The segmented thumb measures the active button; re-measure when the
  // home view becomes visible again (offsetWidth is 0 while hidden).
  if (isHome) {
    requestAnimationFrame(positionThumb);
    closeProfileMenu(); // never leave the list hanging off a hidden view
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
/* The profile picker is a custom dropdown: a native <select> lets the OS
 * size the option popup however it likes, which is why it never matched
 * the pill. The list is absolutely positioned at width:100% of the
 * trigger, so the two are the same size by construction. Picking an
 * entry writes state.selectedProfile and its localStorage key — the same
 * state the old <select>'s "change" listener wrote. */
function renderProfileSelect() {
  const menu = $("conn-profile-menu");
  menu.innerHTML = "";
  if (state.profiles.length === 0) {
    state.selectedProfile = "";
    localStorage.setItem("swiftsstp.selectedProfile", "");
    const li = document.createElement("li");
    li.className = "profile-option";
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", "false");
    li.setAttribute("aria-disabled", "true");
    li.textContent = "— no profiles, create one —";
    menu.appendChild(li);
    syncProfileSelect();
    return;
  }
  for (let i = 0; i < state.profiles.length; i++) {
    const p = state.profiles[i];
    const li = document.createElement("li");
    li.className = "profile-option";
    li.id = `conn-profile-opt-${i}`;
    li.setAttribute("role", "option");
    li.dataset.id = p.id;
    const span = document.createElement("span");
    span.textContent = `${p.name || "unnamed"} (${p.host}:${p.port || 443})`;
    li.appendChild(span);
    menu.appendChild(li);
  }
  // Old behaviour kept: a selection that no longer exists falls back to
  // the first profile ("" when there are none at all).
  if (!state.profiles.some(p => p.id === state.selectedProfile)) {
    state.selectedProfile = state.profiles[0].id;
    localStorage.setItem("swiftsstp.selectedProfile", state.selectedProfile);
  }
  syncProfileSelect();
}

/* Paint the trigger label and the selected row from state. */
function syncProfileSelect() {
  const p = state.profiles.find(x => x.id === state.selectedProfile);
  $("conn-profile-label").textContent =
    p ? `${p.name || "unnamed"} (${p.host}:${p.port || 443})` : "— no profiles, create one —";
  for (const li of $("conn-profile-menu").querySelectorAll(".profile-option")) {
    li.setAttribute("aria-selected", li.dataset.id === state.selectedProfile ? "true" : "false");
  }
}

function profileMenuOpen() {
  return !$("conn-profile-menu").classList.contains("hidden");
}

/* Selectable rows only (the empty-state row is not). */
function profileMenuItems() {
  return Array.from($("conn-profile-menu").querySelectorAll(".profile-option[role=option]"))
    .filter(li => li.getAttribute("aria-disabled") !== "true");
}

let profileActive = -1;
function setActiveProfileOption(idx) {
  const items = profileMenuItems();
  profileActive = (idx >= 0 && idx < items.length) ? idx : -1;
  items.forEach((li, i) => li.classList.toggle("active", i === profileActive));
  if (profileActive >= 0) items[profileActive].scrollIntoView({ block: "nearest" });
}

function openProfileMenu() {
  $("conn-profile-menu").classList.remove("hidden");
  $("conn-profile-trigger").setAttribute("aria-expanded", "true");
  const idx = profileMenuItems().findIndex(li => li.dataset.id === state.selectedProfile);
  setActiveProfileOption(idx);
}

function closeProfileMenu() {
  const menu = $("conn-profile-menu");
  if (!menu || menu.classList.contains("hidden")) return;
  menu.classList.add("hidden");
  $("conn-profile-trigger").setAttribute("aria-expanded", "false");
  setActiveProfileOption(-1);
}

/* The old select's "change" handler: pick the profile and remember it. */
function selectProfile(id) {
  state.selectedProfile = id;
  localStorage.setItem("swiftsstp.selectedProfile", id);
  syncProfileSelect();
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
  if (prof && prof.id !== state.selectedProfile) {
    // A connect started elsewhere (duplicate, "Use", backend retry):
    // keep the picker's label on the profile that is actually running.
    state.selectedProfile = prof.id;
    syncProfileSelect();
  }

  // The Home pill toggle carries no label text: state is data-state driven
  // (CSS knob position / track colour / border breathe) plus aria-label and
  // title. The status word below stays pure white — the toggle owns colour.
  function setToggle(label, busy) {
    btn.dataset.state = st;
    btn.setAttribute("aria-label", label);
    btn.title = label;
    if (busy) btn.classList.add("busy");
    else btn.classList.remove("busy");
  }

  switch (st) {
    case "connected":
      big.textContent = "Connected";
      sub.textContent = `${s.mode === "tunnel" ? "whole-device tunnel" : "local proxy"} · ${s.profile_name || ""}`;
      setToggle("Disconnect", false);
      break;
    case "connecting":
      big.textContent = "Connecting…";
      sub.textContent = `${s.profile_name || ""} · ${s.mode || ""} mode`;
      setToggle("Stop", true);
      break;
    case "reconnecting":
      big.textContent = "Reconnecting…";
      sub.textContent = `attempt ${s.reconnect_attempts || 0} · ${s.last_error || ""}`;
      setToggle("Stop", true);
      break;
    case "auth_error":
      big.textContent = "Auth rejected";
      sub.textContent = s.last_error || "credentials rejected by server";
      setToggle("Connect", false);
      break;
    case "error":
      big.textContent = "Error";
      sub.textContent = s.last_error || "connection failed";
      setToggle("Connect", false);
      break;
    default:
      big.textContent = "Disconnected";
      sub.textContent = state.profiles.length ? "ready" : "create a profile first";
      setToggle("Connect", false);
  }
  // A concrete failure reason is worth red; the sub line is muted otherwise.
  sub.classList.toggle("err", (st === "error" || st === "auth_error") && !!s.last_error);

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

/* ---------------- update (stable channel, manual only) ----------------
 * Nothing here runs on launch or on a timer: the app never spends the
 * user's network on its own. The only trigger is the Settings tab's
 * "Check for updates" button (the orange top-bar pill just navigates
 * there). The download still shows bytes/percent and the replace+restart
 * is always confirmed by the user before the backend touches the exe. */
const UPDATE_POLL_MS = 400;
let updateBusy = false;   // also the "Checking…" guard: a second tap is ignored
let updatePoll = null;
let updateStatusTimer = null;

/* "dev" (or an unknown version) means CI never stamped this build — the
 * backend would refuse to call it an update anyway, so we never even ask. */
function isDevBuild() { return !state.version || state.version === "dev"; }

/* The row's outcome line. kind: "" = persistent muted, "err" = red and
 * persistent (the user should know the check did not happen), "ok" =
 * self-clearing so the row goes quiet again on its own. */
function setUpdateStatus(text, kind) {
  const el = $("update-status");
  clearTimeout(updateStatusTimer);
  el.classList.toggle("err", kind === "err");
  el.textContent = text || "";
  el.classList.toggle("hidden", !text);
  if (text && kind === "ok") {
    updateStatusTimer = setTimeout(() => el.classList.add("hidden"), 6000);
  }
}

/* Version on the left of the row, button state on the right. "dev" is
 * shown verbatim — it is a real value, not a missing one. */
function renderUpdateRow() {
  const v = state.version || "dev";
  $("update-check-ver").textContent = v === "dev" ? "dev" : `v${v}`;
  const btn = $("btn-check-update");
  btn.textContent = updateBusy ? "Checking…" : "Check for updates";
  btn.disabled = updateBusy || isDevBuild();
  if (isDevBuild()) {
    setUpdateStatus("Local build — updates only ship in releases");
  }
}

async function checkUpdate() {
  if (updateBusy || isDevBuild()) return;
  updateBusy = true;
  setUpdateStatus("");     // drop the previous outcome while we look again
  renderUpdateRow();
  try {
    const d = await call("update_check");
    // Never clobber a flow the user already started.
    if (state.updatePhase !== "none" && state.updatePhase !== "ready") return;
    state.update = d && d.available ? d : null;
    state.updatePhase = state.update ? "ready" : "none";
    renderUpdate();
    if (!state.update) setUpdateStatus("You're on the latest version", "ok");
  } catch {
    // Honest failure: we asked and could not get an answer. An update
    // already on screen stays there — a flaky network does not un-know it.
    setUpdateStatus("could not reach GitHub", "err");
  } finally {
    updateBusy = false;
    renderUpdateRow();
  }
}

function renderUpdate() {
  const info = state.update;
  $("update-pill").classList.toggle("hidden", !info);
  $("update-card").classList.toggle("hidden", !info);
  if (!info) return;

  const ins = $("btn-update-install"), msg = $("update-msg"),
        act = $("update-actions"), later = $("btn-update-later"),
        retry = $("btn-update-retry"), page = $("btn-update-page");
  ins.classList.remove("hidden");
  ins.textContent = "Install";
  msg.classList.add("hidden");
  msg.classList.remove("err");
  act.classList.add("hidden");
  later.classList.add("hidden");
  retry.classList.add("hidden");
  page.classList.add("hidden");
  $("update-ver").textContent = `v${info.version} available`;

  switch (state.updatePhase) {
    case "downloading": {
      ins.classList.add("hidden");
      const p = state.updateProgress || {};
      const total = p.total || info.size || 0;
      const done = p.done || 0;
      const pct = total ? ` (${Math.min(100, Math.floor(done / total * 100))}%)` : "";
      msg.textContent = total
        ? `Downloading… ${fmtBytes(done)} / ${fmtBytes(total)}${pct}`
        : `Downloading… ${fmtBytes(done)}`;
      msg.classList.remove("hidden");
      break;
    }
    case "confirm":
      msg.textContent = "Replace swiftsstp.exe and restart?"; // ask before replacing
      msg.classList.remove("hidden");
      ins.textContent = "Replace";
      act.classList.remove("hidden");
      later.classList.remove("hidden");
      break;
    case "applying":
      ins.classList.add("hidden");
      msg.textContent = "Restarting…";
      msg.classList.remove("hidden");
      break;
    case "error":
      ins.classList.add("hidden");
      msg.textContent = state.updateError || "update failed";
      msg.classList.add("err");
      msg.classList.remove("hidden");
      act.classList.remove("hidden");
      // Unwritable folder (e.g. Program Files): retry cannot help —
      // offer the release page instead.
      (state.updateUnwritable ? page : retry).classList.remove("hidden");
      break;
  }
}

async function startDownload() {
  if (!state.update) return;
  state.updatePhase = "downloading";
  state.updatePath = "";
  state.updateError = "";
  state.updateUnwritable = false;
  state.updateProgress = { done: 0, total: state.update.size || 0 };
  renderUpdate();
  // update_download blocks until the file is checksum-verified; poll the
  // progress in parallel (Wails runs each Call in its own goroutine).
  updatePoll = setInterval(async () => {
    try {
      const p = await call("update_progress");
      if (state.updatePhase === "downloading") {
        state.updateProgress = p;
        renderUpdate();
      }
    } catch { /* keep the last numbers */ }
  }, UPDATE_POLL_MS);
  try {
    const d = await call("update_download");
    state.updatePath = (d && d.path) || "";
    state.updatePhase = "confirm";
  } catch (e) {
    state.updatePhase = "error";
    state.updateError = e.message || "download failed";
  } finally {
    clearInterval(updatePoll);
    updatePoll = null;
    renderUpdate();
  }
}

async function applyUpdate() {
  state.updatePhase = "applying";
  state.updateError = "";
  state.updateUnwritable = false;
  renderUpdate();
  try {
    const d = await call("update_apply");
    if (d && d.applied === false) {
      // Nothing was replaced: explain why and offer the fallback.
      state.updatePhase = "error";
      state.updateUnwritable = !!d.unwritable;
      state.updateError = d.error || "update could not be installed";
    }
    // applied → the backend restarts the app into the new build; the
    // card stays on "Restarting…".
  } catch (e) {
    state.updatePhase = "error";
    state.updateError = e.message || "install failed";
  } finally { renderUpdate(); }
}

/* "What's new" / fallback: open the release page in the browser. */
async function openReleasePage() {
  try { await call("update_notes"); }
  catch (e) { toast(e.message, true); }
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
    // Only per-profile tags left: TLS and username. Reconnect, upstream
    // proxy, tunnel count and the local proxy knobs are app-wide now
    // (Settings owns them), so they no longer describe one profile.
    card.innerHTML = `
      <div class="p-name">${esc(p.name || "unnamed")}</div>
      <div class="p-host">${esc(p.host)}:${p.port || 443}${p.sni ? " · sni " + esc(p.sni) : ""}</div>
      <div class="p-meta">
        ${tlsTag(p)}
        <span class="tag">${esc(p.username || "—")}</span>
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

/* The editor only owns what makes a connection to THAT server: address,
 * credentials and certificate handling. The tunnel options (MTU,
 * families, DNS, reconnect, upstream proxy, tunnels) and the local
 * proxy knobs (SOCKS5 / HTTP ports, system proxy) live in the Settings
 * view and settings.json. */
function fillForm(p) {
  $("f-name").value = p.name || "";
  $("f-host").value = p.host || "";
  $("f-port").value = p.port || 443;
  $("f-sni").value = p.sni || "";
  $("f-user").value = p.username || "";
  $("f-pass").value = p.password || "";
  $("f-tls").value = p.tls_mode || "verify";
  $("f-ca").value = p.ca_path || "";
  $("ca-wrap").classList.toggle("hidden", $("f-tls").value !== "custom_ca");
}

function collectForm() {
  const p = state.editing || {};
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
    // Always send an object: the schema keeps extras open but empty on
    // Windows now, and this drops any legacy socks_port / http_port /
    // system_proxy an old profiles.json still carried in the entry.
    extras: {},
  };
}

/* ---------------- settings (app-wide) ---------------- */
function fillSettings(s) {
  const ar = s.auto_reconnect || {};
  $("f-mtu").value = s.mtu || 0;
  $("f-ipv4").checked = !!s.ipv4_enabled;
  $("f-ipv6").checked = !!s.ipv6_enabled;
  $("f-dns").value = (s.dns_override || []).join("\n");
  $("f-reconnect").checked = ar.enabled !== false;
  $("f-backoff-init").value = ar.initial_backoff_ms || 1000;
  $("f-backoff-max").value = ar.max_backoff_ms || 30000;
  const px = s.proxy || {};
  $("f-proxy-type").value = px.type || "";
  $("f-proxy-host").value = px.host || "";
  $("f-proxy-port").value = px.port || 3128;
  $("f-proxy-user").value = px.username || "";
  $("f-proxy-pass").value = px.password || "";
  toggleProxyFields();
  // Local proxy inbounds started in proxy mode (app-wide since the
  // knobs left the profile editor).
  $("f-socks").value = s.socks_port || 10808;
  $("f-http").value = s.http_port || 10809;
  $("f-sysproxy").checked = !!s.system_proxy;
  $("f-tunnels").value = clampTunnels(s.tunnels);
}

function collectSettings() {
  const dns = $("f-dns").value.split(/[\r\n,]+/).map(s => s.trim()).filter(Boolean);
  return {
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
    // A blank or nonsense port falls back to the default, mirroring the
    // other integer fields in this form.
    socks_port: parseInt($("f-socks").value, 10) || 10808,
    http_port: parseInt($("f-http").value, 10) || 10809,
    system_proxy: $("f-sysproxy").checked,
    tunnels: clampTunnels($("f-tunnels").value),
  };
}

async function loadSettings() {
  const s = await call("settings_get"); // migrates settings.json if needed
  state.settings = s || {};
  fillSettings(state.settings);
}

/* Explicit save with a visible outcome: the backend validates the
   settings and writes settings.json. Nothing here auto-saves. */
async function saveSettings() {
  try {
    state.settings = await call("settings_save", { settings: collectSettings() });
    fillSettings(state.settings);
    toast("Settings saved");
  } catch (e) { toast(e.message, true); }
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
  // The picker owns the selection; connect always reads it from state.
  const id = state.selectedProfile;
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

  // Profile dropdown: the trigger toggles the list, rows select, a click
  // outside (or Escape) closes, and arrows move the highlight. Enter and
  // Space open/select from the keyboard.
  const profileKeyAt = { at: 0 }; // last keyboard activation (click guard)
  const trigger = $("conn-profile-trigger");
  trigger.addEventListener("click", () => {
    if (Date.now() - profileKeyAt.at < 250) return; // synthesized click
    if (profileMenuOpen()) closeProfileMenu();
    else openProfileMenu();
  });
  trigger.addEventListener("keydown", e => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!profileMenuOpen()) { openProfileMenu(); return; }
      const items = profileMenuItems();
      if (!items.length) return;
      const dir = e.key === "ArrowDown" ? 1 : -1;
      const next = profileActive < 0 ? (dir > 0 ? 0 : items.length - 1) : profileActive + dir;
      setActiveProfileOption(next < 0 ? items.length - 1 : next % items.length);
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      profileKeyAt.at = Date.now();
      if (!profileMenuOpen()) { openProfileMenu(); return; }
      const items = profileMenuItems();
      const li = items[profileActive >= 0 ? profileActive : 0];
      if (li) selectProfile(li.dataset.id);
      closeProfileMenu();
    } else if (e.key === "Escape" && profileMenuOpen()) {
      e.preventDefault();
      closeProfileMenu();
    } else if (e.key === "Tab") {
      closeProfileMenu();
    }
  });
  $("conn-profile-menu").addEventListener("click", e => {
    const li = e.target.closest(".profile-option");
    if (!li || li.getAttribute("aria-disabled") === "true") return;
    selectProfile(li.dataset.id);
    closeProfileMenu();
    trigger.focus();
  });
  document.addEventListener("click", e => {
    if (profileMenuOpen() && !$("conn-profile").contains(e.target)) closeProfileMenu();
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

  // Update card: Install starts the download; the same white pill then
  // confirms the replace-and-restart. Retry redoes whichever step failed.
  $("btn-update-install").addEventListener("click", () => {
    if (state.updatePhase === "confirm") applyUpdate();
    else if (state.updatePhase === "ready") startDownload();
  });
  $("btn-update-later").addEventListener("click", () => {
    state.updatePhase = "ready";
    renderUpdate();
  });
  $("btn-update-retry").addEventListener("click", () => {
    if (state.updatePath) applyUpdate();
    else startDownload();
  });
  $("btn-update-page").addEventListener("click", openReleasePage);

  // Manual update check: this button is the ONLY caller of update_check
  // — no launch check, no interval anywhere. The orange top-bar pill
  // takes you here instead of re-checking, because the offer card lives
  // on this tab now.
  $("btn-check-update").addEventListener("click", checkUpdate);
  $("update-pill").addEventListener("click", () => { location.hash = "#/settings"; });

  // Settings: explicit save only — nothing writes settings.json until
  // this button is pressed.
  $("btn-save-settings").addEventListener("click", saveSettings);
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
    await loadSettings(); // app-wide options for the Settings tab
  } catch (e) { toast(e.message, true); }

  // Running version ("dev" for local builds) — shown in the Settings
  // row and the gate that keeps a local build from ever asking GitHub
  // anything.
  try {
    const info = await call("app_info");
    state.version = (info && info.version) || "";
  } catch { /* unknown: treat like a dev build */ }
  // Paint the row only: the check itself waits for the user's tap.
  renderUpdateRow();

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
