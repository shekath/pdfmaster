# PDF Master for Windows

Electron shell around the web app in `../web`, with a per-user NSIS installer that registers PDF Master for `.pdf` files.

- `main.js` loads the web app from the packaged `resources/web` over a private `app://` scheme, keeps a single window,
  and forwards PDFs passed by Explorer (`"PDF Master.exe" "file.pdf"`) or File → Open to the app (viewer screen).
- `preload.js` exposes `window.pdfmaster.onOpenFiles()` to the web app.
- `afterPack.js` stamps the icon and version into `PDF Master.exe` (pure JS, no Wine).
- `installer/installer.nsi` installs to `%LOCALAPPDATA%\Programs\PDF Master`, creates shortcuts and the uninstaller, and
  writes the ProgID, `OpenWithProgids`, `Applications\PDF Master.exe` and `RegisteredApplications` entries so PDF Master
  shows up in **Open with** and **Settings → Default apps**. Windows 10/11 only let the user set the default app, so the
  last installer page offers to open that Settings page.

```bash
npm install
npm run dist        # → dist/PDF-Master-Setup-<version>.exe and dist/PDF-Master-Portable-<version>.zip
npm start           # run locally (uses ../web)
```
