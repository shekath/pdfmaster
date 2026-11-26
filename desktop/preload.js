// Bridge between the Windows shell and the web app.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('pdfmaster', {
  desktop: true,
  /** cb receives [{ name, data: Uint8Array }] whenever PDFs are opened from Explorer or File > Open. */
  onOpenFiles(cb) {
    ipcRenderer.on('open-files', (_e, list) => cb(list.map((f) => ({ name: f.name, data: f.data }))));
    ipcRenderer.send('renderer-ready');
  },
  openDialog: () => ipcRenderer.invoke('open-dialog'),
});
