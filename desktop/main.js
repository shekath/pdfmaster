// PDF Master for Windows: an Electron shell around the web app in ../web.
// PDFs opened from Explorer ("Open with" / double-click when PDF Master is the default) are passed to the app.
const { app, BrowserWindow, protocol, net, ipcMain, dialog, Menu, shell, nativeTheme } = require('electron');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');

protocol.registerSchemesAsPrivileged([{
  scheme: 'app',
  privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true, allowServiceWorkers: true },
}]);

const WEB = app.isPackaged ? path.join(process.resourcesPath, 'web') : path.join(__dirname, '..', 'web');
let win = null;
let rendererReady = false;
let pending = [];

/** PDF paths among command-line arguments (Explorer passes the file path as an argument). */
function pdfArgs(argv) {
  return argv.slice(1).filter((a) => !a.startsWith('-') && /\.pdf$/i.test(a) && fs.existsSync(a));
}

function sendFiles(paths) {
  if (!paths.length) return;
  if (!rendererReady || !win) { pending.push(...paths); return; }
  const list = [];
  for (const p of paths) {
    try { list.push({ name: path.basename(p), data: fs.readFileSync(p) }); } catch (e) { dialog.showErrorBox('Could not open file', `${p}\n\n${e.message}`); }
  }
  if (list.length) win.webContents.send('open-files', list);
}

async function openDialog() {
  const r = await dialog.showOpenDialog(win, { title: 'Open PDF', filters: [{ name: 'PDF documents', extensions: ['pdf'] }], properties: ['openFile', 'multiSelections'] });
  if (!r.canceled) sendFiles(r.filePaths);
}

function createWindow() {
  win = new BrowserWindow({
    width: 1240, height: 880, minWidth: 380, minHeight: 560,
    title: 'PDF Master',
    icon: path.join(__dirname, 'build', 'icon.ico'),
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#101217' : '#F6F7FA',
    show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, plugins: true, spellcheck: false },
  });
  win.once('ready-to-show', () => win.show());
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('blob:')) return { action: 'allow', overrideBrowserWindowOptions: { width: 1000, height: 820, autoHideMenuBar: true, webPreferences: { plugins: true } } };
    if (/^https?:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('app://')) { e.preventDefault(); if (/^https?:/.test(url)) shell.openExternal(url); } });
  win.on('closed', () => { win = null; rendererReady = false; });
  win.loadURL('app://pdfmaster/index.html');
}

function buildMenu() {
  const template = [
    { label: '&File', submenu: [
      { label: 'Open PDF…', accelerator: 'CmdOrCtrl+O', click: openDialog },
      { type: 'separator' },
      { role: 'quit', label: 'Exit' },
    ] },
    { label: '&Edit', submenu: [{ role: 'undo' }, { role: 'redo' }, { type: 'separator' }, { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' }] },
    { label: '&View', submenu: [{ role: 'reload' }, { type: 'separator' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' }, { role: 'togglefullscreen' }] },
    { label: '&Help', submenu: [
      { label: 'Make PDF Master the default PDF app…', click: () => shell.openExternal('ms-settings:defaultapps') },
      { label: 'PDF Master website', click: () => shell.openExternal('https://shekathali.com/pdf/') },
      { type: 'separator' },
      { label: `About PDF Master ${app.getVersion()}`, click: () => dialog.showMessageBox(win, { type: 'info', title: 'PDF Master', message: `PDF Master ${app.getVersion()}`, detail: 'Every PDF tool, on your PC. Files are processed on this computer and never uploaded.' }) },
    ] },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  // A second launch (e.g. opening another PDF from Explorer) goes to the running window.
  app.on('second-instance', (_e, argv) => {
    if (win) { if (win.isMinimized()) win.restore(); win.focus(); }
    sendFiles(pdfArgs(argv));
  });
  ipcMain.on('renderer-ready', () => { rendererReady = true; const p = pending; pending = []; sendFiles(p); });
  ipcMain.handle('open-dialog', openDialog);
  app.whenReady().then(() => {
    protocol.handle('app', (req) => {
      const u = new URL(req.url);
      let p = decodeURIComponent(u.pathname);
      if (p.endsWith('/')) p += 'index.html';
      const full = path.normalize(path.join(WEB, p));
      if (!full.startsWith(WEB)) return new Response('Forbidden', { status: 403 });
      return net.fetch(pathToFileURL(full).toString());
    });
    buildMenu();
    pending = pdfArgs(process.argv);
    createWindow();
  });
  app.on('window-all-closed', () => app.quit());
}
