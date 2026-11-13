# PDF Master — web app (iPhone, iPad, Android, desktop)

The same 31 tools as the Android app, running entirely in the browser (pdf-lib, pdf.js, qpdf WebAssembly,
Tesseract.js). It installs to the iPhone home screen and works offline after the first visit.

## Install on iPhone / iPad
1. Open the app's address in **Safari** (e.g. `https://shekath.github.io/pdfmaster/`).
2. Tap **Share** → **Add to Home Screen** → **Add**.
3. Open *PDF Master* from the home screen. It runs full-screen and keeps working offline.

## Live copy
https://shekathali.com/pdf/ (Hostinger, `public_html/pdf`). The `.htaccess` in this folder sets the right types for `.mjs`, `.wasm` and the manifest, which Apache needs.

## Host it yourself
Any static HTTPS host works (GitHub Pages, Netlify, Cloudflare Pages, your own server): upload the contents of
this folder. Service workers and "Add to Home Screen" need HTTPS (or `localhost`).

## Notes
- OCR language data (English ~2 MB, Indian languages ~6–12 MB each) downloads from cdn.jsdelivr.net the first
  time a language is used, then is cached.
- Translate sends the document text to the MyMemory translation service; nothing else leaves the device.
- Edit text on the web version covers the original line and writes the new text on top (the Android app
  removes the original text from the file).
