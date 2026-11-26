// Stamps the app icon and version details into "PDF Master.exe" without Wine (pure JS via resedit).
const fs = require('fs');
const path = require('path');

exports.default = async function afterPack(ctx) {
  if (ctx.electronPlatformName !== 'win32') return;
  const ResEdit = await import('resedit').then((m) => m.default ?? m);
  const exe = path.join(ctx.appOutDir, `${ctx.packager.appInfo.productFilename}.exe`);
  const version = ctx.packager.appInfo.version; // e.g. 1.1.0
  const [maj, min, pat] = version.split('.').map((n) => parseInt(n, 10) || 0);

  const pe = ResEdit.NtExecutable.from(fs.readFileSync(exe), { ignoreCert: true });
  const res = ResEdit.NtExecutableResource.from(pe);

  // Replace the main icon group (the first RT_GROUP_ICON) with our icon.
  const iconFile = ResEdit.Data.IconFile.from(fs.readFileSync(path.join(__dirname, 'build', 'icon.ico')));
  const group = res.entries.find((e) => e.type === 14);
  ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries, group ? group.id : 1, group ? group.lang : 1033, iconFile.icons.map((i) => i.data));

  const vi = ResEdit.Resource.VersionInfo.fromEntries(res.entries)[0];
  const lang = vi.getAllLanguagesForStringValues()[0] ?? { lang: 1033, codepage: 1200 };
  vi.setStringValues(lang, {
    FileDescription: 'PDF Master', ProductName: 'PDF Master', CompanyName: 'PDF Master', LegalCopyright: 'PDF Master',
    InternalName: 'PDF Master', OriginalFilename: 'PDF Master.exe', FileVersion: version, ProductVersion: version,
  });
  vi.setFileVersion(maj, min, pat, 0, lang.lang);
  vi.setProductVersion(maj, min, pat, 0, lang.lang);
  vi.outputToResourceEntries(res.entries);

  res.outputResource(pe);
  fs.writeFileSync(exe, Buffer.from(pe.generate()));
  console.log(`  • stamped icon and version ${version} into ${path.basename(exe)}`);
};
