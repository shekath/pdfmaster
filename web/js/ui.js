// Small DOM helpers.
import { ICONS } from './tools.js';

export function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'html') el.innerHTML = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  return el;
}

export function icon(name, size) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('class', 'i'); s.setAttribute('aria-hidden', 'true');
  if (size) { s.style.width = size + 'px'; s.style.height = size + 'px'; }
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path'); p.setAttribute('d', ICONS[name] ?? ''); s.append(p);
  return s;
}

export const iconBtn = (name, label, onclick, attrs = {}) => h('button', { class: 'icon-btn', 'aria-label': label, title: label, onclick, ...attrs }, icon(name));

export function toolIcon(name, tint, box = 40) {
  return h('div', { class: 'ticon', style: { width: box + 'px', height: box + 'px', background: tint + '1f', color: tint } }, icon(name));
}

let toastTimer;
export function toast(msg, ms = 2600) {
  const t = document.getElementById('toast'); t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, ms);
}

export function humanSize(n) { return n < 1024 ? `${n} B` : n < 1048576 ? `${Math.round(n / 1024)} KB` : `${(n / 1048576).toFixed(1)} MB`; }

export function dialog(title, body, actions) {
  const d = h('dialog', {}, h('h2', { style: { marginBottom: '14px' } }, title), body, h('div', { class: 'row', style: { justifyContent: 'flex-end', marginTop: '18px', gap: '6px', flexWrap: 'wrap' } }, ...actions.map(([label, fn, cls]) => h('button', { class: 'btn ' + (cls ?? 'outline'), style: { padding: '10px 16px' }, onclick: () => { if (fn?.() !== false) d.close(); } }, label))));
  d.addEventListener('close', () => d.remove());
  document.body.append(d); d.showModal(); return d;
}

export function pickFiles(accept, multiple, capture) {
  return new Promise((res) => {
    const i = h('input', { type: 'file', accept, multiple: multiple || null, capture: capture ? 'environment' : null, style: { display: 'none' } });
    i.addEventListener('change', () => { res([...i.files]); i.remove(); });
    document.body.append(i); i.click();
  });
}

export const MIME = { pdf: 'application/pdf', docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', zip: 'application/zip', txt: 'text/plain', jpg: 'image/jpeg', png: 'image/png' };
export const mimeOf = (name) => MIME[name.split('.').pop().toLowerCase()] ?? 'application/octet-stream';

export function download(name, blob) {
  const url = URL.createObjectURL(blob); const a = h('a', { href: url, download: name }); document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
export async function share(name, blob) {
  const file = new File([blob], name, { type: blob.type || mimeOf(name) });
  if (navigator.canShare?.({ files: [file] })) { try { await navigator.share({ files: [file], title: name }); } catch { /* cancelled */ } }
  else { download(name, blob); toast('Sharing isn’t supported here, so the file was downloaded.'); }
}
export function openFile(blob) { window.open(URL.createObjectURL(blob), '_blank'); }
